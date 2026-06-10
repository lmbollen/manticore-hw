package manticore.machine.noc

import Chisel._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ISA
import manticore.machine.ManticoreBaseISA
import manticore.machine.TestsCommon.RequiresVerilator
import manticore.machine.core.BareNoC
import manticore.machine.core.NoCBundle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Two BareNoC instances (each DimX x DimY locally) with BOTH dimensions' torus
  * boundaries cross-connected to each other (fwd<->fwd, bwd<->bwd per dimension).
  * Hop fields are sized for the largest reachable pairing (2*DimX x 2*DimY), so a
  * single build supports every mode, selected purely by the two booleans:
  *
  *   extendX=0, extendY=0 : two independent DimX x DimY tori (loops closed locally)
  *   extendX=1, extendY=0 : one (2*DimX) x DimY torus   (chips side by side in X)
  *   extendX=0, extendY=1 : one DimX x (2*DimY) torus   (chips stacked in Y)
  *
  * Global coordinates: X-paired -> gx = chip*DimX + lx; Y-paired -> gy = chip*DimY + ly.
  * The constant-latency chip-to-chip link is a direct wire here (latency matching is
  * handled outside this component in the real system).
  */
class TwoChipHarness(val DimX: Int, val DimY: Int, config: ISA) extends Module {
  val TX = 2 * DimX
  val TY = 2 * DimY
  val io = IO(new Bundle {
    val extendX = Input(Bool())
    val extendY = Input(Bool())
    val in      = Input(Vec(2, Vec(DimX, Vec(DimY, new NoCBundle(TX, TY, config)))))
    val out     = Output(Vec(2, Vec(DimX, Vec(DimY, new NoCBundle(TX, TY, config)))))
  })

  val chips = Seq.fill(2)(Module(new BareNoC(DimX, DimY, config, n_hop = 1, torusDimX = TX, torusDimY = TY)))
  chips.zipWithIndex.foreach { case (c, i) =>
    c.io.extendX      := io.extendX
    c.io.extendY      := io.extendY
    c.io.configEnable := false.B
    c.io.configPacket := NoCBundle.empty(TX, TY, config)
    c.io.corePacketInput := io.in(i)
    io.out(i) := c.io.corePacketOutput
  }

  // X-dimension cross links (used when extendX): each chip's egress -> the other's ingress
  chips(0).io.xFwdIn := chips(1).io.xFwdOut
  chips(1).io.xFwdIn := chips(0).io.xFwdOut
  chips(0).io.xBwdIn := chips(1).io.xBwdOut
  chips(1).io.xBwdIn := chips(0).io.xBwdOut

  // Y-dimension cross links (used when extendY)
  chips(0).io.yFwdIn := chips(1).io.yFwdOut
  chips(1).io.yFwdIn := chips(0).io.yFwdOut
  chips(0).io.yBwdIn := chips(1).io.yBwdOut
  chips(1).io.yBwdIn := chips(0).io.yBwdOut
}

class TorusExtensionTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val config = ManticoreBaseISA

  // signed min-|hops| on a torus of size dim, ties broken forward (compiler convention)
  def signedHops(s: Int, t: Int, dim: Int): Int = {
    val fwd = if (s <= t) t - s else dim - s + t
    val bwd = dim - fwd
    if (fwd <= bwd) fwd else -bwd
  }

  def emptyPkt(dut: TwoChipHarness): NoCBundle =
    NoCBundle(dut.TX, dut.TY, config).Lit(
      _.data -> 0.U, _.address -> 0.U, _.valid -> false.B, _.xHops -> 0.S, _.yHops -> 0.S)

  def mkPkt(dut: TwoChipHarness, data: Int, xh: Int, yh: Int): NoCBundle =
    NoCBundle(dut.TX, dut.TY, config).Lit(
      _.data -> data.U, _.address -> 0x5.U, _.valid -> true.B, _.xHops -> xh.S, _.yHops -> yh.S)

  def allEmpty(dut: TwoChipHarness): Unit =
    for (c <- 0 until 2; x <- 0 until dut.DimX; y <- 0 until dut.DimY)
      dut.io.in(c)(x)(y).poke(emptyPkt(dut))

  /** inject at (chip cs, lx, ly); require delivery EXACTLY at (cd, tx, ty) with
    * latency == |xh|+|yh|+1 and no delivery anywhere else */
  def sendAndCheck(dut: TwoChipHarness, cs: Int, lx: Int, ly: Int, cd: Int, tx: Int, ty: Int,
                   xh: Int, yh: Int, data: Int): Seq[String] = {
    val problems = scala.collection.mutable.ArrayBuffer.empty[String]
    val expLat   = math.abs(xh) + math.abs(yh) + 1
    allEmpty(dut)
    dut.io.in(cs)(lx)(ly).poke(mkPkt(dut, data, xh, yh))
    dut.clock.step()
    allEmpty(dut)
    var c       = 1
    var arrived = false
    while (c <= expLat + 4 && !arrived) {
      for (cc <- 0 until 2; x <- 0 until dut.DimX; y <- 0 until dut.DimY) {
        if (dut.io.out(cc)(x)(y).valid.peek().litToBoolean) {
          if (cc == cd && x == tx && y == ty) {
            val got = dut.io.out(cc)(x)(y).data.peek().litValue.toInt
            if (got != data) problems += s"src(c$cs,$lx,$ly)->dst(c$cd,$tx,$ty) hops($xh,$yh): data $got != $data"
            if (c != expLat) problems += s"src(c$cs,$lx,$ly)->dst(c$cd,$tx,$ty) hops($xh,$yh): latency $c != $expLat"
            arrived = true
          } else {
            problems += s"src(c$cs,$lx,$ly)->dst(c$cd,$tx,$ty) hops($xh,$yh): MISROUTED to (c$cc,$x,$y)"
            arrived = true
          }
        }
      }
      dut.clock.step(); c += 1
    }
    if (!arrived) problems += s"src(c$cs,$lx,$ly)->dst(c$cd,$tx,$ty) hops($xh,$yh): DROPPED"
    dut.clock.step(2) // drain
    problems.toSeq
  }

  behavior of "Torus extension (two chained 2x2 instances)"

  it should "act as two independent 2x2 tori, one 4x2 torus, or one 2x4 torus selected by the booleans" taggedAs RequiresVerilator in {
    test(new TwoChipHarness(2, 2, config)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val D = 2
      val problems = scala.collection.mutable.ArrayBuffer.empty[String]

      // ---- mode 1: both loops closed -> two independent 2x2 tori ----
      dut.io.extendX.poke(false.B); dut.io.extendY.poke(false.B)
      allEmpty(dut); dut.clock.step(2)
      var n = 0
      for (chip <- 0 until 2; sx <- 0 until D; sy <- 0 until D;
           tx <- 0 until D; ty <- 0 until D if (sx, sy) != (tx, ty)) {
        n += 1
        problems ++= sendAndCheck(dut, chip, sx, sy, chip, tx, ty,
          signedHops(sx, tx, D), signedHops(sy, ty, D), (n * 7 + 3) & 0xffff)
      }
      info(s"standalone 2x(2x2): $n transfers, ${problems.size} problem(s)")

      // ---- mode 2: extendX -> one 4x2 torus ----
      dut.io.extendX.poke(true.B); dut.io.extendY.poke(false.B)
      allEmpty(dut); dut.clock.step(2)
      var n2 = 0
      for (gsx <- 0 until 2 * D; sy <- 0 until D; gtx <- 0 until 2 * D; ty <- 0 until D
           if (gsx, sy) != (gtx, ty)) {
        n2 += 1
        problems ++= sendAndCheck(dut,
          gsx / D, gsx % D, sy, gtx / D, gtx % D, ty,
          signedHops(gsx, gtx, 2 * D), signedHops(sy, ty, D), (n2 * 11 + 5) & 0xffff)
      }
      info(s"extendX 4x2: $n2 transfers, ${problems.size} problem(s) cumulative")

      // ---- mode 3: extendY -> one 2x4 torus ----
      dut.io.extendX.poke(false.B); dut.io.extendY.poke(true.B)
      allEmpty(dut); dut.clock.step(2)
      var n3 = 0
      for (sx <- 0 until D; gsy <- 0 until 2 * D; tx <- 0 until D; gty <- 0 until 2 * D
           if (sx, gsy) != (tx, gty)) {
        n3 += 1
        problems ++= sendAndCheck(dut,
          gsy / D, sx, gsy % D, gty / D, tx, gty % D,
          signedHops(sx, tx, D), signedHops(gsy, gty, 2 * D), (n3 * 13 + 9) & 0xffff)
      }
      info(s"extendY 2x4: $n3 transfers, ${problems.size} problem(s) cumulative")

      problems.take(12).foreach(p => info(s"  $p"))
      assert(problems.isEmpty, s"${problems.size} problems across the three modes")
    }
  }

  behavior of "Torus extension (two chained 4x4 instances)"

  it should "form one 8x4 torus from two 4x4 instances (extendX)" taggedAs RequiresVerilator in {
    test(new TwoChipHarness(4, 4, config)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val D = 4
      dut.io.extendX.poke(true.B); dut.io.extendY.poke(false.B)
      allEmpty(dut); dut.clock.step(2)
      val problems = scala.collection.mutable.ArrayBuffer.empty[String]
      var n = 0
      for (gsx <- 0 until 2 * D; sy <- 0 until D; gtx <- 0 until 2 * D; ty <- 0 until D
           if (gsx, sy) != (gtx, ty)) {
        n += 1
        problems ++= sendAndCheck(dut,
          gsx / D, gsx % D, sy, gtx / D, gtx % D, ty,
          signedHops(gsx, gtx, 2 * D), signedHops(sy, ty, D), (n * 11 + 5) & 0xffff)
      }
      info(s"extended 8x4: $n transfers; ${problems.size} problem(s)")
      problems.take(10).foreach(p => info(s"  $p"))
      assert(problems.isEmpty)
    }
  }
}
