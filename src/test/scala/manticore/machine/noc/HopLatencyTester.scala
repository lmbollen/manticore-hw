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

/** Measures end-to-end NoC delivery latency for a single packet routed in each
  * of the four directions (forward/backward X and Y) plus forward/backward
  * turns. If bidirectional routing is correct, a backward (-1) hop must have
  * EXACTLY the same latency as the corresponding forward (+1) hop — any
  * asymmetry desyncs the compiler's schedule.
  */
class HopLatencyTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val DIMX = 4
  val DIMY = 4
  val config = ManticoreBaseISA
  val NHOP = 1

  def pkt(x: Int, y: Int): NoCBundle =
    NoCBundle(DIMX, DIMY, config).Lit(
      _.data -> 0xABCD.U, _.address -> 0x7.U, _.valid -> true.B,
      _.xHops -> x.S, _.yHops -> y.S
    )
  def emptyPkt: NoCBundle = SwitchTestUtils.emptyPacket(DIMX, DIMY, config)

  /** inject `p` at source (sx,sy), return #cycles until (tx,ty) terminal fires (max 64) */
  def latency(dut: BareNoC, sx: Int, sy: Int, tx: Int, ty: Int, p: NoCBundle): Int = {
    dut.io.configEnable.poke(false.B)
    for (x <- 0 until DIMX; y <- 0 until DIMY) dut.io.corePacketInput(x)(y).poke(emptyPkt)
    dut.io.corePacketInput(sx)(sy).poke(p)
    dut.clock.step()
    dut.io.corePacketInput(sx)(sy).poke(emptyPkt)
    var c = 1
    while (c < 64 && !dut.io.corePacketOutput(tx)(ty).valid.peek().litToBoolean) {
      dut.clock.step(); c += 1
    }
    c
  }

  behavior of "NoC hop latency"

  it should "deliver forward and backward 1-hop packets with equal latency" taggedAs RequiresVerilator in {
    test(new BareNoC(DIMX, DIMY, config, n_hop = NHOP))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        // 1-hop each direction (target is exactly 1 switch away)
        val east  = latency(dut, 0, 0, 1, 0, pkt(+1, 0)) // +X
        val west  = latency(dut, 1, 0, 0, 0, pkt(-1, 0)) // -X
        val north = latency(dut, 0, 0, 0, 1, pkt(0, +1)) // +Y
        val south = latency(dut, 0, 1, 0, 0, pkt(0, -1)) // -Y
        // forward vs backward turn (X then Y)
        val fwdTurn = latency(dut, 0, 0, 1, 1, pkt(+1, +1))
        val bwdTurn = latency(dut, 1, 1, 0, 0, pkt(-1, -1))
        info(s"latencies: east=$east west=$west north=$north south=$south fwdTurn=$fwdTurn bwdTurn=$bwdTurn")
        east shouldBe west
        north shouldBe south
        fwdTurn shouldBe bwdTurn
      }
  }
}
