package manticore.machine.memory

import chisel3._
import chisel3.util.Cat
import chisel3.util.HasBlackBoxResource
import chisel3.util.RegEnable
import chisel3.util.log2Ceil

/** Master-side view of a plain BRAM port with a fixed 1-cycle read latency:
  * `addr`/`din`/`wen` are captured on the rising edge when `en` is high and
  * `dout` holds the read word one cycle later (and keeps holding it until the
  * next read).
  */
class GmemBramPort(val addrBits: Int, val dataBits: Int = 16) extends Bundle {
  val en   = Output(Bool())
  val wen  = Output(Bool())
  val addr = Output(UInt(addrBits.W))
  val din  = Output(UInt(dataBits.W))
  val dout = Input(UInt(dataBits.W))
}

/** Fixed-latency global-memory backend over an on-chip BRAM, replacing the
  * cache + AXI (+ DRAM) subsystem — and with it the gmem clock-kill: the core
  * was always designed against a "2-cycle SRAM" contract (see MemoryIntercept),
  * and this backend makes global memory physically be that, so the compute
  * clock never has to freeze to hide memory latency.
  *
  * Presents the cache front interface. Every request is accepted, one per
  * cycle, fully pipelined (back-to-back trace GSTs issue a store every cycle):
  *
  *   - Read:  BRAM enable in the start cycle; `rdata` (= BRAM dout) is valid
  *     one cycle later, together with the `done` pulse, and holds until the
  *     next read.
  *   - Write: captured in the start cycle, `done` one cycle later.
  *   - Flush/Reset: no-ops (there is no backing store behind the BRAM; the
  *     host reads gmem directly), acknowledged with `done` one cycle later so
  *     Management's sCacheResetWait/sCacheFlushWait states proceed unchanged.
  *
  * The BRAM itself lives outside (sim kernels: an internal SyncReadMem with
  * the host DMI on the second port; KCU105: the block-design BRAM with the
  * JTAG-reachable AXI BRAM controller on the second port).
  */
class GmemBramBackend(addrBits: Int) extends Module {
  val io = IO(new Bundle {
    val front = CacheConfig.frontInterface()
    val bram  = new GmemBramPort(addrBits)
  })

  private val isMemAccess =
    io.front.cmd === CacheCommand.Read || io.front.cmd === CacheCommand.Write

  io.bram.en   := io.front.start && isMemAccess
  io.bram.wen  := io.front.start && io.front.cmd === CacheCommand.Write
  io.bram.addr := io.front.addr(addrBits - 1, 0)
  io.bram.din  := io.front.wdata

  io.front.rdata := io.bram.dout
  io.front.done  := RegNext(io.front.start, false.B)
  io.front.idle  := !io.front.start && !RegNext(io.front.start, false.B)
}

object GmemBramBackend {
  /** address bits needed for a gmem of `words` 16-bit words */
  def addrBitsFor(words: Int): Int = log2Ceil(words)
}

/** Chisel wrapper of verilog/TrueDualPortBram.v: true-dual-port, dual-clock,
  * byte-WE, 32-bit, read-first block RAM with 1-cycle read latency and dout
  * hold. `addrWidth` is in 32-bit words.
  */
class TrueDualPortBram(addrWidth: Int, outRegA: Boolean = false)
    extends BlackBox(Map("ADDR_WIDTH" -> addrWidth, "OUT_REG_A" -> (if (outRegA) 1 else 0)))
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clka  = Input(Clock())
    val ena   = Input(Bool())
    val wea   = Input(UInt(4.W))
    val addra = Input(UInt(addrWidth.W))
    val dina  = Input(UInt(32.W))
    val douta = Output(UInt(32.W))

    val clkb  = Input(Clock())
    val enb   = Input(Bool())
    val web   = Input(UInt(4.W))
    val addrb = Input(UInt(addrWidth.W))
    val dinb  = Input(UInt(32.W))
    val doutb = Output(UInt(32.W))
  })
  addResource("/verilog/TrueDualPortBram.v")
}

/** Adapts the 16-bit `GmemBramPort` (addr in 16-bit words) onto one 32-bit
  * port of the TDP BRAM: half-word writes via byte enables, reads muxed by a
  * captured-and-held halfword select (held so a frozen core can sample the
  * result after resuming).
  */
class GmemHalfWordAdapter(addrBits: Int) extends Module {
  val io = IO(new Bundle {
    val gmem = Flipped(new GmemBramPort(addrBits)) // slave side: from GmemBramBackend
    val en   = Output(Bool())
    val we   = Output(UInt(4.W))
    val addr = Output(UInt((addrBits - 1).W)) // 32-bit word address
    val din  = Output(UInt(32.W))
    val dout = Input(UInt(32.W))
  })
  io.en   := io.gmem.en
  io.we   := Mux(
    io.gmem.wen,
    Mux(io.gmem.addr(0), "b1100".U(4.W), "b0011".U(4.W)),
    0.U(4.W)
  )
  io.addr := io.gmem.addr(addrBits - 1, 1)
  io.din  := Cat(io.gmem.din, io.gmem.din)

  private val hiSel = RegEnable(io.gmem.addr(0), false.B, io.gmem.en)
  io.gmem.dout := Mux(hiSel, io.dout(31, 16), io.dout(15, 0))
}

/** Host-side direct-memory port of the gmem (same contract as the old
  * axislave_vip sim port: writes commit on the edge, reads are registered with
  * a 1-cycle latency, updated every cycle).
  */
class GmemHostPort extends Bundle {
  val rdata = Output(UInt(16.W))
  val wdata = Input(UInt(16.W))
  val wen   = Input(Bool())
  val addr  = Input(UInt(64.W))
}

/** The gmem storage for the sim kernels (and later the bittide chip): a
  * dual-port memory with the `GmemBramBackend` on port A and the host DMI on
  * port B.
  *
  * Port A honors the BRAM hold contract: `dout` keeps the last read word while
  * `en` is low — required because the core-domain result register may sample
  * it arbitrarily late if an exception freezes the compute clock with a read
  * in flight (real BRAM primitives hold their output naturally; SyncReadMem's
  * enable-gated read port is undefined when disabled, hence the explicit
  * hold mux).
  */
class SimGmem(words: Int) extends Module {
  val addrBits = log2Ceil(words)
  val io = IO(new Bundle {
    val bram = Flipped(new GmemBramPort(addrBits))
    val dmi  = new GmemHostPort
  })

  val mem = SyncReadMem(words, UInt(16.W))

  // port A: backend (1-cycle reads + hold)
  private val rd      = mem.read(io.bram.addr, io.bram.en)
  private val rdValid = RegNext(io.bram.en && !io.bram.wen, false.B)
  private val rdHeld  = RegEnable(rd, 0.U(16.W), rdValid)
  io.bram.dout := Mux(rdValid, rd, rdHeld)
  when(io.bram.en && io.bram.wen) {
    mem.write(io.bram.addr, io.bram.din)
  }

  // port B: host DMI (registered read every cycle, like axislave_vip)
  private val dmiAddr = io.dmi.addr(addrBits - 1, 0)
  io.dmi.rdata := mem.read(dmiAddr)
  when(io.dmi.wen) {
    mem.write(dmiAddr, io.dmi.wdata)
  }
}
