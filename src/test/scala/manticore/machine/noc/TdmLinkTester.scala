package manticore.machine.noc

import Chisel._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ISA
import manticore.machine.ManticoreBaseISA
import manticore.machine.TestsCommon.RequiresVerilator
import manticore.machine.core.BareNoC
import manticore.machine.core.NoCBundle
import manticore.machine.core.TdmTorusBoundaryBridge
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Two chips chained in X — but instead of 2*DimY parallel seam wires per direction,
  * each chip edge's logical links are time-division multiplexed onto a SINGLE
  * transceiver link per direction (Z = 1), modeled as a fixed-latency pipe of D
  * cycles. The array (BareNoC + TorusBoundary) is unchanged and unaware of the TDM.
  */
class TdmTwoChipHarness(val DimX: Int, val DimY: Int, config: ISA, val D: Int, val cyclesPerSlot: Int)
    extends Module {
  val GX     = 2 * DimX
  val nBanks = 2 * DimY
  val period = nBanks * cyclesPerSlot // one slot per logical link per period

  val io = IO(new Bundle {
    val extend    = Input(Bool())
    val connected = Input(Bool())
    val in        = Input(Vec(2, Vec(DimX, Vec(DimY, new NoCBundle(GX, DimY, config)))))
    val out       = Output(Vec(2, Vec(DimX, Vec(DimY, new NoCBundle(GX, DimY, config)))))
    val overflow  = Output(Bool()) // sticky: any bank overwrite ever
    val cyc       = Output(UInt(32.W)) // free-running counter, for slot-phase alignment in tests
  })

  val chips   = Seq.fill(2)(Module(new BareNoC(DimX, DimY, config, n_hop = 1, torusDimX = GX)))
  val T = period + D + 2 // constant seam latency (== the --hop-latencies value)
  val bridges = Seq.fill(2)(Module(new TdmTorusBoundaryBridge(DimY, cyclesPerSlot, D, T, GX, DimY, config)))

  chips.zipWithIndex.foreach { case (c, i) =>
    c.io.configEnable := false.B
    c.io.configPacket := NoCBundle.empty(GX, DimY, config)
    c.io.corePacketInput := io.in(i)
    io.out(i) := c.io.corePacketOutput
    // N/S boundaries unused (U-turned): drive ingress empty
    c.io.extendNorth := false.B
    c.io.extendSouth := false.B
    Seq(c.io.north, c.io.south).foreach { s =>
      s.fwdIn.foreach(_ := NoCBundle.empty(GX, DimY, config))
      s.bwdIn.foreach(_ := NoCBundle.empty(GX, DimY, config))
    }
  }
  // per-side chain: chip0 is WEST of chip1; the cable is chip0.east <-> chip1.west,
  // TDM'd through one bridge per chip end. The chain's outer sides stay U-turned.
  chips(0).io.extendEast := io.extend
  chips(1).io.extendWest := io.extend
  chips(0).io.extendWest := false.B
  chips(1).io.extendEast := false.B
  Seq((chips(0).io.east, bridges(0)), (chips(1).io.west, bridges(1))).foreach { case (s, b) =>
    b.io.fwdOut := s.fwdOut
    b.io.bwdOut := s.bwdOut
    s.fwdIn := b.io.fwdIn
    s.bwdIn := b.io.bwdIn
    b.io.connected := io.connected
    b.io.bypass    := false.B
  }
  // unused outer-side ingresses
  Seq(chips(0).io.west, chips(1).io.east).foreach { s =>
    s.fwdIn.foreach(_ := NoCBundle.empty(GX, DimY, config))
    s.bwdIn.foreach(_ := NoCBundle.empty(GX, DimY, config))
  }

  // the transceivers: ONE frame wire per direction, fixed latency D
  bridges(1).io.rx := ShiftRegister(bridges(0).io.tx, D)
  bridges(0).io.rx := ShiftRegister(bridges(1).io.tx, D)

  val sticky = RegInit(false.B)
  when(bridges(0).io.overflow || bridges(1).io.overflow) { sticky := true.B }
  io.overflow := sticky

  val cyc = RegInit(0.U(32.W)); cyc := cyc + 1.U
  io.cyc := cyc
}

class TdmLinkTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val DIMX = 4
  val DIMY = 4
  val GX   = 2 * DIMX
  val D    = 5 // modeled transceiver latency
  val CPS  = 1 // cyclesPerSlot
  val config = ManticoreBaseISA

  def signedHops(s: Int, t: Int, dim: Int): Int = {
    val fwd = if (s <= t) t - s else dim - s + t
    val bwd = dim - fwd
    if (fwd <= bwd) fwd else -bwd
  }

  /** number of seam (cable) crossings on the X leg. Folded chain: the single
    * chip0.east<->chip1.west cable carries the global links l1|l2 (out direction) and
    * l5|l6 (back direction); l3|l4 and l7|l0 are the chips' local U-turns (no cable).
    * East crossings leave x=1 or x=5; west crossings leave x=2 or x=6.
    */
  def crossings(sx: Int, xd: Int): Int = {
    val h = DIMX / 2
    val eastSeam = Set(h - 1, DIMX + h - 1)         // {1, 5} for 4-wide chips
    val westSeam = Set(h, DIMX + h)                 // {2, 6}
    var x = sx; var c = 0
    for (_ <- 0 until math.abs(xd)) {
      if (xd >= 0) { if (eastSeam(x)) c += 1; x = (x + 1) % GX }
      else { if (westSeam(x)) c += 1; x = (x - 1 + GX) % GX }
    }
    c
  }

  /** folded 2-chip chain mapping: global ring index -> (chip, local col) */
  def gmap(g: Int): (Int, Int) = {
    val h = DIMX / 2
    if (g < h) (0, g)
    else if (g < DIMX) (1, g - h)
    else if (g < DIMX + h) (1, g - DIMX + h)
    else (0, g - DIMX)
  }

  def emptyPkt: NoCBundle =
    NoCBundle(GX, DIMY, config).Lit(
      _.data -> 0.U, _.address -> 0.U, _.valid -> false.B, _.xHops -> 0.S, _.yHops -> 0.S)

  def mkPkt(data: Int, xh: Int, yh: Int): NoCBundle =
    NoCBundle(GX, DIMY, config).Lit(
      _.data -> data.U, _.address -> 0x5.U, _.valid -> true.B, _.xHops -> xh.S, _.yHops -> yh.S)

  def allEmpty(dut: TdmTwoChipHarness): Unit =
    for (c <- 0 until 2; x <- 0 until DIMX; y <- 0 until DIMY)
      dut.io.in(c)(x)(y).poke(emptyPkt)

  /** inject and require delivery exactly once at the right node with the right data;
    * non-crossing transfers must have EXACT latency hops+1; each seam crossing now
    * costs EXACTLY dut.T (constant-latency release in the demux). Returns problems. */
  def sendAndCheck(dut: TdmTwoChipHarness, cs: Int, lx: Int, ly: Int, cd: Int, tx: Int, ty: Int,
                   xh: Int, yh: Int, k: Int, data: Int): Seq[String] = {
    val problems = scala.collection.mutable.ArrayBuffer.empty[String]
    val base     = math.abs(xh) + math.abs(yh) + 1
    val exact    = base + k * (dut.T - 1) // each crossing replaces a 1-cycle hop with T
    val bound    = exact
    allEmpty(dut)
    dut.io.in(cs)(lx)(ly).poke(mkPkt(data, xh, yh))
    dut.clock.step()
    allEmpty(dut)
    var c = 1; var arrived = false
    while (c <= bound + 4 && !arrived) {
      for (cc <- 0 until 2; x <- 0 until DIMX; y <- 0 until DIMY) {
        if (dut.io.out(cc)(x)(y).valid.peek().litToBoolean) {
          if (cc == cd && x == tx && y == ty) {
            val got = dut.io.out(cc)(x)(y).data.peek().litValue.toInt
            if (got != data) problems += s"(c$cs,$lx,$ly)->(c$cd,$tx,$ty) hops($xh,$yh): data $got != $data"
            if (c != exact)
              problems += s"(c$cs,$lx,$ly)->(c$cd,$tx,$ty) crossings=$k: latency $c != $exact (constant contract)"
            arrived = true
          } else {
            problems += s"(c$cs,$lx,$ly)->(c$cd,$tx,$ty) hops($xh,$yh): MISROUTED to (c$cc,$x,$y)"
            arrived = true
          }
        }
      }
      dut.clock.step(); c += 1
    }
    if (!arrived) problems += s"(c$cs,$lx,$ly)->(c$cd,$tx,$ty) hops($xh,$yh) crossings=$k: DROPPED"
    dut.clock.step(2)
    problems.toSeq
  }

  behavior of "TDM transceiver bridge (two chips, Z=1 link per direction)"

  it should "deliver the full 8x4 sweep through a single TDM'd transceiver" taggedAs RequiresVerilator in {
    test(new TdmTwoChipHarness(DIMX, DIMY, config, D, CPS))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.extend.poke(true.B); dut.io.connected.poke(true.B)
        allEmpty(dut); dut.clock.step(2)
        val problems = scala.collection.mutable.ArrayBuffer.empty[String]
        var n = 0; var nCross = 0
        for (gsx <- 0 until GX; sy <- 0 until DIMY; gtx <- 0 until GX; ty <- 0 until DIMY
             if (gsx, sy) != (gtx, ty)) {
          val xh = signedHops(gsx, gtx, GX)
          val yh = signedHops(sy, ty, DIMY)
          val k  = crossings(gsx, xh)
          n += 1; if (k > 0) nCross += 1
          val (cs, lx) = gmap(gsx)
          val (cd, tx) = gmap(gtx)
          problems ++= sendAndCheck(dut,
            cs, lx, sy, cd, tx, ty, xh, yh, k, (n * 11 + 5) & 0xffff)
        }
        val ovf = dut.io.overflow.peek().litToBoolean
        info(s"TDM 8x4 sweep: $n transfers ($nCross seam-crossing), ${problems.size} problem(s), overflow=$ovf")
        problems.take(10).foreach(p => info(s"  $p"))
        assert(problems.isEmpty && !ovf)
      }
  }

  it should "carry simultaneous transfers on different logical links (bank arbitration)" taggedAs RequiresVerilator in {
    test(new TdmTwoChipHarness(DIMX, DIMY, config, D, CPS))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.extend.poke(true.B); dut.io.connected.poke(true.B)
        allEmpty(dut); dut.clock.step(2)
        // four packets, one per row, all crossing A->B eastward in the same cycle:
        // four different fwd banks contend for the single transceiver, draining one
        // slot at a time. All must arrive exactly once. Folded layout: a one-hop
        // east crossing leaves chip0 col h-1 (= global l1) into chip1 col 0 (l2).
        val sent = (0 until DIMY).map { y => (y, 0xC000 | y) }
        allEmpty(dut)
        sent.foreach { case (y, d) => dut.io.in(0)(DIMX / 2 - 1)(y).poke(mkPkt(d, +1, 0)) } // (g1,y) -> (g2,y)
        dut.clock.step()
        allEmpty(dut)
        val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
        for (c <- 1 to 2 + dut.period + dut.D + 8) {
          for (x <- 0 until DIMX; y <- 0 until DIMY) {
            if (dut.io.out(1)(x)(y).valid.peek().litToBoolean)
              seen += ((x, y, dut.io.out(1)(x)(y).data.peek().litValue.toInt))
          }
          dut.clock.step()
        }
        info(s"simultaneous banks: delivered ${seen.map(s => f"(${s._1},${s._2})=0x${s._3}%04x").mkString(", ")}, " +
          s"overflow=${dut.io.overflow.peek().litToBoolean}")
        assert(seen.size == DIMY, s"expected ${DIMY} deliveries, saw ${seen.size}")
        sent.foreach { case (y, d) =>
          assert(seen.count(s => s._1 == 0 && s._2 == y && s._3 == d) == 1, s"row $y packet not delivered exactly once")
        }
        assert(!dut.io.overflow.peek().litToBoolean)
      }
  }

  it should "be cycle-deterministic for phase-aligned injections" taggedAs RequiresVerilator in {
    test(new TdmTwoChipHarness(DIMX, DIMY, config, D, CPS))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.extend.poke(true.B); dut.io.connected.poke(true.B)
        allEmpty(dut); dut.clock.step(2)
        def alignedLatency(data: Int): Int = {
          while ((dut.io.cyc.peek().litValue % dut.period) != 0) dut.clock.step() // align to slot phase
          allEmpty(dut)
          dut.io.in(0)(DIMX / 2 - 1)(1).poke(mkPkt(data, +1, 0)) // (g1,1) -> (g2,1): one cable hop
          dut.clock.step(); allEmpty(dut)
          var c = 1
          while (!dut.io.out(1)(0)(1).valid.peek().litToBoolean && c < 64) { dut.clock.step(); c += 1 }
          dut.clock.step(2)
          c
        }
        val l1 = alignedLatency(0xAB01)
        val l2 = alignedLatency(0xAB02)
        info(s"phase-aligned latencies: $l1 and $l2 (period=${dut.period}, D=${dut.D})")
        assert(l1 == l2, "TDM latency must be deterministic for identical slot phases")
      }
  }

  it should "behave as standalone chips when disconnected" taggedAs RequiresVerilator in {
    test(new TdmTwoChipHarness(DIMX, DIMY, config, D, CPS))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.extend.poke(false.B); dut.io.connected.poke(false.B)
        allEmpty(dut); dut.clock.step(2)
        val problems = scala.collection.mutable.ArrayBuffer.empty[String]
        var n = 0
        for (chip <- 0 until 2; sx <- 0 until DIMX; sy <- 0 until DIMY;
             tx <- 0 until DIMX; ty <- 0 until DIMY if (sx, sy) != (tx, ty)) {
          n += 1
          problems ++= sendAndCheck(dut, chip, sx, sy, chip, tx, ty,
            signedHops(sx, tx, DIMX), signedHops(sy, ty, DIMY), 0, (n * 7 + 3) & 0xffff)
        }
        info(s"disconnected/standalone: $n transfers, ${problems.size} problem(s)")
        problems.take(8).foreach(p => info(s"  $p"))
        assert(problems.isEmpty)
      }
  }
}
