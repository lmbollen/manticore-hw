package manticore.machine.xrt

import chisel3._
import chiseltest._
import manticore.machine.TestsCommon.RequiresVerilator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** On-path validation of the distributed scheduled stall wave (P1).
  *
  * Runs a compute-only program (count to 16, then FINISH; NO $display) through the
  * full ManticoreFlatSimKernel with `stallWave = true` in Verilator. In stall-wave
  * mode the application FINISH does NOT gate the compute clock immediately; instead
  * the privileged core's countdown heartbeat (seeded to stallMargin on the FINISH)
  * runs the array through the quiesce window and only the reserved STALL interrupt
  * (eid 0x7FFF) gates the clock. The application FINISH eid is still captured.
  *
  * Deferred-precise golden from `masm interpret --lower --stall-wave --stall-margin 4`:
  *   baseline (no stall) halts at 16 virtual cycles;
  *   stall-wave halts at 16 + margin(4) = 20 virtual cycles, eid = the app FINISH (0).
  *
  * Image (compile, inside the compiler's nix shell):
  *   masm --no-cf --stall-wave --stall-margin 4 -x 2 -y 2 -o /tmp/stallwave_out \
  *        /tmp/stallwave_prog.masm
  */
class StallWaveSimTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val dir            = sys.props.getOrElse("stallwave.dir", "/tmp/stallwave_out")
  val userBase       = 16384 // userGlobalMemoryBase (words), from the manifest "base"
  val CMD_START      = 0
  val goldenVcycles  = 20 // 16 base + stallMargin 4
  val goldenEid      = 0  // captured application FINISH eid (STALL eid 0x7FFF only gates)

  def readWords(p: String): Array[Int] = {
    val bytes = Files.readAllBytes(Paths.get(p))
    require(bytes.length % 2 == 0)
    Array.tabulate(bytes.length / 2) { i =>
      (bytes(2 * i) & 0xff) | ((bytes(2 * i + 1) & 0xff) << 8)
    }
  }

  def cmdWord(cmd: Int, timeout: Long): BigInt =
    (BigInt(1) << 63) | (BigInt(cmd) << 56) | BigInt(timeout)

  behavior of "ManticoreFlatSimKernel with stallWave"

  // The stall wave is armed per-run via schedule_config bit 55 (Management.STALL_ARM_BIT).
  // Boot/initializer phases (init_0/init_1) carry no STALL heartbeat, so they run UNARMED
  // (immediate gate-on-exception); only the main compute START is armed so the FINISH is
  // deferred through the quiesce window and the clock gates on the STALL wave.
  val STALL_ARM = BigInt(1) << 55

  it should "defer the FINISH through the quiesce window and gate on the STALL wave" taggedAs RequiresVerilator in {
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
    info(s"image words=${image.length} init0@$base0(${init0.length}) init1@$base1(${init1.length}) main@$baseM(${main.length})")

    test(new ManticoreFlatSimKernel(DimX = 2, DimY = 2, enable_custom_alu = false, stallWave = true))
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

        def run(base: Int, cmdword: BigInt): (Int, Long) = {
          dut.io.kernel_registers.host.schedule_config.poke(cmdword.U)
          dut.io.kernel_registers.host.global_memory_instruction_base.poke(base.U)
          dut.io.kernel_registers.host.trace_dump_base.poke(0.U)
          dut.io.kernel_ctrl.start.poke(true.B)
          dut.clock.step()
          dut.io.kernel_ctrl.start.poke(false.B)
          var guard = 0
          while (dut.io.kernel_ctrl.idle.peekBoolean() && guard < 100000) {
            dut.clock.step(); guard += 1
          }
          while (!dut.io.kernel_ctrl.done.peekBoolean() && guard < 600000) {
            dut.clock.step(); guard += 1
          }
          val eid = dut.io.kernel_registers.device.exception_id.peek().litValue.toInt
          val vc  = dut.io.kernel_registers.device.virtual_cycles.peek().litValue.toLong
          dut.clock.step()
          (eid, vc)
        }

        val to = 100000L
        for ((b, n) <- Seq((base0, "init_0"), (base1, "init_1"))) {
          val (eid, vc) = run(b, cmdWord(CMD_START, to))
          info(s"$n: eid=$eid vc=$vc")
          assert(!(eid > 0xffff), s"$n timed out")
        }

        val (eid, vc) = run(baseM, cmdWord(CMD_START, to) | STALL_ARM)
        info(s"MAIN (stallWave armed) terminated: eid=$eid vcycles=$vc (golden eid=$goldenEid vc=$goldenVcycles)")

        assert(eid <= 0xffff, s"MAIN timed out (eid=$eid) — STALL wave never gated the clock")
        assert(vc == goldenVcycles,
          s"vcycles=$vc, deferred-precise golden=$goldenVcycles (base 16 + margin 4) — the stall wave must defer the gate")
        assert(eid == goldenEid,
          s"eid=$eid, golden=$goldenEid (the captured application FINISH eid; STALL 0x7FFF only gates)")
        info(s"VERIFIED: RTL stall wave deferred the FINISH by the margin window and gated on the STALL — " +
          s"$vc vcycles, captured eid $eid — EXACT match to the deferred-precise interpreter.")
      }
  }
}
