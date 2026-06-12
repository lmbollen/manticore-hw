package manticore.machine.core

import Chisel._
import manticore.machine.ISA


class BareNoCInterface(DimX: Int, DimY: Int, config: ISA, bX: Int = 0, bY: Int = 0) extends Bundle {
  // bundle (torus) dims may exceed the local array dims when this instance is
  // part of a larger multi-instance torus (hop fields must span the global torus)
  private val tX = if (bX > 0) bX else DimX
  private val tY = if (bY > 0) bY else DimY
  def makePacketArray(): Vec[Vec[NoCBundle]] = Vec(DimX, Vec(DimY, new NoCBundle(tX, tY, config)))

  val corePacketInput: Vec[Vec[NoCBundle]] = Input(makePacketArray())
  val corePacketOutput: Vec[Vec[NoCBundle]] = Output(makePacketArray())
  val configPacket: NoCBundle = Input(new NoCBundle(tX, tY, config))
  val configEnable: Bool = Input(Bool())

  // Per-SIDE torus extension (see TorusBoundary and ComputeArray): each ring is cut
  // TWICE into two balanced halves (even dims only) — East owns the middle cut
  // (col DimX/2-1 | DimX/2), West the wrap (col DimX-1 | 0); North/South likewise.
  // extend<Side>=false U-turns that side locally (all four false == plain torus);
  // true hands the cut to the neighbouring instance. Chains of chips fold the global
  // ring through each chip, so all cables are neighbour-to-neighbour and the end
  // chips of a chain close their outer sides.
  val extendEast: Bool  = Input(Bool())
  val extendWest: Bool  = Input(Bool())
  val extendNorth: Bool = Input(Bool())
  val extendSouth: Bool = Input(Bool())

  class Side(n: Int) extends Bundle {
    val fwdOut: Vec[NoCBundle] = Output(Vec(n, new NoCBundle(tX, tY, config)))
    val fwdIn: Vec[NoCBundle]  = Input(Vec(n, new NoCBundle(tX, tY, config)))
    val bwdOut: Vec[NoCBundle] = Output(Vec(n, new NoCBundle(tX, tY, config)))
    val bwdIn: Vec[NoCBundle]  = Input(Vec(n, new NoCBundle(tX, tY, config)))
  }
  val east  = new Side(DimY)
  val west  = new Side(DimY)
  val north = new Side(DimX)
  val south = new Side(DimX)
}

/**
 * Bare-bone NoC that only contains an 2D torus of switches
 * @param DimX       local array width (number of switch columns in this instance)
 * @param DimY       local array height
 * @param config
 * @param n_hop      pipeline registers per hop
 * @param torusDimX  GLOBAL torus width for hop-field sizing (0 = DimX, standalone).
 * @param torusDimY  ditto for Y
 */
class BareNoC(DimX: Int, DimY: Int, config: ISA, val n_hop: Int = 2, torusDimX: Int = 0, torusDimY: Int = 0) extends Module {

  private val tX = if (torusDimX > 0) torusDimX else DimX
  private val tY = if (torusDimY > 0) torusDimY else DimY
  require(tX >= DimX && tY >= DimY, "torus dims must be >= local array dims")
  require(DimX % 2 == 0 && DimY % 2 == 0, "per-side seam cuts need even array dimensions")

  val io = IO(new BareNoCInterface(DimX, DimY, config, tX, tY))

  private val h = DimX / 2
  private val v = DimY / 2

  // Switches take dims ONLY for NoCBundle sizing — routing is purely relative
  // (signed hops moved toward zero), so a local switch works unchanged as a
  // node of the larger torus.
  val switch_array: Seq[Seq[Switch]] = Seq.fill(DimX) {
    Seq.fill(DimY) {
      Module(new Switch(tX, tY, config, n_hop))
    }
  }

  val eastBoundary  = Module(new TorusBoundary(DimY, tX, tY, config))
  val westBoundary  = Module(new TorusBoundary(DimY, tX, tY, config))
  val northBoundary = Module(new TorusBoundary(DimX, tX, tY, config))
  val southBoundary = Module(new TorusBoundary(DimX, tX, tY, config))
  eastBoundary.io.extend  := io.extendEast
  westBoundary.io.extend  := io.extendWest
  northBoundary.io.extend := io.extendNorth
  southBoundary.io.extend := io.extendSouth

  Range(0, DimY).foreach { y =>
    eastBoundary.io.wrapFwdIn(y) := switch_array(h - 1)(y).io.xOutput
    eastBoundary.io.wrapBwdIn(y) := switch_array(h)(y).io.xNegOutput
    westBoundary.io.wrapFwdIn(y) := switch_array(DimX - 1)(y).io.xOutput
    westBoundary.io.wrapBwdIn(y) := switch_array(0)(y).io.xNegOutput
  }
  Range(0, DimX).foreach { x =>
    northBoundary.io.wrapFwdIn(x) := switch_array(x)(v - 1).io.yOutput
    northBoundary.io.wrapBwdIn(x) := switch_array(x)(v).io.yNegOutput
    southBoundary.io.wrapFwdIn(x) := switch_array(x)(DimY - 1).io.yOutput
    southBoundary.io.wrapBwdIn(x) := switch_array(x)(0).io.yNegOutput
  }

  private def hook(side: BareNoCInterface#Side, b: TorusBoundary): Unit = {
    side.fwdOut := b.io.extFwdOut
    b.io.extFwdIn := side.fwdIn
    side.bwdOut := b.io.extBwdOut
    b.io.extBwdIn := side.bwdIn
  }
  hook(io.east, eastBoundary)
  hook(io.west, westBoundary)
  hook(io.north, northBoundary)
  hook(io.south, southBoundary)

  Range(0, DimX).foreach { x =>
    Range(0, DimY).foreach { y =>
      // Eastbound (+X)
      switch_array(x)(y).io.xInput := {
        if (x == 0)      westBoundary.io.wrapFwdOut(y)
        else if (x == h) eastBoundary.io.wrapFwdOut(y)
        else             switch_array(x - 1)(y).io.xOutput
      }
      // Westbound (-X)
      switch_array(x)(y).io.xNegInput := {
        if (x == DimX - 1)   westBoundary.io.wrapBwdOut(y)
        else if (x == h - 1) eastBoundary.io.wrapBwdOut(y)
        else                 switch_array(x + 1)(y).io.xNegOutput
      }
      // Northbound (+Y)
      switch_array(x)(y).io.yInput := {
        if (y == 0)      southBoundary.io.wrapFwdOut(x)
        else if (y == v) northBoundary.io.wrapFwdOut(x)
        else             switch_array(x)(y - 1).io.yOutput
      }
      // Southbound (-Y)
      switch_array(x)(y).io.yNegInput := {
        if (y == DimY - 1)   southBoundary.io.wrapBwdOut(x)
        else if (y == v - 1) northBoundary.io.wrapBwdOut(x)
        else                 switch_array(x)(y + 1).io.yNegOutput
      }
    }
  }

  when(io.configEnable) {
    switch_array.head.head.io.xInput := io.configPacket
  }

  // connect ios
  switch_array.flatten.
    zip(io.corePacketInput.flatten.zip(io.corePacketOutput.flatten))
    .foreach { case (_switch, (_in, _out)) =>
      _switch.io.lInput := _in
      _out := _switch.io.yOutput
      _out.valid := _switch.io.terminal
    }

}
