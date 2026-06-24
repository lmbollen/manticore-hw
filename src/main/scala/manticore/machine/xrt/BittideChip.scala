package manticore.machine.xrt

import chisel3._
import chisel3.util.HasBlackBoxResource
import manticore.machine.ManticoreFullISA
import manticore.machine.core.DeviceRegisters
import manticore.machine.core.HostRegisters
import manticore.machine.core.ManticoreFlatArray
import manticore.machine.core.NoCBundle
import manticore.machine.core.TdmFrame
import manticore.machine.core.TdmTorusBoundaryBridge
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.GmemHalfWordAdapter
import manticore.machine.memory.TrueDualPortBram

/** ClockDistributionNoMmcm.v: no MMCM — control_clock = root_clock (the
  * externally supplied bittide clock, un-rebuffered so chip and surrounding
  * Clash logic share one clock net), compute_clock = BUFGCE(root, en).
  */
class ClockDistributionNoMmcm extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val root_clock       = Input(Clock())
    val root_rst_n       = Input(Bool())
    val compute_clock    = Output(Clock())
    val control_clock    = Output(Clock())
    val compute_clock_en = Input(Bool())
    val locked           = Output(Bool())
    val sync_rst_n       = Output(Bool())
  })
  addResource("/verilog/ClockDistributionNoMmcm.v")
}

/** One Manticore chip for the bittide demo rig (milestone 1: single chip, no
  * seams — the standalone instance, §14 of MANTICORE-KNOWLEDGE.md).
  *
  * Everything is flat pins so a Clash `inst`-based blackbox can wrap it inside
  * a WireDemo UserCore:
  *   - `clk` is the 125 MHz Bittide clock; the whole chip (control side) runs
  *     on it directly (no MMCM), the core array on a BUFGCE child of it.
  *   - host registers / start / done / idle replace the AXI-Lite slave: the
  *     management-unit CPU drives them through wishbone registers.
  *   - the gmem host port (port A) is a raw 32-bit byte-write-enabled BRAM
  *     port: the host window into the chip-local gmem (program image load +
  *     trace readback). The Clash user core maps a management-unit Wishbone
  *     memory region onto it so GDB can bulk-transfer, Bittide clock domain.
  *
  * gmem = TrueDualPortBram inside the chip (fixed-latency, no cache, no
  * clock-kill: see GmemBramBackend). `gmemAddrBits` in 16-bit words.
  */
