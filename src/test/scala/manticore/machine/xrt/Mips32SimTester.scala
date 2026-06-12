package manticore.machine.xrt

import chisel3._
import chiseltest._
import manticore.machine.TestsCommon.RequiresVerilator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** Runs a compiled Manticore program (MIPS32 benchmark) through the full
  * `ManticoreFlatSimKernel` in Verilator, exactly as it would run on the FPGA.
  *
  * Two configurations are tested:
  *   2×2  (-Dmips32.objdir=/tmp/mips_out_nocf)  — all hops positive, baseline
  *   4×4  (-Dmips32.objdir4=/tmp/mips_out_4x4)  — exercises negative hops in
  *         both Programmer packets (cores (0,3),(3,0),(3,3)) and SEND instructions
  *         (e.g. core (0,2)→(0,1) uses yHops=-1)
  *
  * Run the no-URAM (BRAM, KCU105) configuration with -Dmanticore.no_uram=true.
  *
  * Golden (from `masm interpret`): halts via the MIPS halt at 53 virtual cycles,
  * 31 RF-write $display events, then "Got halt!" (eid 3).
  */
class Mips32SimTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  // 2×2 default points at the --no-cf build (matches the custom_alu=false RTL). The plain
  // /tmp/mips_out is a CF-extracted build that mis-executes on the no-CFU array and halts
  // early at vcycle 13 — do NOT use it here.
  val objdir            = sys.props.getOrElse("mips32.objdir",  "/tmp/mips_out_nocf")
  val objdir4           = sys.props.getOrElse("mips32.objdir4", "/tmp/mips_out_4x4")
  val userBase          = 16384 // userGlobalMemoryBase (words), from the manifest "base"
  val CMD_START         = 0
  val CMD_RESUME        = 1
  val CMD_CACHE_FLUSH   = 2
  val EXCEPTION_TIMEOUT = BigInt(1) << 16

  // manifest exceptions for the MIPS32 benchmark: 0,3 = FINISH ; 1,2 = FLUSH
  val FINISH = Set(0, 3)
  val FLUSH  = Set(1, 2)

  def readWords(p: String): Array[Int] = {
    val bytes = Files.readAllBytes(Paths.get(p))
    require(bytes.length % 2 == 0)
    Array.tabulate(bytes.length / 2) { i =>
      (bytes(2 * i) & 0xff) | ((bytes(2 * i + 1) & 0xff) << 8)
    }
  }

  def cmdWord(cmd: Int, timeout: Long): BigInt =
    (BigInt(1) << 63) | (BigInt(cmd) << 56) | BigInt(timeout)

  behavior of "ManticoreFlatSimKernel running MIPS32"

  /** Runs the full Programmer+boot+main flow against a ManticoreFlatSimKernel of the
    * given dimensions and checks the structural signature (vcycles, flushes, eid) against
    * the placed-interpreter golden.
    */
  def runMips32Test(dimX: Int, dimY: Int, dir: String,
                    goldenVcycles: Int, goldenFlushes: Int, goldenDisplays: Int,
                    customAlu: Boolean = false): Unit = {
    // ---- lay out the image like manticore-runtime ----
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
    info(s"image words=${image.length}  init0@$base0(${init0.length}) init1@$base1(${init1.length}) main@$baseM(${main.length})")

    test(new ManticoreFlatSimKernel(DimX = dimX, DimY = dimY, enable_custom_alu = customAlu))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(0)
        dut.io.kernel_ctrl.start.poke(false.B)
        dut.io.dmi.wen.poke(false.B)
        dut.io.dmi.locked.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step(16)
        dut.reset.poke(false.B)
        dut.clock.step(4)

        // ---- load image into global memory via DMI (word-addressed) ----
        // NOTE: the sim AxiMemoryModel is NOT zero-initialized (unlike BRAM on the board),
        // so we must write EVERY word incl. zeros.
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
          dut.io.dmi.wen.poke(false.B)
          dut.io.dmi.addr.poke(a.U)
          dut.clock.step()
          dut.io.dmi.rdata.peek().litValue.toInt
        }
        for (off <- Seq(0, 1, 2, 3)) {
          val got = rdMem(base0 + off); val exp = image(base0 + off)
          info(f"  load-check mem[$base0+$off]=0x$got%04x expect 0x$exp%04x ${if (got == exp) "ok" else "MISMATCH"}")
        }
        for (off <- Seq(0, 1)) {
          val got = rdMem(baseM + off); val exp = image(baseM + off)
          info(f"  load-check mem[$baseM+$off]=0x$got%04x expect 0x$exp%04x ${if (got == exp) "ok" else "MISMATCH"}")
        }

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
          val leftIdle = !dut.io.kernel_ctrl.idle.peekBoolean()
          while (!dut.io.kernel_ctrl.done.peekBoolean() && guard < 600000) {
            dut.clock.step(); guard += 1
            if (guard % 100000 == 0) {
              val v  = dut.io.kernel_registers.device.virtual_cycles.peek().litValue.toLong
              val bc = dut.io.kernel_registers.device.bootloader_cycles.peek().litValue.toLong
              info(s"  [t=$guard] vcycles=$v boot=$bc")
            }
          }
          if (!leftIdle) info(s"  [warn] start not accepted (never left idle) for base=$base cmdword=$cmdword")
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

        def readTrace(): (Long, Long, Int, Long) = {
          val w     = (0 to 6).map(o => rdMem(o).toLong)
          val pc    = w(0) | (w(1) << 16)
          val instr = w(2) | (w(3) << 16)
          val reg   = w(4).toInt
          val value = w(5) | (w(6) << 16)
          (pc, instr, reg, value)
        }

        val cmdFlush  = BigInt(2) << 56
        val trace     = scala.collection.mutable.ArrayBuffer.empty[(Long, Long, Int, Long)]
        var (eid, vc) = run(baseM, cmdWord(CMD_START, to))
        var flushes   = 0
        while (FLUSH.contains(eid) && flushes < 400) {
          val flushEid = eid
          val wrBefore = dut.io.dbg_axi_writes.peek().litValue
          run(baseM, cmdFlush)
          val wrAfter = dut.io.dbg_axi_writes.peek().litValue
          if (flushes < 3) {
            val aw = dut.io.dbg_last_awaddr.peek().litValue
            val wd = dut.io.dbg_last_wdata.peek().litValue
            info(f"  [diag] flush#$flushes: axi_writes $wrBefore->$wrAfter  last_awaddr=0x$aw%x  last_wdata_lo=0x$wd%016x")
          }
          if (flushEid == 1) {
            val rec = readTrace()
            trace += rec
            info(f"  flush#$flushes%-3d ${rec._1}%08x ${rec._2}%08x: RF[${rec._3}%2d] <= ${rec._4}")
          } else {
            info(s"  flush#$flushes (eid=$flushEid: 'Got halt!')")
          }
          val r = run(baseM, cmdWord(CMD_RESUME, to))
          eid = r._1; vc = r._2
          flushes += 1
        }
        info(s"MAIN terminated: eid=$eid vcycles=$vc after $flushes flushes, ${trace.length} RF-write records")

        assert(eid <= 0xffff, s"MAIN timed out (eid=$eid) — did NOT reach the MIPS halt")
        assert(eid == 3, s"MAIN ended with eid=$eid; the golden reaches the MIPS halt (eid 3)")
        assert(vc == goldenVcycles, s"vcycles=$vc, golden/interpreter=$goldenVcycles")
        assert(flushes == goldenFlushes, s"flushes=$flushes, golden=$goldenFlushes")
        assert(trace.length == goldenDisplays, s"RF-write displays=${trace.length}, golden=$goldenDisplays")
        info(s"VERIFIED (structural): RTL ran $vc vcycles, $flushes flushes, ${trace.length} RF-write " +
          s"displays, halted via $$finish (eid 3) — EXACT match to the placed interpreter.")

        // literal value check — readable since the rs4-bank + MemoryIntercept pass-through
        // fixes (see VERIFICATION.md "The (former) gap")
        val rf2 = trace.filter(_._3 == 2).map(_._4).toList
        val rf2Golden = List[Long](0, 0, 1, 3, 6, 10, 15, 21, 28, 36, 45)
        info(s"RF[2] trace values (golden $rf2Golden): $rf2")
        assert(rf2 == rf2Golden, s"RF[2] trace $rf2 != interpreter golden $rf2Golden")
      }
  }

  // 2×2: all hops positive, baseline correctness
  it should "execute the MIPS32 simulation on 2x2 and halt like the golden" taggedAs RequiresVerilator in {
    runMips32Test(dimX = 2, dimY = 2, dir = objdir,
      goldenVcycles = 53, goldenFlushes = 32, goldenDisplays = 31)
  }

  // 4×4: exercises negative hops in Programmer packets (cores (0,3),(3,0),(3,3))
  // and in SEND instructions (e.g. core (0,2)→(0,1) uses yHops=-1).
  // Same program, same golden: 53 vcycles, 32 flushes, 31 RF-write displays.
  it should "execute the MIPS32 simulation on 4x4 and halt like the golden" taggedAs RequiresVerilator in {
    runMips32Test(dimX = 4, dimY = 4, dir = objdir4,
      goldenVcycles = 53, goldenFlushes = 32, goldenDisplays = 31)
  }

  // CFU-enabled: the CF-extracted build (6 custom functions, default masm compile) on an
  // enable_custom_alu=true array. Interpreter golden for this build is identical:
  // 53 vcycles, 31 RF-write displays, RF[2] -> 45.
  val objdirCf = sys.props.getOrElse("mips32.objdircf", "/tmp/mips_out_cf")
  it should "execute the CF-extracted MIPS32 on a custom-ALU 2x2 and halt like the golden" taggedAs RequiresVerilator in {
    runMips32Test(dimX = 2, dimY = 2, dir = objdirCf,
      goldenVcycles = 53, goldenFlushes = 32, goldenDisplays = 31, customAlu = true)
  }
}
