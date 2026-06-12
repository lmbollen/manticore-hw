package manticore.machine.xrt

import chisel3._
import chisel3.util.HasBlackBoxResource
import manticore.machine.core.DeviceRegisters
import manticore.machine.core.HostRegisters
import manticore.machine.core.ManticoreFlatArray
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
  *   - the DMI port is the host window into the chip-local gmem BRAM (program
  *     image load + trace readback), same contract as the sim kernels:
  *     writes commit on the edge, reads are registered (1 cycle), Bittide
  *     clock domain.
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
    gmemAddrBits: Int = 16 // 64Ki halfwords = 128 KiB
) extends RawModule {

  val clk = IO(Input(Clock()))
  val rst = IO(Input(Bool())) // active-high, Bittide domain

  val host_regs    = IO(Input(new HostRegisters))
  val device_regs  = IO(Output(new DeviceRegisters))
  val ctrl_start   = IO(Input(Bool()))
  val ctrl_done    = IO(Output(Bool()))
  val ctrl_idle    = IO(Output(Bool()))
  val clock_active = IO(Output(Bool())) // diagnostics: compute clock enabled

  val dmi_addr  = IO(Input(UInt(64.W)))
  val dmi_wdata = IO(Input(UInt(16.W)))
  val dmi_wen   = IO(Input(Bool()))
  val dmi_rdata = IO(Output(UInt(16.W)))

  val clock_distribution = Module(new ClockDistributionNoMmcm)
  clock_distribution.io.root_clock := clk
  clock_distribution.io.root_rst_n := !rst
  private val reset_w              = WireDefault(!clock_distribution.io.sync_rst_n)
  private val control_clock        = clock_distribution.io.control_clock

  val manticore = Module(
    new ManticoreFlatArray(DimX, DimY, debug_enable, enable_custom_alu = enable_custom_alu, n_hop = n_hop)
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

  // host DMI on port A (same timing as the sim kernels' DMI / axislave_vip:
  // read every cycle, registered once; halfword select held alongside)
  gmem_bram.io.clka  := control_clock
  gmem_bram.io.ena   := true.B
  gmem_bram.io.wea   := Mux(dmi_wen, Mux(dmi_addr(0), "b1100".U(4.W), "b0011".U(4.W)), 0.U(4.W))
  gmem_bram.io.addra := dmi_addr(gmemAddrBits - 1, 1)
  gmem_bram.io.dina  := chisel3.util.Cat(dmi_wdata, dmi_wdata)
  dmi_rdata := withClockAndReset(control_clock, reset_w) {
    Mux(RegNext(dmi_addr(0), false.B), gmem_bram.io.douta(31, 16), gmem_bram.io.douta(15, 0))
  }
}
