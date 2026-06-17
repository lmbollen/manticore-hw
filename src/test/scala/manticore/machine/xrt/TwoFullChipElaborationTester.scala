package manticore.machine.xrt

import chisel3.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Elaboration smoke test for [[TwoFullChipSimKernel]]: confirms two FULL
  * ManticoreFlatArray instances connected by the X-seam build into valid RTL
  * (no combinational loops, no width mismatches). Does not run a program — that
  * is the bootstrap tester. Pure Chisel elaboration (no Verilator), so it is fast.
  */
class TwoFullChipElaborationTester extends AnyFlatSpec with Matchers {
  behavior of "TwoFullChipSimKernel"

  it should "elaborate to SystemVerilog (two full instances + X seam)" in {
    val sv = new ChiselStage().emitVerilog(
      new TwoFullChipSimKernel(gDimX = 8, gDimY = 4, aDimX = 4, enable_custom_alu = false)
    )
    sv should include("module TwoFullChipSimKernel")
    // both full instances present
    sv.sliding("ManticoreFlatArray".length).count(_ == "ManticoreFlatArray") should be >= 2
  }
}
