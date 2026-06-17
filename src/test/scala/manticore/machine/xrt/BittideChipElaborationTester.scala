package manticore.machine.xrt

import chisel3.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Elaboration smoke test for [[ManticoreBittideChip]]: the per-FPGA bittide chip
  * top. Confirms both the single-chip (milestone 1, no seams) and the multi-chip
  * (a 4x4 sub-array of an 8x16 torus, stall-wave + per-chip boot + four TDM seam
  * edges) configurations build into valid RTL. Pure Chisel elaboration (no
  * Verilator), so it is fast and structural only.
  */
class BittideChipElaborationTester extends AnyFlatSpec with Matchers {
  behavior of "ManticoreBittideChip"

  it should "elaborate the single-chip config (no seams) — milestone 1 unchanged" in {
    val sv = new ChiselStage().emitVerilog(
      new ManticoreBittideChip(DimX = 2, DimY = 2, enable_custom_alu = false)
    )
    sv should include("module ManticoreBittideChip")
    // no multi-chip mode -> no seam ports
    sv should not include "seam_east_tx"
  }

  it should "elaborate the multi-chip config (4x4 in 8x16, stall-wave, four TDM seams)" in {
    val sv = new ChiselStage().emitVerilog(
      new ManticoreBittideChip(
        DimX = 4,
        DimY = 4,
        enable_custom_alu = false,
        torusDimX = 8,
        torusDimY = 16,
        stallWave = true
      )
    )
    sv should include("module ManticoreBittideChip")
    // one TDM seam per edge, exposed as flat TdmFrame tx/rx pins
    for (edge <- Seq("east", "west", "north", "south")) {
      sv should include(s"seam_${edge}_tx")
      sv should include(s"seam_${edge}_rx")
      sv should include(s"seam_${edge}_extend")
    }
    // the four bridges are present
    sv.sliding("TdmTorusBoundaryBridge".length).count(_ == "TdmTorusBoundaryBridge") should be >= 4
  }
}
