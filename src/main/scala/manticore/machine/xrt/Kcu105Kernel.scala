package manticore.machine.xrt

import chisel3._
import manticore.machine.ManticoreFullISA
import manticore.machine.core.ClockDistribution
import manticore.machine.core.ManticoreFlatArray
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.GmemHalfWordAdapter
import manticore.machine.memory.TrueDualPortBram

/** Plain-Vivado (KCU105) kernel with the fixed-latency BRAM global memory
  * (§"GmemBramBackend": no cache, no AXI master, no gmem clock-kill).
  *
  * The gmem BRAM lives INSIDE the kernel as a dual-clock TDP blackbox:
  *   - port A (32-bit, ap_clk) is exported at the top as a BRAM-controller
  *     style pin group (`gmem_*`). The block design connects an AXI BRAM
  *     controller to it, so the JTAG-to-AXI image-load/trace-readback flow
  *     (run.tcl) is unchanged: gmem still appears at AXI offset 0x0.
  *   - port B (control clock) serves the core/bootloader through
  *     GmemBramBackend + the halfword adapter.
  *
  * `gmemAddrBits` is in 16-bit words; the default 17 = 128Ki halfwords =
  * 256 KiB, matching build_kcu105.tcl's default mem_kib=256 (the BRAM
  * controller's address width must equal gmemAddrBits+1; regenerate the HDL
  * if mem_kib changes).
  */
class ManticoreFlatBramKernel(
    DimX: Int,
    DimY: Int,
    enable_custom_alu: Boolean = true,
    debug_enable: Boolean = false,
    freqMhz: Double = 200.0,
    n_hop: Int = 1,
    gmemAddrBits: Int = 17
) extends RawModule {

  val clock = IO(Input(Clock()))
  clock.suggestName("ap_clk")
  val reset_n = IO(Input(Bool()))
  reset_n.suggestName("ap_rst_n")

  val s_axi_control = IO(new AxiSlave.AxiSlaveCoreInterface())
  val interrupt     = IO(Output(Bool()))

  // gmem port A, BRAM-controller pin layout (byte-addressed). The KCU105
  // generator post-processes X_INTERFACE_INFO attributes onto these ports so
  // the block design can connect axi_bram_ctrl/BRAM_PORTA directly.
  private val gmemByteAddrBits = gmemAddrBits + 1
  val gmem_clk  = IO(Input(Clock()))
  val gmem_rst  = IO(Input(Bool()))
  val gmem_en   = IO(Input(Bool()))
  val gmem_we   = IO(Input(UInt(4.W)))
  val gmem_addr = IO(Input(UInt(gmemByteAddrBits.W)))
  val gmem_din  = IO(Input(UInt(32.W)))
  val gmem_dout = IO(Output(UInt(32.W)))

  val clock_distribution = Module(new ClockDistribution())
  val reset              = WireDefault(!clock_distribution.io.sync_rst_n)
  clock_distribution.io.root_rst_n := reset_n
  clock_distribution.io.root_clock := clock

  val s_axi_clock_crossing = Module(
    new AxiLiteClockConverter(s_axi_control.AWADDR.getWidth, s_axi_control.WDATA.getWidth)
  )
  s_axi_clock_crossing.m_axi_aclk   := clock_distribution.io.control_clock
  s_axi_clock_crossing.m_axi_resetn := clock_distribution.io.sync_rst_n
  s_axi_clock_crossing.s_axi_aclk   := clock
  s_axi_clock_crossing.s_axi_resetn := reset_n

  val slave =
    withClockAndReset(
      clock = clock_distribution.io.control_clock,
      reset = reset
    ) {
      Module(new AxiSlave(ManticoreFullISA))
    }

  slave.io.core <> s_axi_clock_crossing.m_axi
  s_axi_control <> s_axi_clock_crossing.s_axi

  interrupt := slave.io.control.interrupt

  val manticore =
    Module(new ManticoreFlatArray(DimX, DimY, debug_enable, enable_custom_alu = enable_custom_alu, n_hop = n_hop))

  manticore.io.reset         := reset
  manticore.io.control_clock := clock_distribution.io.control_clock
  manticore.io.compute_clock := clock_distribution.io.compute_clock
  manticore.io.clock_stabled := clock_distribution.io.locked

  clock_distribution.io.compute_clock_en := manticore.io.clock_active

  // ---- gmem: TDP BRAM, port A = host (ap_clk side), port B = core ----
  val gmem_backend = withClockAndReset(
    clock = clock_distribution.io.control_clock,
    reset = reset
  ) {
    Module(new GmemBramBackend(gmemAddrBits))
  }
  val gmem_adapter = withClockAndReset(
    clock = clock_distribution.io.control_clock,
    reset = reset
  ) {
    Module(new GmemHalfWordAdapter(gmemAddrBits))
  }
  // port A (host, 200 MHz ap_clk side) gets the BRAM-internal output register
  // (READ_LATENCY 2 towards the AXI BRAM controller)
  val gmem_bram = Module(new TrueDualPortBram(gmemAddrBits - 1, outRegA = true))

  gmem_backend.io.front <> manticore.io.memory_backend
  gmem_adapter.io.gmem <> gmem_backend.io.bram

  gmem_bram.io.clkb  := clock_distribution.io.control_clock
  gmem_bram.io.enb   := gmem_adapter.io.en
  gmem_bram.io.web   := gmem_adapter.io.we
  gmem_bram.io.addrb := gmem_adapter.io.addr
  gmem_bram.io.dinb  := gmem_adapter.io.din
  gmem_adapter.io.dout := gmem_bram.io.doutb

  gmem_bram.io.clka  := gmem_clk
  gmem_bram.io.ena   := gmem_en
  gmem_bram.io.wea   := gmem_we
  // byte address from the BRAM controller -> 32-bit word address
  gmem_bram.io.addra := gmem_addr(gmemByteAddrBits - 1, 2)
  gmem_bram.io.dina  := gmem_din
  gmem_dout          := gmem_bram.io.douta // OUT_REG_A inside the BRAM (latency 2)

  // no cache anymore: the cache performance counters read as zero
  slave.io.cache_regs.hit   := 0.U
  slave.io.cache_regs.miss  := 0.U
  slave.io.cache_regs.stall := 0.U

  manticore.io.host_registers := slave.io.host_regs
  slave.io.dev_regs           := manticore.io.device_registers

  manticore.io.start := slave.io.control.ap_start

  slave.io.control.ap_done  := manticore.io.done
  slave.io.control.ap_idle  := manticore.io.idle
  slave.io.control.ap_ready := manticore.io.done

}
