package manticore.machine.xrt

import chisel3._
import chisel3.util._
import manticore.machine.ManticoreFullISA
import manticore.machine.core.DeviceRegisters
import manticore.machine.core.HostRegisters
import manticore.machine.core.ManticoreFlatArray
import manticore.machine.core.NoCBundle
import manticore.machine.core.TdmFrame
import manticore.machine.core.TdmTorusBoundaryBridge
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.SimGmem

/** A `chipCols x chipRows` grid of `chipDimX x chipDimY` chips forming ONE global 2D torus
  * (e.g. 2x4 of 4x4 -> 8x16), with ONE MANAGEMENT UNIT PER IC — i.e. every chip is a full,
  * independent [[ManticoreFlatArray]] with its own controller/bootloader/cache/gmem AND its
  * own [[manticore.machine.core.Management]] (`perChipBoot = true`, `stallWave = true`). This
  * is the faithful model of the bittide hardware demo, where each FPGA runs the full chip and
  * the host drives each chip's Management INDEPENDENTLY over its own DMI/host registers — in
  * contrast to [[MultiChipTdmSimKernel]] (one full master + bare slaves, a single Management)
  * and [[TwoFullChipSimKernel]] (full chips but one broadcast/lockstep host command).
  *
  * Per-IC drive: the host registers, `start`, device registers, `done`/`idle` and the gmem
  * DMI are all per-chip Vecs (indexed `cy*chipCols + cx`, == the demo's `icAt`/FPGA node id),
  * so a tester can load each chip's OWN split image, issue per-chip `CMD_START` (init),
  * `CMD_START_AT(S)` (reset-aligned simultaneous start) and the distributed `$display`
  * flush / `CMD_RESUME`-at-R wave exactly as the real Driver does over the rig's 8 MUs.
  *
  * Seams: the same folded-torus GRID MESH as [[MultiChipTdmSimKernel]] —
  * `east(cx,cy) <-> west(cx+1,cy)`, `north(cx,cy) <-> south(cx,cy+1)`, no wrap cable (the
  * ring wraps U-turn inside the end chips); one [[TdmTorusBoundaryBridge]] per end, constant
  * `seamLatency` (== the compiler's --hop-latencies value).
  *
  * Clock gating: ONE reference clock (`clock`) distributed to every IC; each IC GATES ITS OWN
  * compute clock from it through its own `ClockDistributionNoMmcm` (== `BUFGCE(root, that IC's
  * clock_active)`), exactly like the real `ManticoreBittideChip`. Bittide deasserts every IC's
  * reset on the same cycle, so each IC's free-running `totalCycleCount` (on the ungated control
  * clock = root) is already aligned, and `CMD_START_AT(S)` ungates them all on the same cycle.
  * Crucially the gates are INDEPENDENT: a chip that holds (`clock_active=false` while it waits
  * out boot-skew in `sResumeWait`, or during a stall) freezes ONLY its own compute clock, never
  * its still-booting peers — so there is no boot-skew deadlock (which a single AND-gated shared
  * clock would suffer). The seam bridges run on a clock gated by the AND of all `clock_active`s:
  * the seam is quiet during the skewed per-chip boot and synchronized during main (the heartbeat
  * stalls/resumes all chips on the same vcycle), so a single AND-gated seam clock captures every
  * crossing coherently and freezes with the synchronized stall wave.
  */
