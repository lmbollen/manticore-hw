package manticore.machine.xrt

import chisel3._
import chisel3.util._
import manticore.machine.ManticoreFullISA
import manticore.machine.core.ClockDistribution
import manticore.machine.core.DeviceRegisters
import manticore.machine.core.HostRegisters
import manticore.machine.core.ManticoreFlatArray
import manticore.machine.core.NoCBundle
import manticore.machine.core.TdmFrame
import manticore.machine.core.TdmTorusBoundaryBridge
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.SimGmem

/** A two-IC simulation model where BOTH chips are FULL, independent instances of the
  * existing [[ManticoreFlatArray]] — the "each IC is just an instance of the existing
  * design" model (vs [[TwoChipTdmSimKernel]], where chip B is a bare ComputeArray booted
  * over the seam from chip A's Programmer).
  *
  * Each chip:
  *   - is a full ManticoreFlatArray (`aDimX|bDimX x gDimY`, multi-chip mode) with its own
  *     controller + bootloader + cache + gmem;
  *   - boots ONLY its own local cores from its own gmem (`perChipBoot = true`): the
  *     Programmer's boot grid is the chip's physical dims, so boot is purely intra-chip
  *     (no boot-over-seam). Each chip's image is loaded through its own DMI port.
  *
  * The chips are joined by their X-seam (A.east <-> B.west) through a pair of
  * [[TdmTorusBoundaryBridge]]es and fixed-latency `TdmFrame` wire pipes (the transceiver
  * model), exactly as in [[TwoChipTdmSimKernel]] — every crossing takes `T` cycles.
  *
  * Milestone P3-skeleton: the chips still share ONE gated compute clock (gated by the
  * AND of both `clock_active`s — the global-OR stall shim) and the seam runs on that
  * gated clock. Independent per-chip clock domains + the ungated NoC + the distributed
  * stall/resume wave come later; this kernel exists to validate that two full instances
  * connected by a seam BOOTSTRAP (each boots its own cores) and run.
  */
