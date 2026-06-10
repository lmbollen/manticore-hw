package manticore.machine.noc

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import manticore.machine.ManticoreBaseISA
import manticore.machine.core.NoCBundle
import manticore.machine.core.Switch
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Targeted tests for southbound (-Y) routing, which was never covered by
  * the original SwitchTester (which only generates non-negative hops).
  */
class SwitchSouthboundTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val DIMX = 4
  val DIMY = 4
  val config = ManticoreBaseISA

  def emptyPacket: NoCBundle = SwitchTestUtils.emptyPacket(DIMX, DIMY, config)

  def mkPacket(data: Int, addr: Int, xHops: Int, yHops: Int): NoCBundle =
    NoCBundle(DIMX, DIMY, config).Lit(
      _.data    -> data.U,
      _.address -> addr.U,
      _.valid   -> true.B,
      _.xHops   -> xHops.S,
      _.yHops   -> yHops.S
    )

  behavior of "Switch southbound routing"

  // Test 1: lInput with yHops=-1 → appears on yNegOutput with yHops=0
  it should "forward a lInput packet with yHops=-1 to yNegOutput with yHops=0" in {
    test(new Switch(DIMX, DIMY, config, n_hop = 1)).withAnnotations(Seq()) { dut =>
      val pkt = mkPacket(0xBEEF, 0x7, 0, -1)
      dut.io.lInput.poke(pkt)
      dut.io.xInput.poke(emptyPacket)
      dut.io.xNegInput.poke(emptyPacket)
      dut.io.yInput.poke(emptyPacket)
      dut.io.yNegInput.poke(emptyPacket)
      dut.clock.step()

      // After 1 clock: y_neg_reg should have the packet with yHops incremented toward 0
      dut.io.yNegOutput.valid.expect(true.B, "yNegOutput should be valid")
      dut.io.yNegOutput.data.expect(0xBEEF.U, "yNegOutput data should match")
      dut.io.yNegOutput.address.expect(0x7.U, "yNegOutput address should match")
      dut.io.yNegOutput.yHops.expect(0.S, "yNegOutput yHops should be 0 (moved toward zero from -1)")
      dut.io.terminal.expect(false.B, "terminal should be false (packet is in-transit)")
    }
  }

  // Test 2: yNegInput with yHops=0 → terminal delivery via yOutput, terminal=true
  it should "deliver a yNegInput packet with yHops=0 as terminal via yOutput" in {
    test(new Switch(DIMX, DIMY, config, n_hop = 1)).withAnnotations(Seq()) { dut =>
      val pkt = mkPacket(0xCAFE, 0x5, 0, 0)
      dut.io.lInput.poke(emptyPacket)
      dut.io.xInput.poke(emptyPacket)
      dut.io.xNegInput.poke(emptyPacket)
      dut.io.yInput.poke(emptyPacket)
      dut.io.yNegInput.poke(pkt)
      dut.clock.step()

      // After 1 clock: y_reg should have the terminal packet, terminal_reg should be true
      dut.io.terminal.expect(true.B, "terminal should be true for yNegInput with yHops=0")
      dut.io.yOutput.data.expect(0xCAFE.U, "yOutput data should match")
      dut.io.yOutput.address.expect(0x5.U, "yOutput address should match")
    }
  }

  // Test 3: yNegInput with yHops=-1 → continue south on yNegOutput, terminal=false
  it should "forward a yNegInput packet with yHops=-1 further south via yNegOutput" in {
    test(new Switch(DIMX, DIMY, config, n_hop = 1)).withAnnotations(Seq()) { dut =>
      val pkt = mkPacket(0x1234, 0x3, 0, -1)
      dut.io.lInput.poke(emptyPacket)
      dut.io.xInput.poke(emptyPacket)
      dut.io.xNegInput.poke(emptyPacket)
      dut.io.yInput.poke(emptyPacket)
      dut.io.yNegInput.poke(pkt)
      dut.clock.step()

      // After 1 clock: y_neg_reg should have the packet with yHops incremented (-1→0)
      dut.io.yNegOutput.valid.expect(true.B, "yNegOutput should be valid (continuing south)")
      dut.io.yNegOutput.data.expect(0x1234.U, "yNegOutput data should match")
      dut.io.yNegOutput.yHops.expect(0.S, "yNegOutput yHops should be 0 (moved from -1 toward zero)")
      dut.io.terminal.expect(false.B, "terminal should be false (packet is in-transit south)")
    }
  }

  // Test 4: Full 2-hop southbound journey. Both switches in a column are identical, so we
  // model the two hops on a SINGLE Switch instance: hop 1 routes lInput(yHops=-1) → yNegOutput
  // (yHops=0); we then relay that (known) packet back into yNegInput to model the downstream
  // switch, which delivers it as a terminal. (chiseltest does not support nesting two test()
  // DUT contexts — that was the prior `requirement failed`.)
  it should "deliver a 1-hop southbound packet end-to-end (two switch hops)" in {
    test(new Switch(DIMX, DIMY, config, n_hop = 1)).withAnnotations(Seq()) { dut =>
      val data = 0xDEAD
      val addr = 0xAB

      // Hop 1: lInput with yHops=-1 routes south
      dut.io.lInput.poke(mkPacket(data, addr, 0, -1))
      dut.io.xInput.poke(emptyPacket)
      dut.io.xNegInput.poke(emptyPacket)
      dut.io.yInput.poke(emptyPacket)
      dut.io.yNegInput.poke(emptyPacket)
      dut.clock.step()

      dut.io.yNegOutput.valid.expect(true.B, "yNegOutput should be valid after hop 1")
      dut.io.yNegOutput.yHops.expect(0.S, "yNegOutput yHops should be 0 (moved -1→0)")
      dut.io.terminal.expect(false.B, "not terminal yet (still one hop to go)")

      // Hop 2: relay that packet (data/addr preserved, yHops=0) into the downstream switch's
      // yNegInput — modelled by feeding it back into the same identical Switch.
      dut.io.lInput.poke(emptyPacket)
      dut.io.yNegInput.poke(mkPacket(data, addr, 0, 0))
      dut.clock.step()

      dut.io.terminal.expect(true.B, "terminal should be true for final southbound delivery")
      dut.io.yOutput.data.expect(data.U, "yOutput data should match original")
      dut.io.yOutput.address.expect(addr.U, "yOutput address should match original")
    }
  }
}
