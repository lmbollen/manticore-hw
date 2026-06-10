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
  * Contract: for a configuration (nBanks, cyclesPerSlot N, transceiver latency D), a
  * packet injected on bank b during cycle t is delivered on the receiver's output
  * bank b during EXACTLY
  *
  *     deliver(t, b) = nextSlotStart_b(t + 1) + D + 1
  *     where bank b's slot starts are the cycles s ≡ b*N (mod nBanks*N)
  *
  * (+1 capture register, the free-running slot wait, D wire cycles, +1 demux output
  * register). The latency is a pure function of the arrival cycle's phase — never of
  * data or other traffic — which is what lets the compiler schedule across TDM'd
  * chip-to-chip links statically.
  *
  * Property 1 sweeps randomly generated configurations *of multiple sizes* with
  * random (bank, phase) injection sequences — including repeated phases, which
  * checks run-to-run repeatability — and requires exact delivery cycles.
  * Property 2 injects a random SUBSET of banks in the same cycle and requires each
  * to meet its own contract exactly (sharing the single wire must not perturb
  * timing — independence under contention).
  */
class TdmLatencyHarness(val nBanks: Int, val cyclesPerSlot: Int, val D: Int, config: ISA)
    extends Module {
  val DimX = 8; val DimY = 4 // packet payload dims (irrelevant to the TDM logic)
  val io = IO(new Bundle {
    val in       = Input(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
    val out      = Output(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
    val overflow = Output(Bool())
    val cyc      = Output(UInt(32.W))
  })
  val mux   = Module(new TdmLinkMux(nBanks, cyclesPerSlot, DimX, DimY, config))
  val demux = Module(new TdmLinkDemux(nBanks, DimX, DimY, config))
  mux.io.in          := io.in
  mux.io.connected   := true.B
  demux.io.connected := true.B
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

  case class Cfg(nBanks: Int, n: Int, d: Int)
  val cfgGen: Gen[Cfg] = for {
    nBanks <- Gen.choose(2, 10)
    n      <- Gen.choose(1, 3)
    d      <- Gen.choose(1, 8)
  } yield Cfg(nBanks, n, d)

  /** the contract oracle */
  def predictedDelivery(t: BigInt, b: Int, cfg: Cfg): BigInt = {
    val period = BigInt(cfg.nBanks * cfg.n)
    val target = BigInt(b * cfg.n)
    val u      = t + 1
    u + ((target - u) % period + period) % period + cfg.d + 1
  }

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
      test(new TdmLatencyHarness(cfg.nBanks, cfg.n, cfg.d, config)) { dut =>
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

  it should "keep per-bank timing exact when several banks are injected in the same cycle" in {
    val simGen = for {
      cfg   <- cfgGen
      banks <- Gen.someOf(0 until cfg.nBanks).suchThat(_.size >= 2)
      phase <- Gen.choose(0, cfg.nBanks * cfg.n - 1)
    } yield (cfg, banks.toSeq.sorted, phase)

    forAll(simGen) { case (cfg, banks, phase) =>
      test(new TdmLatencyHarness(cfg.nBanks, cfg.n, cfg.d, config)) { dut =>
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