class ManticoreBittideChip(
    DimX: Int,
    DimY: Int,
    enable_custom_alu: Boolean = true,
    debug_enable: Boolean = false,
    n_hop: Int = 1,
    gmemAddrBits: Int = 16, // 64Ki halfwords = 128 KiB
    // Multi-chip seam: this chip is a DimX x DimY sub-array of a global
    // torusDimX x torusDimY torus. 0 (default) = a single standalone chip (no
    // seams), milestone-1 behaviour. When > 0 the chip boots only its own cores
    // (per-chip boot), runs in stall-wave mode, and exposes one TDM seam per edge.
    torusDimX: Int = 0,
    torusDimY: Int = 0,
    stallWave: Boolean = false,
    // CONSTANT register-to-register seam latency (== the compiler's --hop-latencies
    // value for these links); the external Bittide link supplies the wire latency,
    // wireLatency = seamLatency - 2*nLinks*cyclesPerSlot - 3 (see TdmTorusBoundaryBridge;
    // -3 includes the demux io.out pipeline register). Bumped 25 -> 26: on the rig the
    // transceiver wire is fixed (groomed to goldenUgn+margin), so the io.out register adds
    // a genuine +1 to the seam crossing; the wireLat formula then recovers the SAME groomed
    // wire as before. Sim kernels keep their own seamLatency (their wire pipe absorbs the
    // register, so their crossing is unchanged). Compiler --hop-latencies +1 in Latencies.hs.
    seamLatency: Int = 26,
    cyclesPerSlot: Int = 1
) extends RawModule {

  private val multiChip = torusDimX > 0 || torusDimY > 0
  private val tX        = if (torusDimX > 0) torusDimX else DimX
  private val tY        = if (torusDimY > 0) torusDimY else DimY

  val clk = IO(Input(Clock()))
  val rst = IO(Input(Bool())) // active-high, Bittide domain

  val host_regs    = IO(Input(new HostRegisters))
  val device_regs  = IO(Output(new DeviceRegisters))
  val ctrl_start   = IO(Input(Bool()))
  val ctrl_done    = IO(Output(Bool()))
  val ctrl_idle    = IO(Output(Bool()))
  val clock_active = IO(Output(Bool())) // diagnostics: compute clock enabled

  // Host window into the chip-local gmem BRAM, exposed as a raw 32-bit
  // byte-write-enabled BRAM port (port A). The demo's Clash user core maps a
  // management-unit Wishbone memory region onto this port (via
  // `addressableBytesWb` + a small ReqResp bridge), so GDB can bulk-write the
  // program image and bulk-read the trace instead of poking one 16-bit word
  // per JTAG round-trip. Runs on `control_clock` (= the Bittide clock; no
  // MMCM), the same domain as the surrounding Clash logic.
  val gmem_host_en   = IO(Input(Bool()))
  val gmem_host_we   = IO(Input(UInt(4.W)))
  val gmem_host_addr = IO(Input(UInt((gmemAddrBits - 1).W))) // 32-bit word address
  val gmem_host_din  = IO(Input(UInt(32.W)))
  val gmem_host_dout = IO(Output(UInt(32.W)))

  val clock_distribution = Module(new ClockDistributionNoMmcm)
  clock_distribution.io.root_clock := clk
  clock_distribution.io.root_rst_n := !rst
  private val reset_w              = WireDefault(!clock_distribution.io.sync_rst_n)
  private val control_clock        = clock_distribution.io.control_clock

  val manticore = Module(
    new ManticoreFlatArray(
      DimX,
      DimY,
      debug_enable,
      enable_custom_alu = enable_custom_alu,
      n_hop = n_hop,
      torusDimX = torusDimX,
      torusDimY = torusDimY,
      perChipBoot = multiChip, // each chip boots only its own cores from its own gmem
      stallWave = stallWave
    )
  )
  manticore.io.reset         := reset_w
  manticore.io.control_clock := control_clock
  manticore.io.compute_clock := clock_distribution.io.compute_clock
  manticore.io.clock_stabled := clock_distribution.io.locked

  clock_distribution.io.compute_clock_en := manticore.io.clock_active
  clock_active                           := manticore.io.clock_active

  manticore.io.host_registers := host_regs
  device_regs                 := manticore.io.device_registers
  manticore.io.start          := ctrl_start
  ctrl_done                   := manticore.io.done
  ctrl_idle                   := manticore.io.idle

  // ---- gmem: TDP BRAM; port B = core/bootloader, port A = host DMI ----
  val gmem_backend = withClockAndReset(control_clock, reset_w) {
    Module(new GmemBramBackend(gmemAddrBits))
  }
  val gmem_adapter = withClockAndReset(control_clock, reset_w) {
    Module(new GmemHalfWordAdapter(gmemAddrBits))
  }
  val gmem_bram = Module(new TrueDualPortBram(gmemAddrBits - 1))

  gmem_backend.io.front <> manticore.io.memory_backend
  gmem_adapter.io.gmem <> gmem_backend.io.bram

  gmem_bram.io.clkb  := control_clock
  gmem_bram.io.enb   := gmem_adapter.io.en
  gmem_bram.io.web   := gmem_adapter.io.we
  gmem_bram.io.addrb := gmem_adapter.io.addr
  gmem_bram.io.dinb  := gmem_adapter.io.din
  gmem_adapter.io.dout := gmem_bram.io.doutb

  // host raw BRAM port on port A: driven directly by the Clash ReqResp bridge
  // (which registers address/data/dout on its side for timing). Read-first,
  // 1-cycle read latency, byte write-enables.
  gmem_bram.io.clka  := control_clock
  gmem_bram.io.ena   := gmem_host_en
  gmem_bram.io.wea   := gmem_host_we
  gmem_bram.io.addra := gmem_host_addr
  gmem_bram.io.dina  := gmem_host_din
  gmem_host_dout     := gmem_bram.io.douta

  // ---- multi-chip seams: one TDM bridge per edge, flat TdmFrame tx/rx pins ----
  // Each edge serializes its nLinks boundary links onto one transceiver pair (a
  // single Bittide link). The transceiver/wire is EXTERNAL (the Clash side carries
  // `seam_<edge>_tx` -> link -> neighbour `seam_<edge>_rx`); only the bridge lives in
  // the chip, on the gated compute clock (compute == Bittide between gates, and a
  // stall lands on a vcycle boundary where the seam is empty). `seam_<edge>_extend`
  // (set by the chip's grid position) selects whether the edge is wired to a
  // neighbour; an unextended edge U-turns internally and its tx is idle. Per-chip
  // boot puts no traffic on the seam, so no boot bypass is needed.
  if (multiChip) {
    val mc = manticore.mc.get

    def mkSeam(
        name: String,
        nLinks: Int,
        setExtend: Bool => Unit,
        fwdOut: Vec[NoCBundle],
        bwdOut: Vec[NoCBundle],
        fwdIn: Vec[NoCBundle],
        bwdIn: Vec[NoCBundle]
    ): Unit = {
      val proto   = new TdmFrame(tX, tY, ManticoreFullISA, 2 * nLinks, cyclesPerSlot)
      val frameW  = proto.getWidth
      val wireLat = seamLatency - 2 * nLinks * cyclesPerSlot - 3 // -3 (was -2): +1 for the demux io.out pipeline register
      require(wireLat >= 1, s"seamLatency $seamLatency too small for $name seam (nLinks=$nLinks)")

      val extend = IO(Input(Bool())).suggestName(s"seam_${name}_extend")
      val tx     = IO(Output(UInt(frameW.W))).suggestName(s"seam_${name}_tx")
      val rx     = IO(Input(UInt(frameW.W))).suggestName(s"seam_${name}_rx")
      val ovf    = IO(Output(Bool())).suggestName(s"seam_${name}_overflow")

      setExtend(extend)

      val bridge = withClockAndReset(clock_distribution.io.compute_clock, reset_w) {
        Module(new TdmTorusBoundaryBridge(nLinks, cyclesPerSlot, wireLat, seamLatency, tX, tY, ManticoreFullISA))
      }
      bridge.io.fwdOut    := fwdOut
      bridge.io.bwdOut    := bwdOut
      fwdIn               := bridge.io.fwdIn
      bwdIn               := bridge.io.bwdIn
      bridge.io.connected := extend
      bridge.io.bypass    := false.B
      tx                  := bridge.io.tx.asUInt
      bridge.io.rx        := rx.asTypeOf(proto)
      ovf                 := bridge.io.demuxOverflow
    }

    mkSeam("east",  DimY, (b: Bool) => mc.east.extend  := b, mc.east.fwdOut,  mc.east.bwdOut,  mc.east.fwdIn,  mc.east.bwdIn)
    mkSeam("west",  DimY, (b: Bool) => mc.west.extend  := b, mc.west.fwdOut,  mc.west.bwdOut,  mc.west.fwdIn,  mc.west.bwdIn)
    mkSeam("north", DimX, (b: Bool) => mc.north.extend := b, mc.north.fwdOut, mc.north.bwdOut, mc.north.fwdIn, mc.north.bwdIn)
    mkSeam("south", DimX, (b: Bool) => mc.south.extend := b, mc.south.fwdOut, mc.south.bwdOut, mc.south.fwdIn, mc.south.bwdIn)
  }
}
