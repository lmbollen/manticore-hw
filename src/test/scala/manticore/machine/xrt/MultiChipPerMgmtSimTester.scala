package manticore.machine.xrt

import chisel3._
import chiseltest._
import manticore.machine.TestsCommon.RequiresVerilator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import scala.io.Source

/** Full-system EIGHT-IC RTL simulation where EACH IC is a full, independent
  * [[ManticoreFlatArray]] with its OWN Management unit and its OWN clock gate — the faithful
  * model of the bittide 8-FPGA demo (vs [[MultiChipTdmSimKernel]], a single Management + bare
  * slaves). The host (this tester) drives each IC INDEPENDENTLY over its own host registers /
  * DMI, exactly as the real Driver drives the 8 MUs:
  *   - load each chip's OWN split image into its OWN gmem (the `chips[]` manifest);
  *   - per-chip CMD_START for the initializers (intra-chip boot);
  *   - simultaneous CMD_START_AT(S) for main — every IC holds its (independently gated)
  *     compute clock until the reset-aligned `totalCycleCount` reaches the common S;
  *   - the reporter chip (0,0) produces the $display (SIG) trace; on each FLUSH the stall
  *     wave halts every IC, so the host reads the reporter's trace and resumes ALL ICs with
  *     scheduled CMD_RESUME-at-R, until the reporter FINISHes.
  *
  * The seams use the folded-torus GRID MESH with PER-CABLE, PER-DIRECTION latencies read
  * from the demo's latencies.csv (the real, asymmetric Bittide UGNs). Program + csv come from
  * the existing compile flow (`manticore_compile_program.sh` / masm `--chip-dim-x/y
  * --stall-wave --hop-latencies`); point `permgmt.dir` / `permgmt.csv` at them.
  */
class MultiChipPerMgmtSimTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val dir = sys.props.getOrElse("permgmt.dir", "/tmp/manticore_8fpga")
  val csv = sys.props.getOrElse("permgmt.csv", "/tmp/latencies_demo.csv")
  val chipCols = sys.props.getOrElse("permgmt.cols", "2").toInt
  val chipRows = sys.props.getOrElse("permgmt.rows", "4").toInt
  val chipDimX = sys.props.getOrElse("permgmt.dimx", "4").toInt
  val chipDimY = sys.props.getOrElse("permgmt.dimy", "4").toInt
  val userBase = 16384
  val nChips = chipCols * chipRows
  val gDimX = chipCols * chipDimX
  val gDimY = chipRows * chipDimY

  val CMD_START = 0
  val CMD_RESUME = 1
  val CMD_FLUSH = 2
  val CMD_START_AT = 3
  val STALL_ARM = 55 // schedule_config bit: arm stall-wave (gate ONLY on STALL_EID; $display non-stalling)

  // reporter chip (0,0): FLUSH eid=0 (SIG $display), FINISH eid=1; other chips STALL (32767)
  val FLUSH = Set(0)
  val FINISH = Set(1)
  val goldenVcycles = 1025
  val goldenFlushes = 4
  // Final seam-crossed sig2: the fully-folded signature after the last $display (cyc 1023). Its
  // value requires correct cross-chip NoC delivery; this is the data-dependent correctness check.
  // (The historic (1,0,0) reads were two RTL bugs, both fixed: the Switch's south-turn branches
  // clobbered terminal_reg — silently dropping same-cycle terminal deliveries — and
  // MemoryIntercept sampled the gmem request one cycle late, corrupting the display GST burst.
  // See Pico84SingleChipTester, which now asserts the full golden triple.)
  val goldenFinalSig2 = 225
  val startMargin = 2000L // totalCycleCount headroom for CMD_START_AT / resume-at-R arming

  def cmdWord(cmd: Int, timeout: Long): BigInt = (BigInt(1) << 63) | (BigInt(cmd) << 56) | BigInt(timeout)
  def startAtWord(s: Long): BigInt = (BigInt(CMD_START_AT) << 56) | BigInt(s) // no timeout_enabled
  def resumeAtWord(r: Long): BigInt = (BigInt(1) << 63) | (BigInt(CMD_RESUME) << 56) | BigInt(r)
  val flushWord: BigInt = BigInt(CMD_FLUSH) << 56

  def readWords(p: String): Array[Int] = {
    val bytes = Files.readAllBytes(Paths.get(p))
    require(bytes.length % 2 == 0)
    Array.tabulate(bytes.length / 2)(i => (bytes(2 * i) & 0xff) | ((bytes(2 * i + 1) & 0xff) << 8))
  }

  // ---- fold mapping (must match the compiler Fold + Latencies.hs) -> per-cable latency ----
  def foldChip(g: Int, n: Int, w: Int): Int = { val h = w / 2; if (g < n * h) g / h else n - 1 - (g - n * h) / h }
  def icAt(cx: Int, cy: Int): Int = cy * chipCols + cx
  def icOfCore(x: Int, y: Int): Int = icAt(foldChip(x, chipCols, chipDimX), foldChip(y, chipRows, chipDimY))
  def ringNeighbour(x: Int, y: Int, d: String): (Int, Int) = d match {
    case "east"  => ((x + 1) % gDimX, y)
    case "west"  => ((x - 1 + gDimX) % gDimX, y)
    case "north" => (x, (y + 1) % gDimY)
    case "south" => (x, (y - 1 + gDimY) % gDimY)
  }
  // (srcIC, dstIC) -> latency, built from every directed crossing in the csv (all crossings
  // of a given IC pair share one latency = that cable-direction's value).
  lazy val pairLatency: Map[(Int, Int), Int] = {
    val src = Source.fromFile(csv)
    try {
      src.getLines().drop(1).flatMap { line =>
        line.split(",") match {
          case Array(sx, sy, d, lat) =>
            val (x, y) = (sx.toInt, sy.toInt)
            val (nx, ny) = ringNeighbour(x, y, d)
            Some((icOfCore(x, y), icOfCore(nx, ny)) -> lat.toInt)
          case _ => None
        }
      }.toMap
    } finally src.close()
  }
  // (cx, cy, isXcable) -> (latFwd = (cx,cy)->neighbour, latBwd = neighbour->(cx,cy))
  def cableLatency(cx: Int, cy: Int, isX: Boolean): (Int, Int) = {
    val (a, b) = if (isX) (icAt(cx, cy), icAt(cx + 1, cy)) else (icAt(cx, cy), icAt(cx, cy + 1))
    val l = pairLatency.getOrElse((a, b), throw new RuntimeException(s"no latency for IC $a->$b"))
    val r = pairLatency.getOrElse((b, a), throw new RuntimeException(s"no latency for IC $b->$a"))
    (l, r)
  }

  behavior of "MultiChipPerMgmtSimKernel: 8 ICs, one Management each, asymmetric seams"

  it should "boot each IC from its own image, simultaneous-start via CMD_START_AT, run the distributed stall wave, and match the golden" taggedAs RequiresVerilator in {
    // per-chip split image + bases, indexed by node id = cy*chipCols + cx
    case class ChipImg(image: Array[Int], base0: Int, base1: Int, baseM: Int)
    val imgs: IndexedSeq[ChipImg] = (0 until nChips).map { n =>
      val (cx, cy) = (n % chipCols, n / chipCols)
      val base = s"$dir/chip_${cx}_${cy}"
      val init0 = readWords(s"$base/init_0/exec.bin")
      val init1 = readWords(s"$base/init_1/exec.bin")
      val main = readWords(s"$base/main/exec.bin")
      val b0 = userBase; val b1 = b0 + init0.length; val bM = b1 + init1.length
      val img = Array.fill(bM + main.length)(0)
      Array.copy(init0, 0, img, b0, init0.length)
      Array.copy(init1, 0, img, b1, init1.length)
      Array.copy(main, 0, img, bM, main.length)
      ChipImg(img, b0, b1, bM)
    }
    info(s"loaded ${nChips} per-chip images; sizes=${imgs.map(_.image.length).mkString(",")}")

    test(
      new MultiChipPerMgmtSimKernel(chipCols, chipRows, chipDimX, chipDimY, cableLatency = cableLatency)
    ).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      for (n <- 0 until nChips) {
        dut.io.start(n).poke(false.B)
        dut.io.dmi(n).wen.poke(false.B)
        dut.io.dmi(n).locked.poke(false.B)
        dut.io.dmi(n).wdata.poke(0.U)
        dut.io.dmi(n).addr.poke(0.U)
        dut.io.host(n).schedule_config.poke(0.U)
        dut.io.host(n).global_memory_instruction_base.poke(0.U)
        dut.io.host(n).trace_dump_base.poke(0.U)
      }
      dut.reset.poke(true.B); dut.clock.step(16); dut.reset.poke(false.B); dut.clock.step(4)

      // ---- load every chip's image into its OWN gmem (all DMIs in parallel) ----
      val maxLen = imgs.map(_.image.length).max
      var i = 0
      while (i < maxLen) {
        for (n <- 0 until nChips) {
          val img = imgs(n).image
          if (i < img.length) {
            dut.io.dmi(n).wen.poke(true.B); dut.io.dmi(n).addr.poke(i.U); dut.io.dmi(n).wdata.poke(img(i).U)
          } else dut.io.dmi(n).wen.poke(false.B)
        }
        dut.clock.step(); i += 1
      }
      for (n <- 0 until nChips) dut.io.dmi(n).wen.poke(false.B)
      dut.clock.step()

      def deviceEid(n: Int): Int = dut.io.device(n).exception_id.peek().litValue.toInt
      def deviceVc(n: Int): Long = dut.io.device(n).virtual_cycles.peek().litValue.toLong
      def execCycles(n: Int): Long = dut.io.device(n).execution_cycles.peek().litValue.toLong
      def rdMem(n: Int, a: Int): Int = {
        dut.io.dmi(n).wen.poke(false.B); dut.io.dmi(n).addr.poke(a.U); dut.clock.step()
        dut.io.dmi(n).rdata.peek().litValue.toInt
      }
      // DEBUG (residual #1): verify each chip's MAIN header (loc-hop, body_length) actually
      // landed in gmem == the source image. If gmem matches img but ic5 still boots a short
      // vcycle, the DMI load is fine and the RTL boot/Programmer mis-reads it.
      for (n <- 0 until nChips) {
        val bm = imgs(n).baseM
        info(f"  [GMEM-CHK] chip$n baseM=$bm gmem[bM]=${rdMem(n, bm)} gmem[bM+1]=${rdMem(n, bm + 1)} " +
          f"gmem[bM+2]=${rdMem(n, bm + 2)} | img[bM]=${imgs(n).image(bm)} img[bM+1]=${imgs(n).image(bm + 1)} " +
          f"img.len=${imgs(n).image.length}")
      }
      def clearStarts(): Unit = for (n <- 0 until nChips) dut.io.start(n).poke(false.B)

      // issue (base, cmdword) on each chip simultaneously, pulse start one cycle
      def issueAll(per: Int => (Int, BigInt)): Unit = {
        for (n <- 0 until nChips) {
          val (base, cmd) = per(n)
          dut.io.host(n).schedule_config.poke(cmd.U)
          dut.io.host(n).global_memory_instruction_base.poke(base.U)
          dut.io.host(n).trace_dump_base.poke(0.U)
          dut.io.start(n).poke(true.B)
        }
        dut.clock.step(); clearStarts()
      }
      // issue a command on ONE chip only (others untouched / left gated)
      def issueOne(n: Int, base: Int, cmd: BigInt): Unit = {
        dut.io.host(n).schedule_config.poke(cmd.U)
        dut.io.host(n).global_memory_instruction_base.poke(base.U)
        dut.io.host(n).trace_dump_base.poke(0.U)
        dut.io.start(n).poke(true.B)
        dut.clock.step(); dut.io.start(n).poke(false.B)
      }
      // run ONE command on chip n with the full idle->done handshake (mirrors
      // Pico84SingleChipTester.run): pulse start, wait to LEAVE idle (command accepted), then wait
      // for done. Used to drive the post-FINISH cache flush that drains the reporter's last
      // $display to gmem (issueOne's bare done||idle check would race — the chip is already idle).
      def runOne(n: Int, base: Int, cmd: BigInt): Unit = {
        issueOne(n, base, cmd)
        var guard = 0
        while (dut.io.idle(n).peekBoolean() && guard < 500000) { dut.clock.step(); guard += 1 }
        while (!dut.io.done(n).peekBoolean() && guard < 4000000) { dut.clock.step(); guard += 1 }
        dut.clock.step()
      }
      // "command complete" = done || idle (the bittide UserCore's chipStopped; under
      // stallWave a FINISH returns the chip to idle without a persistent `done` level).
      def stopped(n: Int): Boolean = dut.io.done(n).peekBoolean() || dut.io.idle(n).peekBoolean()
      def chipState(n: Int): String =
        s"$n(done=${dut.io.done(n).peekBoolean()},idle=${dut.io.idle(n).peekBoolean()},eid=${deviceEid(n)},vc=${deviceVc(n)},exec=${execCycles(n)})"
      // step until chip `n` stops (completed a command / captured an exception)
      def waitStopped(n: Int, what: String, limit: Int = 4000000): Unit = {
        var g = 0
        while (!stopped(n) && g < limit) { dut.clock.step(); g += 1 }
        if (!stopped(n)) info(s"$what TIMEOUT after $g cycles: " + chipState(n))
        assert(stopped(n), s"chip $n: timeout waiting for stop ($what) after $g cycles")
      }
      // step until EVERY chip stops (each chip's master self-FINISHes its init)
      def waitAllStopped(what: String, limit: Int = 1500000): Unit = {
        dut.clock.step(200)
        info(s"$what after 200cy: " + (0 until nChips).map(chipState).mkString(" "))
        var g = 0
        while (!(0 until nChips).forall(stopped) && g < limit) { dut.clock.step(); g += 1 }
        val stuck = (0 until nChips).filterNot(stopped)
        if (stuck.nonEmpty)
          info(s"$what TIMEOUT after $g cycles. " +
            s"seamGateViolation=${dut.io.dbg_gate_in_flight.peekBoolean()} " +
            s"dataloss=${dut.io.dbg_seam_dataloss.peekBoolean()} overflow=${dut.io.dbg_seam_overflow.peekBoolean()}. " +
            "STUCK: " + stuck.map(chipState).mkString(" ") +
            " | STOPPED: " + (0 until nChips).filter(stopped).map(chipState).mkString(" "))
        assert(stuck.isEmpty, s"timeout: not all chips stopped ($what): stuck=$stuck")
      }

      // Run EVERY phase (inits + main) with a COORDINATED simultaneous start (CMD_START_AT):
      // boot, then hold until a common cycle S, so all chips begin the phase's vcycle 1
      // ALIGNED despite boot-latency skew. The init phases carry cross-chip seam traffic
      // (confirmed by the gate-in-flight assertion: a frame on y_0_2 during init_1), so an
      // async per-chip CMD_START desyncs them and freezes a seam frame mid-crossing. Aligning
      // keeps the chips in lockstep so the seam empties on a shared vcycle boundary. The margin
      // must exceed the slowest chip's boot for that phase (main is the largest image).
      def startAtPhase(baseOf: Int => Int, margin: Long, what: String): Unit = {
        val s = (0 until nChips).map(execCycles).max + margin
        info(s"$what: CMD_START_AT S=$s")
        issueAll(n => (baseOf(n), startAtWord(s)))
        waitAllStopped(what)
      }
      val initMargin = 100000L
      val mainMargin = 500000L

      // ---- initializers: coordinated CMD_START_AT, both phases ----
      startAtPhase(n => imgs(n).base0, initMargin, "init_0")
      for (n <- 0 until nChips) assert(deviceEid(n) <= 0xffff, s"chip $n init_0 timed out (eid=${deviceEid(n)})")
      startAtPhase(n => imgs(n).base1, initMargin, "init_1")
      for (n <- 0 until nChips) assert(deviceEid(n) <= 0xffff, s"chip $n init_1 timed out (eid=${deviceEid(n)})")
      info("all chips completed initializers")

      // ---- main (ARMED stall-wave): run the whole program WITHOUT stopping on $display ----
      // With STALL_ARM_BIT set the chip gates ONLY on the coordinated STALL (eid 0x7FFF,
      // seeded by $finish); $display (FLUSH) is captured but does NOT stall the pipeline
      // (deferred-precise). So all 8 chips run in lockstep from the aligned CMD_START_AT, the
      // seam is never gated per-$display, and the host reads the result at the terminating
      // stall. (This replaces the single-chip-style stop-flush-resume-per-$display loop, which
      // was the wrong model here: only the reporter $displays, so it stalled alone.)
      val s0 = (0 until nChips).map(execCycles).max + mainMargin
      val armedMain = startAtWord(s0) | (BigInt(1) << STALL_ARM)
      info(s"main (ARMED): CMD_START_AT S=$s0 (max execCycles=${(0 until nChips).map(execCycles).max})")
      issueAll(n => (imgs(n).baseM, armedMain))

      val reporter = icAt(0, 0)
      // Run freely to the program's $finish; the reporter's captured exception id becomes
      // FINISH (1). $display writes its trace and keeps running, so we do NOT stop per SIG.
      val mainLimit = 5000000
      var g = 0
      // diagnostics: first cycle each seam signal trips + where vc freezes, so we know the
      // CAUSAL ORDER (does the send-side mux overflow precede the stall, or follow the gate?).
      var fOvfC = -1L; var fOvfVc = -1L
      var fGateC = -1L; var fGateVc = -1L
      var fLossC = -1L; var fLossVc = -1L
      var lastVc = deviceVc(reporter); var lastVcChangeC = 0L
      var vcEverChanged = false; var frozenFor = 0L
      while (deviceEid(reporter) != 1 && g < mainLimit && frozenFor < 100000) {
        dut.clock.step(); g += 1
        if (dut.io.dbg_seam_overflow.peekBoolean() && fOvfC < 0) { fOvfC = g; fOvfVc = deviceVc(reporter) }
        if (dut.io.dbg_gate_in_flight.peekBoolean() && fGateC < 0) { fGateC = g; fGateVc = deviceVc(reporter) }
        if (dut.io.dbg_seam_dataloss.peekBoolean() && fLossC < 0) { fLossC = g; fLossVc = deviceVc(reporter) }
        val vcNow = deviceVc(reporter)
        // only treat a stalled vc as a "freeze" once the main has actually advanced PAST vc 0
        // (vc==0 is legitimate during the long CMD_START_AT hold until S).
        if (vcNow != lastVc) { lastVc = vcNow; lastVcChangeC = g; vcEverChanged = true; frozenFor = 0 }
        else if (lastVc > 0) frozenFor += 1
        if (g % 50000 == 0)
          info(s"  [t=$g] vc/chip=${(0 until nChips).map(deviceVc).mkString(",")} " +
            s"ovf=${dut.io.dbg_seam_overflow.peekBoolean()} gate=${dut.io.dbg_gate_in_flight.peekBoolean()} " +
            s"stop=${(0 until nChips).map(n => if (stopped(n)) "1" else "0").mkString}")
      }
      val eid = deviceEid(reporter)
      val vc = deviceVc(reporter)
      val muxOvf = dut.io.dbg_seam_overflow.peekBoolean()
      val loss = dut.io.dbg_seam_dataloss.peekBoolean()
      val gateViol = dut.io.dbg_gate_in_flight.peekBoolean()
      // Make the pass DATA-DEPENDENT: recover the reporter's FINAL $display (SIG) record. In armed
      // mode the $display GSTs land in the write-back cache but are never flushed during the run;
      // the reporter is now halted with its last SIG line still dirty in cache. Drain it with ONE
      // cache-flush (cmd 2), then read words 0..5 = [sig0_lo,hi, sig1_lo,hi, sig2_lo,hi].
      runOne(reporter, imgs(reporter).baseM, flushWord)
      val sig = (
        rdMem(reporter, 0) | (rdMem(reporter, 1) << 16),
        rdMem(reporter, 2) | (rdMem(reporter, 3) << 16),
        rdMem(reporter, 4) | (rdMem(reporter, 5) << 16)
      )
      // Second $display statement ("CHK %d %d %d", eid 2): three reporter-LOCAL states
      // (8-bit +3 counter, 16-bit LFSR, 32-bit accumulator) at trace words [6][7][8,9]
      // (globally-unique offsets, so both statements' finals coexist in gmem). Because
      // the CHK states never leave the reporter's process, their final values must be
      // interpreter-golden EVEN while cross-seam application values are under debug —
      // a pure end-to-end check of the multi-state $display/trace mechanism.
      val chk = (rdMem(reporter, 6), rdMem(reporter, 7), rdMem(reporter, 8) | (rdMem(reporter, 9) << 16))
      info(s"MAIN (armed) ended after $g cycles: reporter eid=$eid vc=$vc " +
        s"gateViolation=$gateViol overflow=$muxOvf dataloss=$loss finalSIG=$sig finalCHK=$chk")
      info(s"DIAG first-trip: overflow @cyc=$fOvfC vc=$fOvfVc | gateViol @cyc=$fGateC vc=$fGateVc | dataloss @cyc=$fLossC vc=$fLossVc")
      info(s"DIAG vc froze at cyc=$lastVcChangeC vc=$lastVc (frozenFor=$frozenFor); per-chip stop=${(0 until nChips).map(n => s"$n:${stopped(n)}").mkString(",")}")
      info(s"DIAG per-chip vc=${(0 until nChips).map(n => s"$n:${deviceVc(n)}").mkString(",")}")
      info(s"per-chip eids: ${(0 until nChips).map(n => s"$n:${deviceEid(n)}").mkString(" ")}")

      assert(!gateViol, "seam clock gated while a frame was in flight (stall not on an empty boundary)")
      assert(!loss, "TDM seam demux dropped a packet (real data loss)")
      assert(eid == 1, s"reporter did not reach FINISH (eid=$eid) within $g cycles; vc=$vc")
      // Multi-state $display mechanism check (reporter-local, seam-independent): the final CHK
      // record must be the interpreter golden (last display at cyc 895, next-value semantics; masm interpret 2026-07-06).
      // Asserted BEFORE the seam-dependent sig2 so a transport regression can't mask a display-
      // mechanism regression (or vice versa).
      val goldenFinalChk = (128, 11707, 115475)
      // NOTE: the extractor/splitter may re-partition the CHK state cones onto other
      // cores despite the next-value-display co-location nudge; until the cross-seam
      // residual is fixed, a CHK mismatch here can therefore also mean "this compile
      // placed part of the CHK cone across a broken seam" (verified mechanism-correct
      // on the rig run 28774339849 and single-chip; the counter word is the most
      // placement-robust of the three).
      assert(chk == goldenFinalChk,
        s"final CHK $chk != golden $goldenFinalChk — display mechanism OR cross-seam placement of the CHK cone")
      // DATA-DEPENDENT correctness: the final seam-crossed sig2 must reach the golden fold value;
      // a broken inter-chip NoC would deliver a wrong (or unchanged) sig2 even when the reporter
      // still reaches FINISH on its fixed cycle schedule. (sig0/sig1 reported, not asserted.)
      assert(sig._3 == goldenFinalSig2,
        s"final seam-crossed sig2=${sig._3} != golden $goldenFinalSig2 (full triple $sig)")
      if (vc == goldenVcycles) info(s"vcycles EXACT: $vc")
      else info(s"vcycles=$vc (golden $goldenVcycles) — note: armed FINISH is captured at the deferred stall")
      info(s"VERIFIED (8-IC per-Management, armed stall-wave, asymmetric seams): reached FINISH, no gate-violation, no demux loss")
    }
  }
}
