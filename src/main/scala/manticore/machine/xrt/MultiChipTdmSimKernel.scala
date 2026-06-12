package manticore.machine.xrt

import chisel3._
import chisel3.util._
import manticore.machine.ManticoreFullISA
import manticore.machine.core.ClockDistribution
import manticore.machine.core.ComputeArray
import manticore.machine.core.DeviceRegisters
import manticore.machine.core.HostRegisters
import manticore.machine.core.ManticoreFlatArray
import manticore.machine.core.NoCBundle
import manticore.machine.core.TdmFrame
import manticore.machine.core.TdmTorusBoundaryBridge
import manticore.machine.memory.CacheConfig

/** A `chipCols x chipRows` grid of `chipDimX x chipDimY` chips forming ONE global
  * 2D torus of `gDimX x gDimY` cores (e.g. 8 chips of 4x4 as a 2x4 grid -> 8x16),
  * using the PER-SIDE seam model (extendEast/West/North/South).
  *
  * Each ring is cut twice per chip into two balanced halves; chips chained in a
  * dimension FOLD the global ring through each chip (out-chain through the first
  * half, back-chain through the second), so:
  *   - every cable is strictly neighbour-to-neighbour (chip k.east <-> chip k+1.west,
  *     chip k.north <-> chip k+1.south) — NO global wrap cable;
  *   - the END chips of a chain simply leave their outer side U-turned
  *     (extend=false), which closes the global ring;
  *   - per cable, one TdmTorusBoundaryBridge per end serializes that side's
  *     2*nLinks logical links onto one transceiver pair, exactly as in the verified
  *     two-chip kernel (period = 2*nLinks, wire = T - period - 2, bypass wire T-3,
  *     every crossing exactly T compute cycles).
  *
  * Chip (0,0) is the FULL ManticoreFlatArray (controller/bootloader/cache + the
  * privileged core at global (0,0) = its local (0,0), the out-chain start of both
  * dimensions). All other chips are bare ComputeArrays on the master's gated compute
  * clock and soft reset, booted entirely over the seams.
  *
  * Boot bypass is TRAFFIC-TRIGGERED PER CABLE (the two-chip kernel's tailHold
  * pattern): each cable holds bypass while config_enable is high or its own seam
  * carried a frame within the last bypassWire+2 cycles — so the chained boot tail
  * (countdowns crossing several cables in sequence) drains each cable in bypass
  * mode, and at exception resumes the quiet seams drop to exec mode immediately.
  */
