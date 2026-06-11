package manticore.machine.noc

import Chisel._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ISA
import manticore.machine.ManticoreBaseISA
import manticore.machine.core.NoCBundle
import manticore.machine.core.TdmLinkDemux
import manticore.machine.core.TdmLinkMux
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Property tests (ScalaCheck) for the STATIC LATENCY contract of the TDM components.
  *
  * Contract (constant-latency release): for a configuration (nBanks, cyclesPerSlot N,
  * transceiver latency D, configured total latency T >= nBanks*N + D + 2), a packet
  * injected on bank b during cycle t is presented on the receiver's output bank b
  * during EXACTLY
  *
  *     deliver(t) = t + T - 1        (the destination switch register adds the final +1)
  *
  * for EVERY bank and EVERY arrival phase: the frame carries its slot-wait age and the
  * demux holds each packet until the total reaches T. A constant latency is exactly
  * what the compiler's per-link model (--hop-latencies) charges, and it preserves the
  * scheduler's NoC collision guarantees (nothing ever appears early).
  *
  * Property 1 sweeps randomly generated configurations *of multiple sizes* with random
  * (bank, phase) injection sequences — duplicates included (repeatability).
  * Property 2 injects a random SUBSET of banks in the same cycle: all must deliver in
  * the SAME cycle (t + T - 1), the strongest form of timing independence under
  * contention for the single wire.
  */
