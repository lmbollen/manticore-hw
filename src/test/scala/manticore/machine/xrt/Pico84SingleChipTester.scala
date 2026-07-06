package manticore.machine.xrt

import chisel3._
import chiseltest._
import manticore.machine.TestsCommon.RequiresVerilator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** Diagnostic: the picorv32 loop_multi benchmark on a SINGLE-chip 8x4 array (no seams,
  * plain compile without --hop-latencies). Isolates the multi-chip SIG-value anomaly:
  * if sig0/sig1 are wrong here too, the divergence is core-array/RTL-level, not a seam
  * transport problem. Interpreter golden: 1025 vcycles, SIG (20,20,20) (96,96,96)
  * (193,193,193) (225,225,225), FINISH.
  */
class Pico84SingleChipTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val dir      = sys.props.getOrElse("pico84.dir", "/tmp/pico84_plain")
  val userBase = 16384
  val FLUSH    = Set(0, 2) // eid 0 = SIG display, eid 2 = CHK display (manifest)
  val FINISH   = Set(1)

  def readWords(p: String): Array[Int] = {
    val bytes = Files.readAllBytes(Paths.get(p))
    Array.tabulate(bytes.length / 2) { i =>
      (bytes(2 * i) & 0xff) | ((bytes(2 * i + 1) & 0xff) << 8)
    }
  }
  def cmdWord(cmd: Int, timeout: Long): BigInt =
    (BigInt(1) << 63) | (BigInt(cmd) << 56) | BigInt(timeout)

  behavior of "picorv32 loop_multi on a single-chip 8x4 (no seams)"

  it should "match the interpreter golden including the SIG values" taggedAs RequiresVerilator in {
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

    test(new ManticoreFlatSimKernel(DimX = 8, DimY = 4, enable_custom_alu = false))
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

        // Two $display statements (manifest): eid 0 = "SIG %d %d %d" at trace
        // words [0,1][2,3][4,5]; eid 2 = "CHK %d %d %d" at words [6][7][8,9]
        // (8-bit counter, 16-bit LFSR, 32-bit accumulator — three distinct
        // reporter-local states, verifying the multi-statement/multi-state
        // $display mechanism with globally-unique trace offsets). FINISH = 1.
        val sigs     = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
        val chks     = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
        val cmdFlush = BigInt(2) << 56
        var (eid, vc) = run(baseM, cmdWord(0, to))
        var flushes = 0
        while (FLUSH.contains(eid) && flushes < 20) {
          run(baseM, cmdFlush)
          if (eid == 0) {
            val w0 = rdMem(0) | (rdMem(1) << 16)
            val w1 = rdMem(2) | (rdMem(3) << 16)
            val w2 = rdMem(4) | (rdMem(5) << 16)
            sigs += ((w0, w1, w2))
            info(f"  flush#$flushes%-2d SIG $w0 $w1 $w2")
          } else {
            val cnt  = rdMem(6)
            val lfsr = rdMem(7)
            val acc  = rdMem(8) | (rdMem(9) << 16)
            chks += ((cnt, lfsr, acc))
            info(f"  flush#$flushes%-2d CHK $cnt $lfsr $acc")
          }
          val r = run(baseM, cmdWord(1, to))
          eid = r._1; vc = r._2
          flushes += 1
        }
        info(s"MAIN terminated: eid=$eid vcycles=$vc after $flushes flushes (${sigs.length} SIG + ${chks.length} CHK)")
        assert(FINISH.contains(eid) && vc == 1025 && sigs.length == 4 && chks.length == 4,
          s"structural mismatch: eid=$eid vc=$vc flushes=$flushes sig=${sigs.length} chk=${chks.length}")
        val golden = Seq((20, 20, 20), (96, 96, 96), (193, 193, 193), (225, 225, 225))
        // Interpreter golden for the CHK states (masm interpret, 2026-07-06; the
        // display reads the NEXT-value wires, so cnt = 3*128 mod 256 = 128 at
        // every display — the counter aliasing with the 256-cycle display
        // period is deliberate: it detects a display firing on the wrong
        // cycle. LFSR/accumulator vary per display.)
        val goldenChk = Seq((128, 2698, 16234), (128, 36466, 49359), (128, 9339, 83026), (128, 11707, 115475))
        // Both statements' full sequences are asserted. Historic note: sig0/sig1
        // used to read (1, 0) and (with the reworked scheduler) sig2 read 0 —
        // root-caused to two RTL bugs, both fixed: (a) the Switch's south-turn
        // branches clobbered terminal_reg, silently masking same-cycle terminal
        // deliveries (33/1394 sends per vcycle lost); (b) MemoryIntercept sampled
        // gmem addr/wdata one cycle after start (a gmem clock-kill-era contract),
        // corrupting every display GST burst.
        assert(sigs.toSeq == golden, s"SIG values $sigs != golden $golden")
        assert(chks.toSeq == goldenChk, s"CHK values $chks != golden $goldenChk")
        info(s"single-chip 8x4: EXACT value match incl. all SIG and CHK values")
      }
  }
}
