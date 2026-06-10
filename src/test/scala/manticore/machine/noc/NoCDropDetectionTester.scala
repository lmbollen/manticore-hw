package manticore.machine.noc

import Chisel._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ManticoreBaseISA
import manticore.machine.TestsCommon.RequiresVerilator
import manticore.machine.core.BareNoC
import manticore.machine.core.NoCBundle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end dropped-/misrouted-frame detection for the bidirectional NoC.
  *
  * For every ordered (source, target) pair on the torus we inject a single packet (routed
  * by signed min-|hops|, so this exercises BOTH forward (+) and backward (-) hops in X and
  * Y), let the network drain, and require that the frame is delivered EXACTLY once, at the
  * intended target, with the correct payload, and NOWHERE else. A silently dropped or
  * misrouted frame therefore fails the test with the exact (source→target) that was lost —
  * which is precisely the failure mode we could not previously notice.
  */
class NoCDropDetectionTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val DIMX = 4
  val DIMY = 4
  val config = ManticoreBaseISA

  // signed shortest-path hop count, identical to the compiler's HardwareConfig.xHops/yHops
  def signedHops(s: Int, t: Int, dim: Int): Int = {
    val fwd = if (s <= t) t - s else dim - s + t
    val bwd = dim - fwd
    if (fwd <= bwd) fwd else -bwd
  }

  def emptyPkt: NoCBundle = SwitchTestUtils.emptyPacket(DIMX, DIMY, config)
  def mkPkt(data: Int, addr: Int, xHops: Int, yHops: Int): NoCBundle =
    NoCBundle(DIMX, DIMY, config).Lit(
      _.data -> data.U, _.address -> addr.U, _.valid -> true.B,
      _.xHops -> xHops.S, _.yHops -> yHops.S
    )

  def allEmpty(dut: BareNoC): Unit =
    for (x <- 0 until DIMX; y <- 0 until DIMY) dut.io.corePacketInput(x)(y).poke(emptyPkt)

  it should "deliver every frame exactly once (no drops/misroutes) across all source/target pairs" taggedAs RequiresVerilator in {
    test(new BareNoC(DIMX, DIMY, config, n_hop = 1))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.configEnable.poke(false.B)
        allEmpty(dut)
        dut.clock.step(2)

        val maxCycles = 4 * (DIMX + DIMY) // generous single-packet, no-congestion latency bound
        var injected  = 0
        val problems  = scala.collection.mutable.ArrayBuffer.empty[String]

        for (sx <- 0 until DIMX; sy <- 0 until DIMY;
             tx <- 0 until DIMX; ty <- 0 until DIMY if !(sx == tx && sy == ty)) {

          val xh   = signedHops(sx, tx, DIMX)
          val yh   = signedHops(sy, ty, DIMY)
          val data = ((sx * DIMY + sy) * 251 + (tx * DIMY + ty)) & 0xffff // unique-ish payload
          val addr = (tx * DIMY + ty) & 0x3f
          injected += 1

          allEmpty(dut)
          dut.io.corePacketInput(sx)(sy).poke(mkPkt(data, addr, xh, yh))
          dut.clock.step()
          allEmpty(dut)

          var arrived   = false
          var misroute  = false
          var cyc       = 0
          while (cyc < maxCycles && !arrived && !misroute) {
            // scan all terminals this cycle
            for (x <- 0 until DIMX; y <- 0 until DIMY) {
              if (dut.io.corePacketOutput(x)(y).valid.peek().litToBoolean) {
                if (x == tx && y == ty) {
                  val got = dut.io.corePacketOutput(x)(y).data.peek().litValue.toInt & 0xffff
                  if (got != data)
                    problems += s"($sx,$sy)->($tx,$ty) xh=$xh yh=$yh: wrong data $got != $data"
                  arrived = true
                } else {
                  problems += s"($sx,$sy)->($tx,$ty) xh=$xh yh=$yh: MISROUTED to ($x,$y)"
                  misroute = true
                }
              }
            }
            dut.clock.step()
            cyc += 1
          }
          if (!arrived && !misroute)
            problems += s"($sx,$sy)->($tx,$ty) xh=$xh yh=$yh: DROPPED (no delivery within $maxCycles cycles)"

          dut.clock.step(2) // drain before next injection
        }

        info(s"injected $injected frames; ${problems.size} problem(s)")
        problems.take(20).foreach(p => info(s"  $p"))
        assert(problems.isEmpty, s"NoC dropped/misrouted/corrupted ${problems.size} of $injected frames")
      }
  }

  // Negative control: deliberately force a drop and prove the end-to-end accounting NOTICES it.
  // Two 1-hop packets are injected on the same cycle, both terminating at (0,0)'s shared y_reg:
  //   A: (1,0) -> (0,0) westbound (xHops=-1) arrives via xNegInput
  //   B: (0,1) -> (0,0) southbound (yHops=-1) arrives via yNegInput
  // Both reach (0,0)'s terminal register on the same cycle; the switch priority keeps the
  // higher-priority xNegInput (A) and silently DROPS B. If our detection (count delivered vs
  // injected) is sound, exactly one of the two payloads is delivered and B is reported missing.
  it should "detect a deliberately-forced collision drop" taggedAs RequiresVerilator in {
    test(new BareNoC(DIMX, DIMY, config, n_hop = 1))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.configEnable.poke(false.B)
        allEmpty(dut)
        dut.clock.step(2)

        val dataA = 0xAAAA // (1,0) -> (0,0), westbound
        val dataB = 0xBBBB // (0,1) -> (0,0), southbound
        allEmpty(dut)
        dut.io.corePacketInput(1)(0).poke(mkPkt(dataA, 0x1, -1, 0))
        dut.io.corePacketInput(0)(1).poke(mkPkt(dataB, 0x2, 0, -1))
        dut.clock.step()
        allEmpty(dut)

        // collect every payload delivered to (0,0)
        val deliveredAt00 = scala.collection.mutable.ArrayBuffer.empty[Int]
        for (_ <- 0 until 4 * (DIMX + DIMY)) {
          if (dut.io.corePacketOutput(0)(0).valid.peek().litToBoolean)
            deliveredAt00 += (dut.io.corePacketOutput(0)(0).data.peek().litValue.toInt & 0xffff)
          dut.clock.step()
        }

        val injectedSet = Set(dataA, dataB)
        val deliveredSet = deliveredAt00.toSet
        val dropped      = injectedSet -- deliveredSet
        info(s"forced collision at (0,0): injected ${injectedSet.map(_.toHexString)}, " +
          s"delivered ${deliveredSet.map(_.toHexString)}, detected-dropped ${dropped.map(_.toHexString)}")

        // The whole point: a real drop happened and our accounting caught it.
        assert(deliveredAt00.size == 1,
          s"expected exactly one frame to survive the collision, saw ${deliveredAt00.size}")
        assert(dropped.nonEmpty,
          "drop-detection FAILED: a frame was dropped by the collision but the accounting did not notice")
        assert(dropped == Set(dataB),
          s"expected the lower-priority southbound frame (B) to be the dropped one, got $dropped")
      }
  }

  // Bidirectionality under concurrency: the torus rings are BIDIRECTIONAL — each direction
  // has its own physical channel (x_reg/x_neg_reg, y_reg/y_neg_reg), so opposite-direction
  // traffic on the SAME ring in the SAME cycles must not interfere. Inject four packets in
  // one cycle: east+west sharing row 1, north+south sharing column 0, all with distinct
  // terminal switches. All four must be delivered exactly once at latency hops+1.
  it should "carry simultaneous opposite-direction traffic on shared rings without interference" taggedAs RequiresVerilator in {
    test(new BareNoC(DIMX, DIMY, config, n_hop = 1))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.configEnable.poke(false.B)
        allEmpty(dut)
        dut.clock.step(2)

        // (data, src, dst, xh, yh, expected latency = |xh|+|yh|+1)
        case class T(data: Int, sx: Int, sy: Int, tx: Int, ty: Int, xh: Int, yh: Int) {
          val lat = math.abs(xh) + math.abs(yh) + 1
        }
        // NOTE on the pattern: opposite-direction TRANSIT uses disjoint registers
        // (x_reg vs x_neg_reg, y_reg vs y_neg_reg) and can never interfere; only
        // TERMINAL delivery shares y_reg with northbound transit (a known, compiler-
        // arbitrated contention — scheduled traffic never collides there). The pattern
        // below keeps all terminals on distinct switches and clear of transits.
        val traffic = Seq(
          T(0xE0E0, 0, 1, 2, 1, +2, 0), // eastbound  on row 1
          T(0x3030, 3, 1, 1, 1, -2, 0), // westbound  on row 1   (opposite direction, same ring)
          T(0x4040, 0, 0, 0, 2, 0, +2), // northbound on col 0
          T(0x5050, 0, 3, 0, 1, 0, -2)  // southbound on col 0   (opposite direction, same ring)
        )
        allEmpty(dut)
        traffic.foreach(t => dut.io.corePacketInput(t.sx)(t.sy).poke(mkPkt(t.data, 0x3, t.xh, t.yh)))
        dut.clock.step()
        allEmpty(dut)

        val delivered = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int, Int)] // (x,y,data,cycle)
        var c = 1
        while (c <= 8) {
          for (x <- 0 until DIMX; y <- 0 until DIMY) {
            if (dut.io.corePacketOutput(x)(y).valid.peek().litToBoolean)
              delivered += ((x, y, dut.io.corePacketOutput(x)(y).data.peek().litValue.toInt & 0xffff, c))
          }
          dut.clock.step(); c += 1
        }

        info(s"simultaneous bidirectional traffic: delivered ${delivered.map(d => f"(${d._1},${d._2})=0x${d._3}%04x@${d._4}").mkString(", ")}")
        assert(delivered.size == traffic.size, s"expected ${traffic.size} deliveries, saw ${delivered.size}")
        traffic.foreach { t =>
          val hits = delivered.filter(d => d._1 == t.tx && d._2 == t.ty && d._3 == t.data)
          assert(hits.size == 1, s"packet 0x${t.data.toHexString} to (${t.tx},${t.ty}) delivered ${hits.size} times")
          assert(hits.head._4 == t.lat, s"packet 0x${t.data.toHexString}: latency ${hits.head._4} != ${t.lat}")
        }
      }
  }
}