class TwoFullChipSimKernel(
    gDimX: Int = 8,
    gDimY: Int = 4,
    aDimX: Int = 4,
    enable_custom_alu: Boolean = true,
    // seam timing (defaults match data/latencies.csv: T = 25, period = 8)
    cyclesPerSlot: Int = 1,
    seamLatency: Int = 25,      // T: constant register-to-register seam latency
    execWireLatency: Int = 15,  // steady-state transceiver pipe depth (= T - period - 2)
    bypassWireLatency: Int = 22 // boot transceiver pipe depth (= T - 3)
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
    // shared run control (both chips get the same commands and step in lockstep)
    val kernel_registers  = new KernelRegisters // host broadcast to both; device = chip A (reporter)
    val kernel_ctrl       = new KernelControl   // start broadcast; done/idle = AND of both
    val dmi_a             = new DirectMemoryInterface // chip A image load + trace readback
    val dmi_b             = new DirectMemoryInterface // chip B image load
    val dbg_seam_overflow = Output(Bool())
    val dbg_seam_dataloss = Output(Bool())
  }
  val io = IO(new KernelInterface)

  val clock_distribution = Module(new ClockDistribution())
  clock_distribution.io.root_clock := clock

  // ---------------- both chips: full arrays, per-chip boot ----------------
  def mkChip(localDimX: Int) =
    Module(new ManticoreFlatArray(localDimX, gDimY, debug_enable = false, enable_custom_alu,
      torusDimX = gDimX, torusDimY = gDimY, perChipBoot = true))

  val chipA = mkChip(aDimX)
  val chipB = mkChip(bDimX)

  for (c <- Seq(chipA, chipB)) {
    c.io.reset         := reset
    c.io.control_clock := clock_distribution.io.control_clock
    c.io.compute_clock := clock_distribution.io.compute_clock
    c.io.clock_stabled := clock_distribution.io.locked
    c.io.host_registers := io.kernel_registers.host
    c.io.start          := io.kernel_ctrl.start
  }

  // device registers come from chip A (the reporter lives there for the A2/loop_multi test)
  io.kernel_registers.device := chipA.io.device_registers
  // global-OR stall shim: the shared compute clock runs only while BOTH chips want to run
  clock_distribution.io.compute_clock_en := chipA.io.clock_active && chipB.io.clock_active
  io.kernel_ctrl.done := chipA.io.done && chipB.io.done
  io.kernel_ctrl.idle := chipA.io.idle && chipB.io.idle

  // ---------------- seam topology (A is the west chip, B the east) ----------------
  val aMc = chipA.mc.get
  val bMc = chipB.mc.get
  aMc.east.extend  := true.B
  aMc.west.extend  := false.B
  aMc.north.extend := false.B
  aMc.south.extend := false.B
  bMc.west.extend  := true.B
  bMc.east.extend  := false.B
  bMc.north.extend := false.B
  bMc.south.extend := false.B

  def tieIn(fwdIn: Vec[NoCBundle], bwdIn: Vec[NoCBundle]): Unit = {
    fwdIn := VecInit(Seq.fill(fwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
    bwdIn := VecInit(Seq.fill(bwdIn.length)(NoCBundle.empty(gDimX, gDimY, ManticoreFullISA)))
  }
  tieIn(aMc.west.fwdIn, aMc.west.bwdIn)
  tieIn(aMc.north.fwdIn, aMc.north.bwdIn)
  tieIn(aMc.south.fwdIn, aMc.south.bwdIn)
  tieIn(bMc.east.fwdIn, bMc.east.bwdIn)
  tieIn(bMc.north.fwdIn, bMc.north.bwdIn)
  tieIn(bMc.south.fwdIn, bMc.south.bwdIn)

  // ---------------- TDM seam: one bridge per chip + transceiver pipes ----------------
  // On the gated compute clock (as in TwoChipTdmSimKernel — the constant-latency contract
  // is in compute cycles). Per-chip boot puts NO traffic on the seam during boot, so the
  // bypass window only needs to cover any boot-window churn; we keep the traffic-triggered
  // tail from the two-chip kernel for robustness, armed while EITHER chip is in boot.
  val nLinks = gDimY
  def mkBridge() = withClockAndReset(clock_distribution.io.compute_clock, reset) {
    Module(new TdmTorusBoundaryBridge(nLinks, cyclesPerSlot, execWireLatency,
      seamLatency, gDimX, gDimY, ManticoreFullISA))
  }
  val bridgeA = mkBridge()
  val bridgeB = mkBridge()

  val bootBypass = withClockAndReset(clock_distribution.io.compute_clock, reset) {
    val tailHold = bypassWireLatency + 2
    val tail     = RegInit(0.U(log2Ceil(tailHold + 1).W))
    val bypass   = aMc.configEnableOut || bMc.configEnableOut || tail =/= 0.U
    val anyFrame = bridgeA.io.tx.valid || bridgeB.io.tx.valid
    when(bypass && anyFrame) { tail := tailHold.U }.elsewhen(tail =/= 0.U) { tail := tail - 1.U }
    bypass
  }
  bridgeA.io.bypass := bootBypass
  bridgeB.io.bypass := bootBypass
  bridgeA.io.connected := true.B
  bridgeB.io.connected := true.B

  bridgeA.io.fwdOut := aMc.east.fwdOut
  bridgeA.io.bwdOut := aMc.east.bwdOut
  aMc.east.fwdIn := bridgeA.io.fwdIn
  aMc.east.bwdIn := bridgeA.io.bwdIn
  bridgeB.io.fwdOut := bMc.west.fwdOut
  bridgeB.io.bwdOut := bMc.west.bwdOut
  bMc.west.fwdIn := bridgeB.io.fwdIn
  bMc.west.bwdIn := bridgeB.io.bwdIn

  def wirePipe(src: TdmFrame): TdmFrame = {
    val deep    = ShiftRegister(src, bypassWireLatency)
    val shallow = ShiftRegister(src, execWireLatency)
    val out = Wire(chiselTypeOf(src))
    out := Mux(bootBypass, deep, shallow)
    out
  }
  withClockAndReset(clock_distribution.io.compute_clock, reset) {
    bridgeB.io.rx := wirePipe(bridgeA.io.tx) // A -> B
    bridgeA.io.rx := wirePipe(bridgeB.io.tx) // B -> A
  }

  withClockAndReset(clock_distribution.io.control_clock, reset) {
    val ovf  = RegInit(false.B)
    val loss = RegInit(false.B)
    when(!bootBypass && (bridgeA.io.overflow || bridgeB.io.overflow)) { ovf := true.B }
    when(!bootBypass && (bridgeA.io.demuxOverflow || bridgeB.io.demuxOverflow)) { loss := true.B }
    io.dbg_seam_overflow := ovf
    io.dbg_seam_dataloss := loss
  }

  // ---------------- per-chip gmem (fixed-latency BRAM) ----------------
  def attachGmem(chip: ManticoreFlatArray, dmi: DirectMemoryInterface): Unit = {
    val backend = withClockAndReset(clock_distribution.io.control_clock, reset) {
      Module(new GmemBramBackend(GmemBramBackend.addrBitsFor(1 << 20)))
    }
    val gmem = withClockAndReset(clock_distribution.io.control_clock, reset) {
      Module(new SimGmem(1 << 20))
    }
    backend.io.front <> chip.io.memory_backend
    gmem.io.bram <> backend.io.bram
    gmem.io.dmi.addr  := dmi.addr
    gmem.io.dmi.wdata := dmi.wdata
    gmem.io.dmi.wen   := dmi.wen
    dmi.rdata         := gmem.io.dmi.rdata
  }
  attachGmem(chipA, io.dmi_a)
  attachGmem(chipB, io.dmi_b)
}