class TdmLatencyHarness(val nBanks: Int, val cyclesPerSlot: Int, val D: Int, val T: Int, config: ISA)
    extends Module {
  val DimX = 8; val DimY = 4 // packet payload dims (irrelevant to the TDM logic)
  val io = IO(new Bundle {
    val in       = Input(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
    val out      = Output(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
    val overflow = Output(Bool())
    val cyc      = Output(UInt(32.W))
  })
  val mux   = Module(new TdmLinkMux(nBanks, cyclesPerSlot, DimX, DimY, config))
  val demux = Module(new TdmLinkDemux(nBanks, cyclesPerSlot, D, T, DimX, DimY, config))
  mux.io.in          := io.in
  mux.io.connected   := true.B
  demux.io.connected := true.B
  mux.io.bypass      := false.B
  demux.io.bypass    := false.B
  demux.io.rx := ShiftRegister(mux.io.tx, D) // the fixed-latency "transceiver"
  io.out      := demux.io.out
  io.overflow := mux.io.overflow
  // free-running cycle counter, in phase with the mux's slot rotation (both reset to 0)
  val cyc = RegInit(0.U(32.W)); cyc := cyc + 1.U
  io.cyc := cyc
}

class TdmStaticLatencyTester
    extends AnyFlatSpec
    with ChiselScalatestTester
    with Matchers
    with ScalaCheckPropertyChecks {

  val config = ManticoreBaseISA

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 12, workers = 1)

  case class Cfg(nBanks: Int, n: Int, d: Int, slack: Int) {
    def period = nBanks * n
    def T      = period + d + 2 + slack // configured constant seam latency
  }
  val cfgGen: Gen[Cfg] = for {
    nBanks <- Gen.choose(2, 10)
    n      <- Gen.choose(1, 3)
    d      <- Gen.choose(1, 8)
    slack  <- Gen.choose(0, 4)
  } yield Cfg(nBanks, n, d, slack)

  /** the constant-latency contract oracle: presentation cycle for injection at t */
  def predictedDelivery(t: BigInt, b: Int, cfg: Cfg): BigInt = t + cfg.T - 1

  def emptyPkt(dut: TdmLatencyHarness): NoCBundle =
    NoCBundle(dut.DimX, dut.DimY, config).Lit(
      _.data -> 0.U, _.address -> 0.U, _.valid -> false.B, _.xHops -> 0.S, _.yHops -> 0.S)

  def mkPkt(dut: TdmLatencyHarness, data: Int): NoCBundle =
    NoCBundle(dut.DimX, dut.DimY, config).Lit(
      _.data -> data.U, _.address -> 0x3.U, _.valid -> true.B, _.xHops -> 1.S, _.yHops -> (-1).S)

  /** observe all banks until `until`; returns (bank, cycle, data) deliveries */
  def collect(dut: TdmLatencyHarness, until: BigInt): Seq[(Int, BigInt, Int)] = {
    val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, Int)]
    while (dut.io.cyc.peek().litValue <= until) {
      val cyc = dut.io.cyc.peek().litValue
      for (i <- 0 until dut.nBanks)
        if (dut.io.out(i).valid.peek().litToBoolean)
          seen += ((i, cyc, dut.io.out(i).data.peek().litValue.toInt))
      dut.clock.step()
    }
    seen.toSeq
  }

  behavior of "TDM static latency contract (property-based)"

  it should "deliver at exactly the predicted cycle for any size, bank and arrival phase" in {
    val caseGen = for {
      cfg    <- cfgGen
      ncases <- Gen.choose(4, 8)
      cases  <- Gen.listOfN(ncases, for {
        b     <- Gen.choose(0, cfg.nBanks - 1)
        phase <- Gen.choose(0, cfg.nBanks * cfg.n - 1)
      } yield (b, phase)) // duplicates allowed: repeated phases check repeatability
    } yield (cfg, cases)

    forAll(caseGen) { case (cfg, cases) =>
      test(new TdmLatencyHarness(cfg.nBanks, cfg.n, cfg.d, cfg.T, config)) { dut =>
        (0 until cfg.nBanks).foreach(i => dut.io.in(i).poke(emptyPkt(dut)))
        dut.clock.step()
        val period = cfg.nBanks * cfg.n
        cases.zipWithIndex.foreach { case ((b, phase), k) =>
          val data = (0x2000 + k) & 0xffff
          while ((dut.io.cyc.peek().litValue % period) != phase) dut.clock.step()
          val t        = dut.io.cyc.peek().litValue
          val expected = predictedDelivery(t, b, cfg)
          dut.io.in(b).poke(mkPkt(dut, data))
          dut.clock.step()
          dut.io.in(b).poke(emptyPkt(dut))
          val seen = collect(dut, expected + 1)
          withClue(s"$cfg bank=$b phase=$phase t=$t: ") {
            seen should contain only ((b, expected, data))
          }
          dut.clock.step()
        }
        dut.io.overflow.peek().litToBoolean shouldBe false
      }
    }
  }

  it should "sustain full rate: same-bank injections exactly one period apart never collide" in {
    // The compiler (--tdm-period) schedules same-link crossings no closer than one TDM
    // period. This is only collision-free when the wire absorbs all the slack
    // (wireLatency == T - period - 2, i.e. slack == 0): the demux bank occupancy is then
    // period - age <= period and a period-spaced successor lands exactly on the release
    // boundary. This property would have caught the release==1 overwrites seen in the
    // two-chip system test, which used a 1-cycle-shallow wire.
    val fullRateGen = for {
      cfg   <- cfgGen.map(_.copy(slack = 0))
      b     <- Gen.choose(0, 9)
      phase <- Gen.choose(0, 29)
      n     <- Gen.choose(2, 4) // back-to-back train length
    } yield (cfg, b % cfg.nBanks, phase % (cfg.nBanks * cfg.n), n)

    forAll(fullRateGen) { case (cfg, b, phase, n) =>
      test(new TdmLatencyHarness(cfg.nBanks, cfg.n, cfg.d, cfg.T, config)) { dut =>
        (0 until cfg.nBanks).foreach(i => dut.io.in(i).poke(emptyPkt(dut)))
        dut.clock.step()
        val period = cfg.nBanks * cfg.n
        while ((dut.io.cyc.peek().litValue % period) != phase) dut.clock.step()
        val t0 = dut.io.cyc.peek().litValue
        // inject n packets on the SAME bank, exactly one period apart (max legal rate)
        val expected = (0 until n).map { k =>
          (b, predictedDelivery(t0 + k * period, b, cfg), (0x4000 + k) & 0xffff)
        }
        val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt, Int)]
        var k = 0
        while (dut.io.cyc.peek().litValue <= expected.last._2 + 1) {
          val cyc = dut.io.cyc.peek().litValue
          if (k < n && cyc == t0 + BigInt(k) * period) {
            dut.io.in(b).poke(mkPkt(dut, (0x4000 + k) & 0xffff)); k += 1
          } else dut.io.in(b).poke(emptyPkt(dut))
          for (i <- 0 until dut.nBanks)
            if (dut.io.out(i).valid.peek().litToBoolean)
              seen += ((i, cyc, dut.io.out(i).data.peek().litValue.toInt))
          dut.clock.step()
        }
        withClue(s"$cfg bank=$b phase=$phase train=$n: ") {
          seen.toSeq should contain theSameElementsAs expected
        }
        dut.io.overflow.peek().litToBoolean shouldBe false
      }
    }
  }

  it should "keep per-bank timing exact when several banks are injected in the same cycle" in {
    val simGen = for {
      cfg   <- cfgGen
      banks <- Gen.someOf(0 until cfg.nBanks).suchThat(_.size >= 2)
      phase <- Gen.choose(0, cfg.nBanks * cfg.n - 1)
    } yield (cfg, banks.toSeq.sorted, phase)

    forAll(simGen) { case (cfg, banks, phase) =>
      test(new TdmLatencyHarness(cfg.nBanks, cfg.n, cfg.d, cfg.T, config)) { dut =>
        (0 until cfg.nBanks).foreach(i => dut.io.in(i).poke(emptyPkt(dut)))
        dut.clock.step()
        val period = cfg.nBanks * cfg.n
        while ((dut.io.cyc.peek().litValue % period) != phase) dut.clock.step()
        val t = dut.io.cyc.peek().litValue
        val expected = banks.map(b => (b, predictedDelivery(t, b, cfg), (0x3000 + b) & 0xffff))
        banks.foreach(b => dut.io.in(b).poke(mkPkt(dut, (0x3000 + b) & 0xffff)))
        dut.clock.step()
        banks.foreach(b => dut.io.in(b).poke(emptyPkt(dut)))
        val seen = collect(dut, expected.map(_._2).max + 1)
        withClue(s"$cfg banks=$banks phase=$phase t=$t: ") {
          seen should contain theSameElementsAs expected
        }
        dut.io.overflow.peek().litToBoolean shouldBe false
      }
    }
  }
}
