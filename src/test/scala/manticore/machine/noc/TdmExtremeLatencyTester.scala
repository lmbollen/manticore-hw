package manticore.machine.noc

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ManticoreBaseISA
import manticore.machine.TestsCommon.RequiresVerilator
import manticore.machine.core.NoCBundle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Directed TDM-link tests at the DEMO'S REAL cable latencies — including the rig's
  * known high-latency link (T=1298: a 1287-deep wire, ~160 frames of one link in
  * flight simultaneously) and the shallow end (T=15: a 4-deep wire, the contract's
  * minimum). The generic property tester sweeps small T (≈10..42) and never overlaps
  * in-flight same-link packets; the 8-chip demo loses exactly the packets that cross
  * the extreme-latency cable in period-spaced same-link trains, so this reproduces
  * that regime in isolation: per-link back-to-back trains at exactly `period`
  * spacing (the compiler's --tdm-period minimum, the demo's steady state) plus all
  * links loaded, asserting every packet is delivered exactly once at t + T - 1 with
  * no overflow — i.e., no loss, no duplication, no corruption.
  */
class TdmExtremeLatencyTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val config = ManticoreBaseISA
  val nBanks = 4 // the demo's nLinks per seam side
  val n      = 1 // cyclesPerSlot
  val period = nBanks * n

  def emptyPkt(dut: TdmLatencyHarness): NoCBundle =
    NoCBundle(dut.DimX, dut.DimY, config).Lit(
      _.data -> 0.U,
      _.address -> 0.U,
      _.valid -> false.B,
      _.xHops -> 0.S,
      _.yHops -> 0.S
    )

  def mkPkt(dut: TdmLatencyHarness, data: Int, addr: Int, xh: Int, yh: Int): NoCBundle =
    NoCBundle(dut.DimX, dut.DimY, config).Lit(
      _.data -> data.U,
      _.address -> addr.U,
      _.valid -> true.B,
      _.xHops -> xh.S,
      _.yHops -> yh.S
    )

  /** Drive per-link period-spaced trains and record every delivery (bank, cycle,
    * data, address, xHops, yHops) until `until`.
    */
  def runTrains(
      dut: TdmLatencyHarness,
      T: Int,
      trains: Seq[(Int, Int, Int)] // (bank, startCycleOffset, count)
  ): (Seq[(Int, BigInt, Int, Int, Int, Int)], Set[(Int, BigInt, Int)]) = {
    val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, Int, Int, Int, Int)]
    // injection schedule: absolute cycle -> (bank, data); expected = (bank, t+T-1, data)
    val inj = scala.collection.mutable.Map.empty[BigInt, List[(Int, Int)]]
    val expected = scala.collection.mutable.Set.empty[(Int, BigInt, Int)]
    val t0  = dut.io.cyc.peek().litValue + period * 2
    var maxT = t0
    for (((b, off, cnt), ti) <- trains.zipWithIndex; k <- 0 until cnt) {
      val t = t0 + off + k * period
      val data = (0x1000 + ti * 0x100 + k) & 0xffff
      inj(t) = (b, data) :: inj.getOrElse(t, Nil)
      expected += ((b, t + T - 1, data))
      if (t > maxT) maxT = t
    }
    val until = maxT + T + period + 4
    while (dut.io.cyc.peek().litValue <= until) {
      val cyc = dut.io.cyc.peek().litValue
      // sample outputs THIS cycle
      for (i <- 0 until dut.nBanks) {
        if (dut.io.out(i).valid.peek().litToBoolean) {
          seen += (
            (
              i,
              cyc,
              dut.io.out(i).data.peek().litValue.toInt,
              dut.io.out(i).address.peek().litValue.toInt,
              dut.io.out(i).xHops.peek().litValue.toInt,
              dut.io.out(i).yHops.peek().litValue.toInt
            )
          )
        }
      }
      // drive inputs for THIS cycle
      inj.get(cyc) match {
        case Some(pkts) =>
          for ((b, d) <- pkts) dut.io.in(b).poke(mkPkt(dut, d, 0x2a, 1, -1))
        case None => (0 until nBanks).foreach(i => dut.io.in(i).poke(emptyPkt(dut)))
      }
      dut.clock.step()
      (0 until nBanks).foreach(i => dut.io.in(i).poke(emptyPkt(dut)))
    }
    (seen.toSeq, expected.toSet)
  }

  def check(T: Int, trains: Seq[(Int, Int, Int)], label: String): Unit = {
    it should s"deliver every packet exactly once at t+T-1 for T=$T ($label)" taggedAs RequiresVerilator in {
      val d = T - period - 3 // the sim kernel's exec wire depth for this T
      test(new TdmLatencyHarness(nBanks, n, d, T, config))
        .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(0) // deep-T cases legitimately have >1000 quiet cycles in flight
        (0 until nBanks).foreach(i => dut.io.in(i).poke(emptyPkt(dut)))
        dut.clock.step()
        val (seen, expected) = runTrains(dut, T, trains)
        // exact constant-latency contract: the delivered (bank, cycle, data) set must
        // EQUAL the predicted set — any loss, duplication or timing shift fails
        val got = seen.map { case (b, cyc, data, _, _, _) => (b, cyc, data) }.toSet
        withClue(s"T=$T $label missing=${(expected -- got).take(5)} extra=${(got -- expected).take(5)}: ") {
          got shouldBe expected
        }
        seen.foreach { case (b, cyc, data, addr, xh, yh) =>
          withClue(s"T=$T $label pkt data=$data bank=$b @$cyc fields: ") {
            addr shouldBe 0x2a
            xh shouldBe 1
            yh shouldBe -1
          }
        }
        dut.io.overflow.peek().litToBoolean shouldBe false
      }
    }
  }

  behavior of "TDM link at the demo's real cable latencies"

  // The rig's known high-latency cable: ~160 same-link frames in flight; trains at the
  // compiler's minimum spacing (--tdm-period = period) on every link, phase-staggered
  // like real switch traffic.
  check(1298, Seq((0, 0, 24), (1, 1, 24), (2, 2, 24), (3, 3, 24)), "deep: all links, period-spaced trains")
  // single-link long train through the deep pipe
  check(1298, Seq((2, 0, 40)), "deep: one link, 40-packet train")
  // the shallow end of the envelope (wire depth 4 = the contract minimum)
  check(15, Seq((0, 0, 24), (1, 1, 24), (2, 2, 24), (3, 3, 24)), "shallow: all links, period-spaced trains")
  // the asymmetric mid values seen on the rig
  check(122, Seq((0, 0, 24), (1, 1, 24), (2, 2, 24), (3, 3, 24)), "mid: all links")
  check(71, Seq((0, 0, 24), (1, 1, 24), (2, 2, 24), (3, 3, 24)), "nominal: all links")
}
