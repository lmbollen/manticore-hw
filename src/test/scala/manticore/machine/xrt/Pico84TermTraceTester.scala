package manticore.machine.xrt

import chisel3._
import chiseltest._
import manticore.machine.TestsCommon.RequiresVerilator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** Diagnostic (SIG-divergence hunt): run loop_multi on a single-chip 8x4 with
  * debug_enable so ManticoreFlatArray prints every NoC terminal delivery
  * ([TERM x y] cyc reg data) and every core-activation edge ([ACT x y] cyc).
  * Runs main only to the FIRST FLUSH (the first $display already shows the
  * frozen sig0/sig1), then reads back the SIG words. The stdout trace is
  * correlated offline against the compiler's transactions.csv.
  */
class Pico84TermTraceTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val dir      = sys.props.getOrElse("pico84.dir", "/tmp/pico84_plain")
  val userBase = 16384

  def readWords(p: String): Array[Int] = {
    val bytes = Files.readAllBytes(Paths.get(p))
    Array.tabulate(bytes.length / 2) { i =>
      (bytes(2 * i) & 0xff) | ((bytes(2 * i + 1) & 0xff) << 8)
    }
  }
  def cmdWord(cmd: Int, timeout: Long): BigInt =
    (BigInt(1) << 63) | (BigInt(cmd) << 56) | BigInt(timeout)

  behavior of "picorv32 loop_multi single-chip 8x4 with terminal-delivery trace"

  it should "dump the delivery trace up to the first FLUSH" taggedAs RequiresVerilator in {
    val init0 = readWords(s"$dir/init_0/exec.bin")
    val init1 = readWords(s"$dir/init_1/exec.bin")
    val main  = readWords(s"$dir/main/exec.bin")
    val base0 = userBase
    val base1 = base0 + init0.length
    val baseM = base1 + init1.length
    val image = Array.fill(baseM + main.length)(0)
    Array.copy(init0, 0, image, base0, init0.length)
    Array.copy(init1, 0, image, base1, init1.length)
    Array.copy(main, 0, image, baseM, main.length)

    test(new ManticoreFlatSimKernel(DimX = 8, DimY = 4, debug_enable = true, enable_custom_alu = false))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(0)
        dut.io.kernel_ctrl.start.poke(false.B)
        dut.io.dmi.wen.poke(false.B)
        dut.io.dmi.locked.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step(16)
        dut.reset.poke(false.B)
        dut.clock.step(4)

        var i = 0
        while (i < image.length) {
          dut.io.dmi.wen.poke(true.B)
          dut.io.dmi.addr.poke(i.U)
          dut.io.dmi.wdata.poke(image(i).U)
          dut.clock.step()
          i += 1
        }
        dut.io.dmi.wen.poke(false.B)
        dut.clock.step()

        def rdMem(a: Int): Int = {
          dut.io.dmi.addr.poke(a.U)
          dut.clock.step()
          dut.io.dmi.rdata.peek().litValue.toInt
        }

        def run(base: Int, cmdword: BigInt): (Int, Long) = {
          dut.io.kernel_registers.host.schedule_config.poke(cmdword.U)
          dut.io.kernel_registers.host.global_memory_instruction_base.poke(base.U)
          dut.io.kernel_registers.host.trace_dump_base.poke(0.U)
          dut.io.kernel_ctrl.start.poke(true.B)
          dut.clock.step()
          dut.io.kernel_ctrl.start.poke(false.B)
          var guard = 0
          while (dut.io.kernel_ctrl.idle.peekBoolean() && guard < 500000) { dut.clock.step(); guard += 1 }
          while (!dut.io.kernel_ctrl.done.peekBoolean() && guard < 4000000) { dut.clock.step(); guard += 1 }
          val eid = dut.io.kernel_registers.device.exception_id.peek().litValue.toInt
          val vc  = dut.io.kernel_registers.device.virtual_cycles.peek().litValue.toLong
          dut.clock.step()
          (eid, vc)
        }

        val to = 500000L
        for ((b, n) <- Seq((base0, "init_0"), (base1, "init_1"))) {
          val (eid, _) = run(b, cmdWord(0, to))
          assert(!(eid > 0xffff), s"$n timed out")
        }

        println(s"[TRACE] main phase starts")
        val (eid, vc) = run(baseM, cmdWord(0, to))
        val raw = (0 until 8).map(rdMem)
        println(s"[TRACE] pre-flush gmem[0..7] = $raw ; image sanity gmem[$base0..+3] = ${(base0 until base0 + 4).map(rdMem)} (expect ${init0.take(4).toSeq})")
        // drain the trace cache so the SIG words are readable from gmem
        run(baseM, BigInt(2) << 56)
        val post = (0 until 8).map(rdMem)
        println(s"[TRACE] post-flush gmem[0..7] = $post")
        val w0 = rdMem(0) | (rdMem(1) << 16)
        val w1 = rdMem(2) | (rdMem(3) << 16)
        val w2 = rdMem(4) | (rdMem(5) << 16)
        println(s"[TRACE] first stop: eid=$eid vc=$vc SIG=($w0, $w1, $w2)")
        info(s"first stop: eid=$eid vc=$vc SIG=($w0, $w1, $w2)")
      }
  }
}
