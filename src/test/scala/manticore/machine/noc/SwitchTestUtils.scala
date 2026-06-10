package manticore.machine.noc

import Chisel._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import manticore.machine.ISA
import manticore.machine.core.NoCBundle

object SwitchTestUtils {

  def genData(config: ISA)(implicit rdgen: scala.util.Random): UInt = {
    rdgen.nextInt(1 << config.DataBits).U
  }

  def genAddress(config: ISA)(implicit rdgen: scala.util.Random): UInt = {
    rdgen.nextInt(1 << config.IdBits).U
  }

  // Generates a packet with positive (forward) hops only — used by the existing
  // unidirectional-behaviour tests which still only exercise the positive path.
  def randomPacketXY(DimX: Int, DimY: Int, config: ISA)(implicit rdgen: scala.util.Random): NoCBundle = {
    NoCBundle(DimX, DimY, config).Lit(
      _.data    -> genData(config),
      _.address -> genAddress(config),
      _.valid   -> true.B,
      _.xHops   -> rdgen.nextInt(1 << log2Ceil(DimX)).S,
      _.yHops   -> rdgen.nextInt(1 << log2Ceil(DimY)).S
    )
  }

  def randomPacketY(DimX: Int, DimY: Int, config: ISA)(implicit rdgen: scala.util.Random): NoCBundle = {
    NoCBundle(DimX, DimY, config).Lit(
      _.data    -> genData(config),
      _.address -> genAddress(config),
      _.valid   -> true.B,
      _.xHops   -> 0.S,
      _.yHops   -> (rdgen.nextInt((1 << log2Ceil(DimX)) - 1)).S
    )
  }

  def randomPacketX(DimX: Int, DimY: Int, config: ISA)(implicit rdgen: scala.util.Random): NoCBundle = {
    NoCBundle(DimX, DimY, config).Lit(
      _.data    -> genData(config),
      _.address -> genAddress(config),
      _.valid   -> true.B,
      _.xHops   -> (rdgen.nextInt((1 << log2Ceil(DimY)) - 1)).S,
      _.yHops   -> 0.S
    )
  }

  def emptyPacket(DimX: Int, DimY: Int, config: ISA): NoCBundle = {
    NoCBundle(DimX, DimY, config).Lit(
      _.data    -> 0.U,
      _.address -> 0.U,
      _.valid   -> false.B,
      _.xHops   -> 0.S,
      _.yHops   -> 0.S
    )
  }
}