class MultiChipPerMgmtSimKernel(
    chipCols: Int = 2,
    chipRows: Int = 4,
    chipDimX: Int = 4,
    chipDimY: Int = 4,
    enable_custom_alu: Boolean = false,
    cyclesPerSlot: Int = 1,
    seamLatency: Int = 25,
    // Per-cable, PER-DIRECTION register-to-register seam latency == the compiler's
    // --hop-latencies value for that directed crossing: (cx, cy, isXcable) -> (latFwd, latBwd).
    // isXcable picks the east(cx,cy) <-> west(cx+1,cy) cable (fwd = (cx,cy)->(cx+1,cy)); else
    // the north(cx,cy) <-> south(cx,cy+1) cable (fwd = (cx,cy)->(cx,cy+1)). The two directions
    // get independent latencies (the real Bittide UGNs are asymmetric); the bridge needs no
    // change — each end's demux is given the latency of the direction it RECEIVES, and each
    // wire pipe the matching depth. Default: uniform 'seamLatency' both ways.
    cableLatency: (Int, Int, Boolean) => (Int, Int) = null
) extends Module {

  clock.suggestName("ap_clk")
  reset.suggestName("ap_rst")

  val gDimX = chipCols * chipDimX
  val gDimY = chipRows * chipDimY
  val nChips = chipCols * chipRows
  require(chipCols >= 1 && chipRows >= 1 && nChips >= 2)

  // Resolve per-cable (fwd, bwd) latencies (fall back to the uniform seamLatency both ways).
  val latOf: (Int, Int, Boolean) => (Int, Int) =
    if (cableLatency == null) ((_, _, _) => (seamLatency, seamLatency)) else cableLatency

  class DirectMemoryInterface extends Bundle {
    val rdata: UInt = Output(UInt(16.W))
    val wdata: UInt = Input(UInt(16.W))
    val locked: Bool = Input(Bool())
    val wen: Bool = Input(Bool())
    val addr: UInt = Input(UInt(64.W))
  }
  class KernelInterface extends Bundle {
    // per-IC, indexed by node id = cy*chipCols + cx (== the demo's icAt / FPGA node)
    val host = Input(Vec(nChips, new HostRegisters))
    val device = Output(Vec(nChips, new DeviceRegisters))
    val start = Input(Vec(nChips, Bool()))
    val done = Output(Vec(nChips, Bool()))
    val idle = Output(Vec(nChips, Bool()))
    val dmi = Vec(nChips, new DirectMemoryInterface)
    val dbg_seam_overflow = Output(Bool()) // any cable mux OR demux overflow
    val dbg_seam_dataloss = Output(Bool()) // any cable DEMUX overflow (real loss)
    val dbg_gate_in_flight = Output(Bool()) // seam clock gated while a frame was in flight (BUG)
  }
  val io = IO(new KernelInterface)

  def idx(cx: Int, cy: Int): Int = cy * chipCols + cx

  // ONE reference clock (`clock`) distributed to every IC; each IC GATES ITS OWN compute
  // clock from it via its own ClockDistributionNoMmcm (== BUFGCE(root, that IC's
  // clock_active)), exactly like the real ManticoreBittideChip. A chip that holds (boot-skew
  // CMD_START_AT wait / stall) freezes ONLY its own compute clock, never its still-booting
  // peers — so there is no boot-skew deadlock. The control clock is the ungated root.
  val resetN = !reset.asBool

  // ---------------- the chips: every IC a full array with its own Management + gate -------
  val chips: Seq[ManticoreFlatArray] = Seq.tabulate(nChips) { n =>
    val c = Module(
      new ManticoreFlatArray(
        chipDimX,
        chipDimY,
        // set to (n == 0) to get TERM/INJ/GMEM traces on the reporter chip for
        // correlating seam-crossed deliveries against the schedule (see the
        // debug(sim) instrumentation in ManticoreFlatArray/Processor)
        debug_enable = false,
        enable_custom_alu,
        torusDimX = gDimX,
        torusDimY = gDimY,
        perChipBoot = true,
        stallWave = true
      )
    )
    c.suggestName(s"chip_${n % chipCols}_${n / chipCols}")
    val cd = Module(new ClockDistributionNoMmcm)
    cd.suggestName(s"clkgate_${n % chipCols}_${n / chipCols}")
    cd.io.root_clock := clock
    cd.io.root_rst_n := resetN
    cd.io.compute_clock_en := c.io.clock_active // this IC gates its own compute clock
    c.io.reset := reset
    c.io.control_clock := cd.io.control_clock
    c.io.compute_clock := cd.io.compute_clock
    c.io.clock_stabled := cd.io.locked
    c.io.host_registers := io.host(n)
    c.io.start := io.start(n)
    io.device(n) := c.io.device_registers
    io.done(n) := c.io.done
    io.idle(n) := c.io.idle
    c
  }

  // Seam clock: gated by the AND of every chip's clock_active. The seam is QUIET during the
  // (skewed) per-chip boot and SYNCHRONIZED during main (the heartbeat stalls/resumes all
  // chips on the same vcycle), so a single AND-gated seam clock captures every crossing
  // coherently and freezes with the synchronized stall wave — without coupling the chips'
  // independent boot clocks.
  val seamEn = chips.map(_.io.clock_active).reduce(_ && _)
  val seamGate = Module(new ClockDistributionNoMmcm)
  seamGate.suggestName("clkgate_seam")
  seamGate.io.root_clock := clock
  seamGate.io.root_rst_n := resetN
  seamGate.io.compute_clock_en := seamEn
  val seamClock = seamGate.io.compute_clock

  // Per-cable "a frame is in flight" flags (seam-clock domain), collected for the
  // gate-while-in-flight assertion below.
  val cableInFlight = scala.collection.mutable.ArrayBuffer.empty[(String, Bool)]

  // ---------------- per-chip seam ports (uniform view over each chip's mc bundle) -------
  case class SidePorts(
      extend: Bool,
      fwdOut: Vec[NoCBundle],
      fwdIn: Vec[NoCBundle],
      bwdOut: Vec[NoCBundle],
      bwdIn: Vec[NoCBundle]
  )
  case class ChipPorts(east: SidePorts, west: SidePorts, north: SidePorts, south: SidePorts)

  val ports: Seq[Seq[ChipPorts]] = Seq.tabulate(chipCols) { cx =>
    Seq.tabulate(chipRows) { cy =>
      val mc = chips(idx(cx, cy)).mc.get
      ChipPorts(
        SidePorts(mc.east.extend, mc.east.fwdOut, mc.east.fwdIn, mc.east.bwdOut, mc.east.bwdIn),
        SidePorts(mc.west.extend, mc.west.fwdOut, mc.west.fwdIn, mc.west.bwdOut, mc.west.bwdIn),
        SidePorts(mc.north.extend, mc.north.fwdOut, mc.north.fwdIn, mc.north.bwdOut, mc.north.bwdIn),
        SidePorts(mc.south.extend, mc.south.fwdOut, mc.south.fwdIn, mc.south.bwdOut, mc.south.bwdIn)
      )
    }
  }

  // A side extends iff a grid neighbour exists there (the torus wraps U-turn inside the end
  // chips). The per-chip-boot contract (boot must be intra-chip: a bootloader packet whose route
  // reaches a boundary must wrap LOCALLY, else it leaks onto a seam and corrupts a neighbour's
  // skewed boot — observed at chip_1_2's fold corner truncating its master body) is now enforced
  // in the RTL: ManticoreFlatArray gates each boundary extend on !config_enable. So this kernel
  // drives the static grid-neighbour extend and the chip itself closes the seam during boot,
  // exactly mirroring the multi-FPGA rig (driver-controlled seam_*_extend, RTL boot-gated).
  for (cx <- 0 until chipCols; cy <- 0 until chipRows) {
    val p = ports(cx)(cy)
    p.east.extend := (cx < chipCols - 1).B
    p.west.extend := (cx > 0).B
    p.north.extend := (cy < chipRows - 1).B
    p.south.extend := (cy > 0).B
    def tie(s: SidePorts): Unit = {
      s.fwdIn := VecInit(Seq.fill(s.fwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
      s.bwdIn := VecInit(Seq.fill(s.bwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
    }
    if (cx == chipCols - 1) tie(p.east)
    if (cx == 0) tie(p.west)
    if (cy == chipRows - 1) tie(p.north)
    if (cy == 0) tie(p.south)
  }

  // ---------------- one TDM cable per grid adjacency (on the gated compute clock) -------
  val muxOverflows = scala.collection.mutable.ArrayBuffer.empty[Bool]
  val demuxOverflows = scala.collection.mutable.ArrayBuffer.empty[Bool]

  // a is the FWD end (chip (cx,cy)), b the BWD end (the +1 neighbour). latAB = a->b crossing
  // latency, latBA = b->a. Each end's demux gets the latency of the direction it RECEIVES
  // (bridgeA receives b->a = latBA; bridgeB receives a->b = latAB), and each wire pipe the
  // matching depth — so the two directions can have independent (asymmetric) latencies.
  def cable(a: SidePorts, b: SidePorts, nLinks: Int, nameHint: String, cfgA: Bool, cfgB: Bool, latAB: Int, latBA: Int): Unit = {
    val period = 2 * nLinks * cyclesPerSlot
    // wire depths: -3 exec / -4 boot (was -2 / -3) absorb the demux io.out pipeline register
    // so the total crossing stays == latAB/latBA (the compiler's --hop-latencies, unchanged).
    val execAB = latAB - period - 3; val bypAB = latAB - 4 // a->b wire depths (exec / boot)
    val execBA = latBA - period - 3; val bypBA = latBA - 4 // b->a wire depths
    require(execAB >= 1, s"cable $nameHint: a->b latency $latAB too small for nLinks=$nLinks (execWire=$execAB)")
    require(execBA >= 1, s"cable $nameHint: b->a latency $latBA too small for nLinks=$nLinks (execWire=$execBA)")
    withClockAndReset(seamClock, reset) {
      // bridgeA demuxes the b->a direction (latBA); bridgeB demuxes a->b (latAB).
      val bridgeA = Module(new TdmTorusBoundaryBridge(nLinks, cyclesPerSlot, execBA, latBA, gDimX, gDimY, ManticoreFullISA))
      val bridgeB = Module(new TdmTorusBoundaryBridge(nLinks, cyclesPerSlot, execAB, latAB, gDimX, gDimY, ManticoreFullISA))
      bridgeA.suggestName(s"bridge_${nameHint}_a")
      bridgeB.suggestName(s"bridge_${nameHint}_b")

      bridgeA.io.fwdOut := a.fwdOut
      bridgeA.io.bwdOut := a.bwdOut
      a.fwdIn := bridgeA.io.fwdIn
      a.bwdIn := bridgeA.io.bwdIn
      bridgeB.io.fwdOut := b.fwdOut
      bridgeB.io.bwdOut := b.bwdOut
      b.fwdIn := bridgeB.io.fwdIn
      b.bwdIn := bridgeB.io.bwdIn

      // Bypass (deep wire) while EITHER end is booting (config_enable) or its seam carried a
      // frame within the last bypassWire+2 cycles, then drop to exec — the verified
      // MultiChipTdmSimKernel pattern, per cable, armed by both ends' per-chip config_enable.
      // One shared bypass select; per-direction deep/exec wire depths. (Boot is seam-quiet, so
      // the bypass->exec transition has no in-flight frames per direction.)
      val tailHold = math.max(bypAB, bypBA) + 2
      val tail = RegInit(0.U(log2Ceil(tailHold + 1).W))
      val anyFrame = bridgeA.io.tx.valid || bridgeB.io.tx.valid
      val bypassW = cfgA || cfgB || tail =/= 0.U
      when(bypassW && anyFrame) { tail := tailHold.U }.elsewhen(tail =/= 0.U) { tail := tail - 1.U }
      bridgeA.io.bypass := bypassW
      bridgeB.io.bypass := bypassW
      bridgeA.io.connected := true.B
      bridgeB.io.connected := true.B

      def wirePipe(src: TdmFrame, execD: Int, bypD: Int): TdmFrame = {
        val deep = ShiftRegister(src, bypD)
        val shallow = ShiftRegister(src, execD)
        val out = Wire(chiselTypeOf(src))
        out := Mux(bypassW, deep, shallow)
        out
      }
      bridgeB.io.rx := wirePipe(bridgeA.io.tx, execAB, bypAB) // a->b, arrives at bridgeB (latAB)
      bridgeA.io.rx := wirePipe(bridgeB.io.tx, execBA, bypBA) // b->a, arrives at bridgeA (latBA)

      // "A frame is in flight" = one was sent within the last `latency` seam cycles (still in
      // the wire / not yet released by the demux), tracked per direction. Used by the
      // gate-while-in-flight assertion: gating the (seam) compute clock with a frame in flight
      // would freeze a message mid-crossing — it must only ever happen on an empty-seam vcycle
      // boundary (the synchronized stall-wave contract).
      // Qualify on bypassW: during per-chip BOOT/idle the bootloader streams config packets in
      // bypass and pulses tx.valid; without this guard the counter is set, then FREEZES nonzero
      // when a chip drops clock_active at init-end (the seam clock stops), and the root-clock gate
      // detector latches a false in-flight violation. bypassW (boot/idle) forces it to 0, so only
      // a genuine mid-crossing frame during armed main (bypassW=false) is ever counted.
      val flightAB = RegInit(0.U(log2Ceil(latAB + 1).W))
      when(bypassW) { flightAB := 0.U }.elsewhen(bridgeA.io.tx.valid) { flightAB := latAB.U }.elsewhen(flightAB =/= 0.U) { flightAB := flightAB - 1.U }
      val flightBA = RegInit(0.U(log2Ceil(latBA + 1).W))
      when(bypassW) { flightBA := 0.U }.elsewhen(bridgeB.io.tx.valid) { flightBA := latBA.U }.elsewhen(flightBA =/= 0.U) { flightBA := flightBA - 1.U }
      cableInFlight += ((nameHint, !bypassW && (flightAB =/= 0.U || flightBA =/= 0.U)))

      muxOverflows += (bridgeA.io.muxOverflow && !bypassW)
      muxOverflows += (bridgeB.io.muxOverflow && !bypassW)
      demuxOverflows += (bridgeA.io.demuxOverflow && !bypassW)
      demuxOverflows += (bridgeB.io.demuxOverflow && !bypassW)

      // DEBUG (seam-direction probe): count each end's transmitted frames and print the
      // first few plus periodic totals. Distinguishes "b-side (the neighbour's south/west
      // side) never transmits" from "transmits but is not delivered" when chasing one-way
      // seam traffic (the rig's sig2=0 / all-RX-frozen symptom).
      val txCntA = RegInit(0.U(32.W))
      val txCntB = RegInit(0.U(32.W))
      when(bridgeA.io.tx.valid) { txCntA := txCntA + 1.U }
      when(bridgeB.io.tx.valid) { txCntB := txCntB + 1.U }
      when(bridgeA.io.tx.valid && txCntA < 3.U) {
        printf(p"[SEAMTX $nameHint A] tag=${bridgeA.io.tx.tag} bypass=$bypassW\n")
      }
      when(bridgeB.io.tx.valid && txCntB < 3.U) {
        printf(p"[SEAMTX $nameHint B] tag=${bridgeB.io.tx.tag} bypass=$bypassW\n")
      }
      val dbgTick = RegInit(0.U(64.W))
      dbgTick := dbgTick + 1.U
      when(dbgTick(19, 0) === 0.U && dbgTick =/= 0.U) {
        printf(p"[SEAMCNT $nameHint] A=$txCntA B=$txCntB\n")
      }
    }
  }

  def cfg(cx: Int, cy: Int): Bool = chips(idx(cx, cy)).mc.get.configEnableOut
  for (cy <- 0 until chipRows; cx <- 0 until chipCols - 1) {
    val (latAB, latBA) = latOf(cx, cy, true)
    cable(ports(cx)(cy).east, ports(cx + 1)(cy).west, chipDimY, s"x_${cx}_${cy}", cfg(cx, cy), cfg(cx + 1, cy), latAB, latBA)
  }
  for (cx <- 0 until chipCols; cy <- 0 until chipRows - 1) {
    val (latAB, latBA) = latOf(cx, cy, false)
    cable(ports(cx)(cy).north, ports(cx)(cy + 1).south, chipDimX, s"y_${cx}_${cy}", cfg(cx, cy), cfg(cx, cy + 1), latAB, latBA)
  }

  withClockAndReset(seamClock, reset) {
    val ovf = RegInit(false.B)
    val loss = RegInit(false.B)
    when(muxOverflows.reduce(_ || _) || demuxOverflows.reduce(_ || _)) { ovf := true.B }
    when(demuxOverflows.reduce(_ || _)) { loss := true.B }
    io.dbg_seam_overflow := ovf
    io.dbg_seam_dataloss := loss
  }

  // ASSERTION: the (seam) compute clock must NEVER gate while a seam frame is in flight — in a
  // correct system a stall lands on an empty-seam vcycle boundary (the synchronized stall-wave
  // contract). Gating mid-crossing freezes a message in transit. On the always-on root clock,
  // detect seamEn falling (the seam clock gating) while any cable still carries a frame: warn
  // live via printf (naming the cable) and latch an error flag for the tester.
  withClockAndReset(clock, reset) {
    val seamEnPrev = RegNext(seamEn, true.B)
    val gating     = seamEnPrev && !seamEn
    val violated   = RegInit(false.B)
    cableInFlight.foreach { case (name, inFlight) =>
      when(gating && inFlight) {
        printf(s"[SEAM-GATE-VIOLATION] seam clock gated while a frame is in flight on cable $name\n")
        violated := true.B
      }
    }
    io.dbg_gate_in_flight := violated
  }

  // ---------------- per-chip gmem (fixed-latency BRAM) ----------------------------------
  for (n <- 0 until nChips) {
    val backend = withClockAndReset(clock, reset) {
      Module(new GmemBramBackend(GmemBramBackend.addrBitsFor(1 << 20)))
    }
    val gmem = withClockAndReset(clock, reset) {
      Module(new SimGmem(1 << 20))
    }
    backend.io.front <> chips(n).io.memory_backend
    gmem.io.bram <> backend.io.bram
    gmem.io.dmi.addr := io.dmi(n).addr
    gmem.io.dmi.wdata := io.dmi(n).wdata
    gmem.io.dmi.wen := io.dmi(n).wen
    io.dmi(n).rdata := gmem.io.dmi.rdata
  }
}
