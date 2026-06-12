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
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.SimGmem

/** A two-IC simulation model of a single global torus split across two chips.
  *
  * The global program is compiled for a `gDimX x gDimY` torus. Physically:
  *
  *   - Chip A is a FULL [[ManticoreFlatArray]] (`aDimX x gDimY`, multi-chip mode):
  *     controller + bootloader + cache + the privileged/reporter core (0,0). Its
  *     Programmer addresses the GLOBAL torus, so its boot stream covers every core
  *     of BOTH chips; packets destined for chip B simply traverse the X seam.
  *
  *   - Chip B is a BARE [[ComputeArray]] (`bDimX x gDimY`, same multi-chip mode):
  *     compute-only, no controller/bootloader/cache. It shares chip A's gated
  *     compute clock and soft reset and receives its program purely over the seam.
  *     This is the "the second part is just the compute array" model the user asked
  *     for as a stand-in for a second IC.
  *
  * The two chips are joined by their X-seam boundary ports through a pair of
  * [[TdmTorusBoundaryBridge]]es (one per chip), connected by fixed-latency
  * `TdmFrame` wire pipes that MODEL THE TRANSCEIVERS. Every seam crossing —
  * register to register, in both boot-bypass and steady-state TDM modes — takes
  * EXACTLY `T` cycles (the value the compiler charged for these links via
  * latencies.csv). The compiler's boot-skew padding compensates the countdown
  * skew these slow seams introduce.
  *
  * Latency budget — the steady-state wire must be EXACTLY `T - period - 2`:
  *   - steady state : a frame waits up to `period-1` for its slot, crosses the
  *     wire, and the demux holds it so the total is `T`. The demux hold for an
  *     age-a packet is `T - 2 - wire - a` cycles of bank occupancy; with
  *     wire = T - period - 2 that is `period - a <= period`, so a same-link
  *     crossing one full TDM period later (the compiler's minimum spacing under
  *     --tdm-period) lands exactly on the release boundary and never collides.
  *     A SHALLOWER wire shifts that cycle into the demux hold and makes the
  *     occupancy exceed the period — back-to-back period-spaced crossings then
  *     overwrite the bank 1 cycle before release (release==1 overflows).
  *   - boot bypass  : banks drain immediately (full rate), so we pad the bypass
  *     wire to `bypassWireLatency = T - 3` to keep the crossing at `T` as well —
  *     one constant the compiler can model.
  */