class MultiChipTdmSimKernel(
    chipCols: Int = 2,
    chipRows: Int = 4,
    chipDimX: Int = 4,
    chipDimY: Int = 4,
    enable_custom_alu: Boolean = false,
    cyclesPerSlot: Int = 1,
    seamLatency: Int = 25 // T: constant register-to-register seam latency (== the CSV value)
) extends Module {

  clock.suggestName("ap_clk")
  reset.suggestName("ap_rst")

  val gDimX = chipCols * chipDimX
  val gDimY = chipRows * chipDimY
  require(chipCols >= 1 && chipRows >= 1 && chipCols * chipRows >= 2)

  def execWire(nLinks: Int)   = seamLatency - 2 * nLinks * cyclesPerSlot - 2 // EXACT, see TdmLinkDemux
  val bypassWireLatency       = seamLatency - 3
  require(execWire(chipDimX) >= 1 && execWire(chipDimY) >= 1, s"seamLatency $seamLatency too small")

  class KernelRegisters extends Bundle {
    val host   = Input(new HostRegisters)
    val device = Output(new DeviceRegisters)
  }
  class KernelControl extends Bundle {
    val start: Bool = Input(Bool())
    val done: Bool  = Output(Bool())
    val idle: Bool  = Output(Bool())
  }
  class DirectMemoryInterface extends Bundle {
    val rdata: UInt  = Output(UInt(16.W))
    val wdata: UInt  = Input(UInt(16.W))
    val locked: Bool = Input(Bool())
    val wen: Bool    = Input(Bool())
    val addr: UInt   = Input(UInt(64.W))
  }
  class KernelInterface extends Bundle {
    val kernel_registers  = new KernelRegisters
    val kernel_ctrl       = new KernelControl
    val dmi               = new DirectMemoryInterface
    val dbg_seam_overflow = Output(Bool()) // steady-state mux OR demux overflow (any cable)
    val dbg_seam_dataloss = Output(Bool()) // steady-state DEMUX overflow only (real loss)
  }
  val io = IO(new KernelInterface)

  val clock_distribution = Module(new ClockDistribution())
  clock_distribution.io.root_clock := clock

  // ---------------- master chip (0,0) ----------------
  val master = Module(new ManticoreFlatArray(chipDimX, chipDimY, debug_enable = false,
    enable_custom_alu, torusDimX = gDimX, torusDimY = gDimY))

  master.io.reset         := reset
  master.io.control_clock := clock_distribution.io.control_clock
  master.io.compute_clock := clock_distribution.io.compute_clock
  master.io.clock_stabled := clock_distribution.io.locked
  master.io.host_registers := io.kernel_registers.host
  io.kernel_registers.device := master.io.device_registers
  master.io.start     := io.kernel_ctrl.start
  io.kernel_ctrl.done := master.io.done
  io.kernel_ctrl.idle := master.io.idle
  clock_distribution.io.compute_clock_en := master.io.clock_active

  val mMc = master.mc.get
  val softReset    = mMc.softResetOut
  val configEnable = mMc.configEnableOut

  // uniform per-side view over master (mc bundle) and slaves (ComputeArray io)
  case class SidePorts(extend: Bool, fwdOut: Vec[NoCBundle], fwdIn: Vec[NoCBundle],
                       bwdOut: Vec[NoCBundle], bwdIn: Vec[NoCBundle])
  case class ChipPorts(east: SidePorts, west: SidePorts, north: SidePorts, south: SidePorts)

  val ports: Seq[Seq[ChipPorts]] = Seq.tabulate(chipCols) { cx =>
    Seq.tabulate(chipRows) { cy =>
      if (cx == 0 && cy == 0) {
        ChipPorts(
          SidePorts(mMc.east.extend, mMc.east.fwdOut, mMc.east.fwdIn, mMc.east.bwdOut, mMc.east.bwdIn),
          SidePorts(mMc.west.extend, mMc.west.fwdOut, mMc.west.fwdIn, mMc.west.bwdOut, mMc.west.bwdIn),
          SidePorts(mMc.north.extend, mMc.north.fwdOut, mMc.north.fwdIn, mMc.north.bwdOut, mMc.north.bwdIn),
          SidePorts(mMc.south.extend, mMc.south.fwdOut, mMc.south.fwdIn, mMc.south.bwdOut, mMc.south.bwdIn))
      } else {
        val c = withClockAndReset(clock_distribution.io.compute_clock, softReset) {
          Module(new ComputeArray(chipDimX, chipDimY, debug_enable = false, enable_custom_alu,
            prefix_path = ".", n_hop = 1, torusDimX = gDimX, torusDimY = gDimY))
        }
        c.suggestName(s"chip_${cx}_${cy}")
        c.io.config_enable := false.B
        c.io.config_packet := NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)
        c.io.mem_access.done  := false.B
        c.io.mem_access.idle  := true.B
        c.io.mem_access.rdata := 0.U
        ChipPorts(
          SidePorts(c.io.extendEast, c.io.east.fwdOut, c.io.east.fwdIn, c.io.east.bwdOut, c.io.east.bwdIn),
          SidePorts(c.io.extendWest, c.io.west.fwdOut, c.io.west.fwdIn, c.io.west.bwdOut, c.io.west.bwdIn),
          SidePorts(c.io.extendNorth, c.io.north.fwdOut, c.io.north.fwdIn, c.io.north.bwdOut, c.io.north.bwdIn),
          SidePorts(c.io.extendSouth, c.io.south.fwdOut, c.io.south.fwdIn, c.io.south.bwdOut, c.io.south.bwdIn))
      }
    }
  }

  // extends are static per grid position: a side extends iff a neighbour exists there
  for (cx <- 0 until chipCols; cy <- 0 until chipRows) {
    val p = ports(cx)(cy)
    p.east.extend  := (cx < chipCols - 1).B
    p.west.extend  := (cx > 0).B
    p.north.extend := (cy < chipRows - 1).B
    p.south.extend := (cy > 0).B
    // U-turned outer sides never receive: tie their ingress empty
    def tie(s: SidePorts): Unit = {
      s.fwdIn := VecInit(Seq.fill(s.fwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
      s.bwdIn := VecInit(Seq.fill(s.bwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
    }
    if (cx == chipCols - 1) tie(p.east)
    if (cx == 0) tie(p.west)
    if (cy == chipRows - 1) tie(p.north)
    if (cy == 0) tie(p.south)
  }

  // ---------------- one TDM cable: a.side <-> b.side, bridge per end ----------------
  // All seam logic on the GATED COMPUTE clock (constant-latency contract is in
  // compute cycles and must freeze with the array).
  val muxOverflows   = scala.collection.mutable.ArrayBuffer.empty[Bool]
  val demuxOverflows = scala.collection.mutable.ArrayBuffer.empty[Bool]

  def cable(a: SidePorts, b: SidePorts, nLinks: Int, nameHint: String): Unit = {
    withClockAndReset(clock_distribution.io.compute_clock, reset) {
      def mkBridge() = Module(new TdmTorusBoundaryBridge(nLinks, cyclesPerSlot,
        execWire(nLinks), seamLatency, gDimX, gDimY, ManticoreFullISA))
      val bridgeA = mkBridge(); bridgeA.suggestName(s"bridge_${nameHint}_a")
      val bridgeB = mkBridge(); bridgeB.suggestName(s"bridge_${nameHint}_b")

      bridgeA.io.fwdOut := a.fwdOut
      bridgeA.io.bwdOut := a.bwdOut
      a.fwdIn := bridgeA.io.fwdIn
      a.bwdIn := bridgeA.io.bwdIn
      bridgeB.io.fwdOut := b.fwdOut
      bridgeB.io.bwdOut := b.bwdOut
      b.fwdIn := bridgeB.io.fwdIn
      b.bwdIn := bridgeB.io.bwdIn

      // traffic-triggered bypass (two-chip kernel pattern, per cable)
      val tailHold = bypassWireLatency + 2
      val tail     = RegInit(0.U(log2Ceil(tailHold + 1).W))
      val bypass   = configEnable || tail =/= 0.U
      val anyFrame = bridgeA.io.tx.valid || bridgeB.io.tx.valid
      when(bypass && anyFrame) { tail := tailHold.U }.elsewhen(tail =/= 0.U) { tail := tail - 1.U }
      bridgeA.io.bypass := bypass
      bridgeB.io.bypass := bypass
      bridgeA.io.connected := true.B
      bridgeB.io.connected := true.B

      // transceiver: dual-depth pipes, mode stable across a frame's flight
      def wirePipe(src: TdmFrame): TdmFrame = {
        val deep    = ShiftRegister(src, bypassWireLatency)
        val shallow = ShiftRegister(src, execWire(nLinks))
        val out     = Wire(chiselTypeOf(src))
        out := Mux(bypass, deep, shallow)
        out
      }
      bridgeB.io.rx := wirePipe(bridgeA.io.tx)
      bridgeA.io.rx := wirePipe(bridgeB.io.tx)

      muxOverflows += (bridgeA.io.muxOverflow && !bypass)
      muxOverflows += (bridgeB.io.muxOverflow && !bypass)
      demuxOverflows += (bridgeA.io.demuxOverflow && !bypass)
      demuxOverflows += (bridgeB.io.demuxOverflow && !bypass)
    }
  }

  for (cy <- 0 until chipRows; cx <- 0 until chipCols - 1)
    cable(ports(cx)(cy).east, ports(cx + 1)(cy).west, chipDimY, s"x_${cx}_${cy}")
  for (cx <- 0 until chipCols; cy <- 0 until chipRows - 1)
    cable(ports(cx)(cy).north, ports(cx)(cy + 1).south, chipDimX, s"y_${cx}_${cy}")

  withClockAndReset(clock_distribution.io.compute_clock, reset) {
    val ovf  = RegInit(false.B)
    val loss = RegInit(false.B)
    when(muxOverflows.reduce(_ || _) || demuxOverflows.reduce(_ || _)) { ovf := true.B }
    when(demuxOverflows.reduce(_ || _)) { loss := true.B }
    io.dbg_seam_overflow := ovf
    io.dbg_seam_dataloss := loss
  }

  // ---------------- master cache subsystem + sim memory (as in the single-chip kernel) ---
  val axi_cache = withClockAndReset(clock_distribution.io.control_clock, reset) {
    Module(new CacheSubsystem)
  }
  val axi_mem = withClockAndReset(clock_distribution.io.control_clock, reset) {
    Module(new AxiMemoryModel(AxiCacheAdapter.CacheAxiParameters, 1 << 20, ManticoreFullISA.DataBits))
  }
  axi_cache.io.base := 0.U
  axi_cache.io.core <> master.io.memory_backend
  axi_cache.io.bus  <> axi_mem.io.axi
  axi_mem.io.sim.waddr := io.dmi.addr
  axi_mem.io.sim.raddr := io.dmi.addr
  axi_mem.io.sim.lock  := io.dmi.locked
  axi_mem.io.sim.wdata := io.dmi.wdata
  axi_mem.io.sim.wen   := io.dmi.wen
  io.dmi.rdata         := axi_mem.io.sim.rdata
}
