package manticore.machine.noc

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ManticoreBaseISA
import manticore.machine.TestsCommon.RequiresVerilator
import manticore.machine.core.BareNoC
import manticore.machine.core.NoCBundle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DIAGNOSTIC: systematically measure the ACTUAL RTL NoC latency (injection at a source PE's
  * lInput to terminal-valid at the destination PE) across MANY route geometries on the 8x4
  * grid the loop_multi/SIG benchmark uses, at n_hop=1. Flags every route whose latency
  * deviates from the naive H+1 model, to find the geometry the compiler's NetworkOnChip
  * latency model mis-predicts (which schedules a recv one cycle off -> the SIG freeze; the
  * interpreter masks it by delivering at the vcycle boundary).
  */
class NoCLatencyProbe extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  val config = ManticoreBaseISA

  def m(v: Int, n: Int): Int = ((v % n) + n) % n

  def measure(dut: BareNoC, sx: Int, sy: Int, xh: Int, yh: Int): Int = {
    val DimX = dut.io.corePacketInput.length
    val DimY = dut.io.corePacketInput.head.length
    val tx = m(sx + xh, DimX)
    val ty = m(sy + yh, DimY)
    val pkt = new NoCBundle(DimX, DimY, config).Lit(
      _.data -> 0xbeef.U, _.address -> 0x5.U, _.valid -> true.B, _.xHops -> xh.S, _.yHops -> yh.S
    )
    val empty = new NoCBundle(DimX, DimY, config).Lit(
      _.data -> 0.U, _.address -> 0.U, _.valid -> false.B, _.xHops -> 0.S, _.yHops -> 0.S
    )
    dut.io.corePacketInput(sx)(sy).poke(pkt)
    dut.clock.step()
    dut.io.corePacketInput(sx)(sy).poke(empty)
    var c = 1
    while (c < 128 && !dut.io.corePacketOutput(tx)(ty).valid.peek().litToBoolean) { dut.clock.step(); c += 1 }
    // settle a few cycles so a stale poke doesn't bleed into the next measurement
    dut.clock.step(4)
    c
  }

  behavior of "NoC latency sweep (n_hop=1, 8x4)"

  it should "flag geometries whose latency deviates from H+1" taggedAs RequiresVerilator in {
    test(new BareNoC(8, 4, ManticoreBaseISA, n_hop = 1))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.configEnable.poke(false.B)
        val DimX = 8; val DimY = 4
        val deviations = scala.collection.mutable.ArrayBuffer.empty[String]
        val misroutes  = scala.collection.mutable.ArrayBuffer.empty[String]
        // SIGNED hops: forward (+) and backward (-) in both dims. xHops range -(DimX-1)..(DimX-1).
        for (sx <- Seq(0, 3, 4, 7); sy <- Seq(0, 1, 2, 3);
             xh <- -(DimX - 1) until DimX; yh <- -(DimY - 1) until DimY if (xh != 0 || yh != 0)) {
          val tx = m(sx + xh, DimX); val ty = m(sy + yh, DimY)
          val lat = measure(dut, sx, sy, xh, yh)
          val model = math.abs(xh) + math.abs(yh) + 1
          if (lat >= 128) {
            misroutes += f"src($sx,$sy) xh=$xh yh=$yh : NEVER ARRIVED at dst($tx,$ty) (misroute/drop)"
          } else if (lat != model) {
            deviations += f"src($sx,$sy)->dst($tx,$ty) xh=$xh yh=$yh : actual=$lat model=$model diff=${lat - model}"
          }
        }
        info(s"=== ${misroutes.size} MISROUTES (never arrived), ${deviations.size} latency deviations ===")
        misroutes.take(40).foreach(d => info("MISROUTE " + d))
        deviations.take(40).foreach(d => info("LATDEV   " + d))
      }
  }
}