class TwoChipTdmSimKernel(
    gDimX: Int = 8,
    gDimY: Int = 4,
    aDimX: Int = 4,
    enable_custom_alu: Boolean = true,
    // seam timing (defaults match data/latencies.csv: T = 25, period = 8)
    cyclesPerSlot: Int = 1,
    seamLatency: Int = 25,       // T: constant register-to-register seam latency
    execWireLatency: Int = 15,   // steady-state transceiver pipe depth (= T - period - 2)
    bypassWireLatency: Int = 22  // boot transceiver pipe depth (= T - 3)
) extends Module {

  clock.suggestName("ap_clk")
  reset.suggestName("ap_rst")

  val bDimX = gDimX - aDimX
  require(bDimX > 0, "chip B must hold at least one column")

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
    val kernel_registers = new KernelRegisters
    val kernel_ctrl      = new KernelControl
    val dmi              = new DirectMemoryInterface
    val dbg_axi_writes   = Output(UInt(32.W))
    val dbg_last_awaddr  = Output(UInt(64.W))
    val dbg_last_wdata   = Output(UInt(64.W))
    val dbg_seam_overflow = Output(Bool())     // steady-state mux OR demux overflow (any)
    val dbg_seam_dataloss = Output(Bool())     // steady-state DEMUX overflow only (real loss)
  }

  val io = IO(new KernelInterface)

  val clock_distribution = Module(new ClockDistribution())
  clock_distribution.io.root_clock := clock

  // ---------------- Chip A: full array, global-addressed ----------------
  val chipA =
    Module(new ManticoreFlatArray(aDimX, gDimY, debug_enable = false, enable_custom_alu,
      torusDimX = gDimX, torusDimY = gDimY))

  chipA.io.reset         := reset
  chipA.io.control_clock := clock_distribution.io.control_clock
  chipA.io.compute_clock := clock_distribution.io.compute_clock
  chipA.io.clock_stabled := clock_distribution.io.locked

  chipA.io.host_registers := io.kernel_registers.host
  io.kernel_registers.device := chipA.io.device_registers

  chipA.io.start    := io.kernel_ctrl.start
  io.kernel_ctrl.done := chipA.io.done
  io.kernel_ctrl.idle := chipA.io.idle

  clock_distribution.io.compute_clock_en := chipA.io.clock_active

  // Per-side model: chip A is the WEST chip of a 2-chip X chain. Only its EAST side
  // extends (one cable to chip B's west); A's west/north/south stay U-turned, making
  // A hold the global out-chain cols 0..aDimX/2-1 and back-chain cols gDimX-aDimX/2..
  val aMc = chipA.mc.get
  aMc.east.extend  := true.B
  aMc.west.extend  := false.B
  aMc.north.extend := false.B
  aMc.south.extend := false.B
  private def tieMcSide(s: chipA.SideIO): Unit = {
    s.fwdIn := VecInit(Seq.fill(s.fwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
    s.bwdIn := VecInit(Seq.fill(s.bwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
  }
  tieMcSide(aMc.west)
  tieMcSide(aMc.north)
  tieMcSide(aMc.south)

  // ---------------- Chip B: bare compute array (a second IC) ----------------
  // Same gated compute clock and the same soft reset chip A drives to its own array,
  // so both arrays step in lockstep and reset together.
  val chipB = withClockAndReset(
    clock = clock_distribution.io.compute_clock,
    reset = aMc.softResetOut
  ) {
    Module(new ComputeArray(bDimX, gDimY, debug_enable = false, enable_custom_alu,
      prefix_path = ".", n_hop = 1, torusDimX = gDimX, torusDimY = gDimY))
  }

  // chip B is the EAST chip: only its WEST side extends (the cable to A); its east
  // U-turn is the chain's far turnaround, north/south stay closed.
  chipB.io.extendWest  := true.B
  chipB.io.extendEast  := false.B
  chipB.io.extendNorth := false.B
  chipB.io.extendSouth := false.B
  private def tieArrSide(s: manticore.machine.core.SeamSide): Unit = {
    s.fwdIn := VecInit(Seq.fill(s.nLinks)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
    s.bwdIn := VecInit(Seq.fill(s.nLinks)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
  }
  tieArrSide(chipB.io.east)
  tieArrSide(chipB.io.north)
  tieArrSide(chipB.io.south)
  // chip B has no local bootloader: it is configured purely by seam-delivered packets.
  chipB.io.config_enable := false.B
  chipB.io.config_packet := NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)
  // chip B has no cache (no privileged/memory process is placed there)
  chipB.io.mem_access.done  := false.B
  chipB.io.mem_access.idle  := true.B
  chipB.io.mem_access.rdata := 0.U
  // chip B's exceptions/cycle/active are ignored: the reporter lives on chip A.

  // ---------------- TDM seam: one bridge per chip + transceiver pipes ----------------
  // The bridges and the wire pipes run on the GATED COMPUTE clock: the schedule's
  // constant-latency contract (T compute cycles per crossing) and the TDM slot/age/
  // release arithmetic are all in compute cycles, so the seam must freeze exactly when
  // the array freezes (e.g. trace-store clock stalls before a FLUSH). Running them on
  // the free-running control clock lets release counters tick while the array is
  // frozen, mis-spacing crossings and presenting 1-cycle output pulses the array never
  // samples (silently lost packets). This also matches the real system: lockstep BSP
  // chips must share clock-gating behaviour. Boot traffic is unaffected — the compute
  // clock is active while the bootloader streams (the NoC switches deliver the config
  // packets and live in the compute domain).
  val nLinks = gDimY
  def mkBridge() = withClockAndReset(clock_distribution.io.compute_clock, reset) {
    Module(new TdmTorusBoundaryBridge(nLinks, cyclesPerSlot, execWireLatency,
      seamLatency, gDimX, gDimY, ManticoreFullISA))
  }
  val bridgeA = mkBridge()
  val bridgeB = mkBridge()

  // Boot bypass while chip A streams config/countdown packets. config_enable alone is
  // the wrong window on BOTH edges:
  //   - it falls while the last countdown packets (back-to-back, full rate) are still
  //     crossing the seam — TDM-ing that tail would collide it;
  //   - it RISES during every exception halt (it covers all non-execution states), and
  //     a fixed stretch past its fall would overlap the resumed execution: flipping the
  //     deep/shallow transceiver pipes with exec frames near the seam compresses their
  //     spacing by the pipe-depth difference and collides period-spaced crossings.
  // So the tail is TRAFFIC-TRIGGERED: bypass holds until the seam has been idle for
  // tailHold cycles. After boot, the countdown tail keeps retriggering it until the
  // last frame has fully drained (frame at the mux at t clears the demux at t+23 <
  // t+tailHold). At an exception RESUME the seam has been quiet throughout the halt
  // (each vcycle drains all in-flight packets before it ends), so bypass drops with
  // config_enable, well before the first resumed SEND can reach the seam.
  val bootBypass = withClockAndReset(clock_distribution.io.compute_clock, reset) {
    val tailHold = bypassWireLatency + 2 // 24: frame at mux -> presented at demux + 1
    val tail     = RegInit(0.U(log2Ceil(tailHold + 1).W))
    val bypass   = aMc.configEnableOut || tail =/= 0.U
    val anyFrame = bridgeA.io.tx.valid || bridgeB.io.tx.valid
    when(bypass && anyFrame) {
      tail := tailHold.U
    }.elsewhen(tail =/= 0.U) {
      tail := tail - 1.U
    }
    bypass
  }
  bridgeA.io.bypass := bootBypass
  bridgeB.io.bypass := bootBypass
  bridgeA.io.connected := true.B
  bridgeB.io.connected := true.B

  // array-facing wiring: the cable is A.east <-> B.west, one bridge per end
  bridgeA.io.fwdOut := aMc.east.fwdOut
  bridgeA.io.bwdOut := aMc.east.bwdOut
  aMc.east.fwdIn := bridgeA.io.fwdIn
  aMc.east.bwdIn := bridgeA.io.bwdIn

  bridgeB.io.fwdOut := chipB.io.west.fwdOut
  bridgeB.io.bwdOut := chipB.io.west.bwdOut
  chipB.io.west.fwdIn := bridgeB.io.fwdIn
  chipB.io.west.bwdIn := bridgeB.io.bwdIn

  // transceiver model: a fixed-latency TdmFrame pipe each way. The pipe depth depends
  // on whether we are in boot bypass (banks drained immediately, so the wire absorbs
  // the rest of T) or steady state. Selecting per-frame keeps EVERY crossing == T.
  def wirePipe(src: TdmFrame): TdmFrame = {
    val deep    = ShiftRegister(src, bypassWireLatency)
    val shallow = ShiftRegister(src, execWireLatency)
    // boot frames travel the deep pipe; steady-state frames the shallow one. The mode
    // is stable across a frame's whole flight (bootBypass only falls after the last
    // boot packet has drained), so there is no in-flight reordering.
    val out = Wire(chiselTypeOf(src))
    out := Mux(bootBypass, deep, shallow)
    out
  }

  withClockAndReset(clock_distribution.io.compute_clock, reset) {
    bridgeB.io.rx := wirePipe(bridgeA.io.tx) // A -> B
    bridgeA.io.rx := wirePipe(bridgeB.io.tx) // B -> A
  }

  // Latch a STEADY-STATE seam overflow. We deliberately gate out the boot-bypass window:
  // during boot the bootloader's serialized stream fans out across the seam links and the
  // single-drain-per-cycle bypass can momentarily show a bank churn that does NOT lose data
  // (boot packets are idempotent SET deliveries and the schedule does not rely on seam
  // timing yet). A steady-state overflow, by contrast, means the compiler oversubscribed a
  // TDM link — a real scheduling bug.
  withClockAndReset(clock_distribution.io.control_clock, reset) {
    val ovf  = RegInit(false.B)
    val loss = RegInit(false.B)
    when(!bootBypass && (bridgeA.io.overflow || bridgeB.io.overflow)) { ovf := true.B }
    when(!bootBypass && (bridgeA.io.demuxOverflow || bridgeB.io.demuxOverflow)) { loss := true.B }
    io.dbg_seam_overflow := ovf
    io.dbg_seam_dataloss := loss
  }

  // ---------------- Chip A gmem (fixed-latency BRAM, as in the single-chip kernel) -----
  val gmem_backend = withClockAndReset(clock_distribution.io.control_clock, reset) {
    Module(new GmemBramBackend(GmemBramBackend.addrBitsFor(1 << 20)))
  }
  val gmem = withClockAndReset(clock_distribution.io.control_clock, reset) {
    Module(new SimGmem(1 << 20))
  }
  gmem_backend.io.front <> chipA.io.memory_backend
  gmem.io.bram <> gmem_backend.io.bram
  gmem.io.dmi.addr  := io.dmi.addr
  gmem.io.dmi.wdata := io.dmi.wdata
  gmem.io.dmi.wen   := io.dmi.wen
  io.dmi.rdata      := gmem.io.dmi.rdata

  // cache + AXI model are gone; debug ports kept for tester compatibility
  io.dbg_axi_writes  := 0.U
  io.dbg_last_awaddr := 0.U
  io.dbg_last_wdata  := 0.U
}
