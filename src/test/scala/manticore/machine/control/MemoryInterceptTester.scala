package manticore.machine.control

import chisel3._
import chiseltest._
import manticore.machine.core.MemoryIntercept
import manticore.machine.memory.CacheCommand
import manticore.machine.memory.CacheConfig
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.SimGmem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit test of the fixed-latency gmem path (MemoryIntercept +
  * GmemBramBackend + SimGmem) that replaced the cache + clock-kill machinery.
  *
  * The core-visible contract (unchanged from the kill/revive era):
  *   - `gmem.start` pulses at core cycle n; the address/wdata settle at the
  *     core's pins one cycle later (n+1) and hold;
  *   - read data is valid at the core's rdata pins during cycle n+3
  *     ("an SRAM with 2-cycle read latency", counted from the settled pins);
  *   - requests may issue back-to-back (one per cycle);
  *   - `pending` covers the request until commitment (drain guard for
  *     exceptions).
  *
  * Single clock: control == compute, i.e. the array is executing (the only
  * regime in which the core issues gmem accesses).
  */
class MemoryInterceptTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  private val memWords = 1 << 12

  class Harness extends Module {
    val io = IO(new Bundle {
      val start   = Input(Bool())
      val cmd     = Input(CacheCommand.Type())
      val addr    = Input(UInt(CacheConfig.UsedAddressBits.W))
      val wdata   = Input(UInt(16.W))
      val rdata   = Output(UInt(16.W))
      val pending = Output(Bool())

      val dmi_addr  = Input(UInt(64.W))
      val dmi_wdata = Input(UInt(16.W))
      val dmi_wen   = Input(Bool())
      val dmi_rdata = Output(UInt(16.W))
    })

    val intercept = Module(new MemoryIntercept)
    val backend   = Module(new GmemBramBackend(GmemBramBackend.addrBitsFor(memWords)))
    val gmem      = Module(new SimGmem(memWords))

    intercept.io.core.start := io.start
    intercept.io.core.cmd   := io.cmd
    intercept.io.core.addr  := io.addr
    intercept.io.core.wdata := io.wdata
    io.rdata   := intercept.io.core.rdata
    io.pending := intercept.io.pending

    intercept.io.config_enable := false.B
    intercept.io.cache_flush   := false.B
    intercept.io.cache_reset   := false.B
    intercept.io.core_clock    := clock

    intercept.io.boot.start := false.B
    intercept.io.boot.cmd   := CacheCommand.Read
    intercept.io.boot.addr  := 0.U
    intercept.io.boot.wdata := 0.U

    backend.io.front <> intercept.io.cache
    gmem.io.bram <> backend.io.bram

    gmem.io.dmi.addr  := io.dmi_addr
    gmem.io.dmi.wdata := io.dmi_wdata
    gmem.io.dmi.wen   := io.dmi_wen
    io.dmi_rdata      := gmem.io.dmi.rdata
  }

  private def dmiWrite(dut: Harness, addr: Int, value: Int): Unit = {
    dut.io.dmi_addr.poke(addr.U)
    dut.io.dmi_wdata.poke(value.U)
    dut.io.dmi_wen.poke(true.B)
    dut.clock.step()
    dut.io.dmi_wen.poke(false.B)
  }

  private def dmiRead(dut: Harness, addr: Int): Int = {
    dut.io.dmi_addr.poke(addr.U)
    dut.clock.step()
    dut.io.dmi_rdata.peek().litValue.toInt
  }

  behavior of "fixed-latency gmem path"

  it should "return read data at the core pins during cycle n+3" in {
    test(new Harness) { dut =>
      dut.io.start.poke(false.B)
      dut.io.dmi_wen.poke(false.B)
      dmiWrite(dut, 0x10, 0xbeef)
      dut.clock.step(2)

      // cycle n: start pulses, pins still hold a stale address
      dut.io.cmd.poke(CacheCommand.Read)
      dut.io.start.poke(true.B)
      dut.io.addr.poke(0.U)
      dut.clock.step()
      // cycle n+1: pins settle (start already low)
      dut.io.start.poke(false.B)
      dut.io.addr.poke(0x10.U)
      dut.clock.step()
      // cycle n+2: BRAM read in flight
      dut.clock.step()
      // cycle n+3: data at the core's rdata pins
      dut.io.rdata.expect(0xbeef.U)
    }
  }

  it should "commit a write captured from the settled pins" in {
    test(new Harness) { dut =>
      dut.io.start.poke(false.B)
      dut.io.dmi_wen.poke(false.B)
      dut.clock.step(2)

      dut.io.cmd.poke(CacheCommand.Write)
      dut.io.start.poke(true.B)
      dut.io.addr.poke(0.U) // stale pins at the start cycle
      dut.io.wdata.poke(0.U)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.addr.poke(0x20.U) // settled one cycle after start
      dut.io.wdata.poke(0xcafe.U)
      dut.clock.step()
      dut.clock.step()

      dmiRead(dut, 0x20) shouldBe 0xcafe
      // the stale address must NOT have been written
      dmiRead(dut, 0x0) shouldBe 0
    }
  }

  it should "accept back-to-back stores (one per cycle)" in {
    test(new Harness) { dut =>
      dut.io.start.poke(false.B)
      dut.io.dmi_wen.poke(false.B)
      dut.clock.step(2)

      dut.io.cmd.poke(CacheCommand.Write)
      // n: first start, stale pins
      dut.io.start.poke(true.B)
      dut.io.addr.poke(0.U)
      dut.io.wdata.poke(0.U)
      dut.clock.step()
      // n+1: second start; pins now carry the FIRST request
      dut.io.start.poke(true.B)
      dut.io.addr.poke(0x30.U)
      dut.io.wdata.poke(0x1111.U)
      dut.clock.step()
      // n+2: no new start; pins carry the SECOND request
      dut.io.start.poke(false.B)
      dut.io.addr.poke(0x31.U)
      dut.io.wdata.poke(0x2222.U)
      dut.clock.step()
      dut.clock.step()

      dmiRead(dut, 0x30) shouldBe 0x1111
      dmiRead(dut, 0x31) shouldBe 0x2222
    }
  }

  it should "hold pending over the in-flight window" in {
    test(new Harness) { dut =>
      dut.io.start.poke(false.B)
      dut.io.dmi_wen.poke(false.B)
      dut.clock.step(2)
      dut.io.pending.expect(false.B)

      dut.io.cmd.poke(CacheCommand.Write)
      dut.io.start.poke(true.B)
      dut.io.pending.expect(true.B) // request cycle
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.addr.poke(0x40.U)
      dut.io.wdata.poke(0x3333.U)
      dut.io.pending.expect(true.B) // capture cycle
      dut.clock.step()
      dut.io.pending.expect(true.B) // commit cycle
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }
}
