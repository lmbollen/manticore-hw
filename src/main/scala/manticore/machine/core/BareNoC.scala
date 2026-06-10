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

  // Torus extension (see TorusBoundary): each ring of a dimension is cut into a
  // line whose two directed strings (fwd/bwd) end at a boundary router. With
  // extendX/extendY = false (default) the boundary reconnects the strings — the
  // standalone single-torus behaviour. With extend = true the strings are routed
  // through these ports instead; cross-connecting two instances (fwd<->fwd,
  // bwd<->bwd) forms one torus of twice the extent in that dimension.
  val extendX: Bool = Input(Bool())
  val xFwdOut: Vec[NoCBundle] = Output(Vec(DimY, new NoCBundle(tX, tY, config)))
  val xFwdIn: Vec[NoCBundle]  = Input(Vec(DimY, new NoCBundle(tX, tY, config)))
  val xBwdOut: Vec[NoCBundle] = Output(Vec(DimY, new NoCBundle(tX, tY, config)))
  val xBwdIn: Vec[NoCBundle]  = Input(Vec(DimY, new NoCBundle(tX, tY, config)))

  val extendY: Bool = Input(Bool())
  val yFwdOut: Vec[NoCBundle] = Output(Vec(DimX, new NoCBundle(tX, tY, config)))
  val yFwdIn: Vec[NoCBundle]  = Input(Vec(DimX, new NoCBundle(tX, tY, config)))
  val yBwdOut: Vec[NoCBundle] = Output(Vec(DimX, new NoCBundle(tX, tY, config)))
  val yBwdIn: Vec[NoCBundle]  = Input(Vec(DimX, new NoCBundle(tX, tY, config)))
}

/**
 * Bare-bone NoC that only contains an 2D torus of switches
 * @param DimX       local array width (number of switch columns in this instance)
 * @param DimY       local array height
 * @param config
 * @param n_hop      pipeline registers per hop
 * @param torusDimX  GLOBAL torus width for hop-field sizing (0 = DimX, standalone).
 *                   Set to e.g. 2*DimX when two instances are chained via extendX.
 * @param torusDimY  ditto for Y (Y extension not yet wired — symmetric, add when needed)
 */
class BareNoC(DimX: Int, DimY: Int, config: ISA, val n_hop: Int = 2, torusDimX: Int = 0, torusDimY: Int = 0) extends Module {

  private val tX = if (torusDimX > 0) torusDimX else DimX
  private val tY = if (torusDimY > 0) torusDimY else DimY
  require(tX >= DimX && tY >= DimY, "torus dims must be >= local array dims")

  val io = IO(new BareNoCInterface(DimX, DimY, config, tX, tY))

  // Switches take dims ONLY for NoCBundle sizing — routing is purely relative
  // (signed hops moved toward zero), so a local switch works unchanged as a
  // node of the larger torus.
  val switch_array: Seq[Seq[Switch]] = Seq.fill(DimX) {
    Seq.fill(DimY) {
      Module(new Switch(tX, tY, config, n_hop))
    }
  }

  // connect the row ports in the switches
  // Eastbound (+X): packet from left enters switch's xInput. The wrap link
  // (rightmost → leftmost) goes through the TorusBoundary so it can be opened
  // toward a neighbouring instance (extendX) or closed locally (default).
  val xBoundary = Module(new TorusBoundary(DimY, tX, tY, config))
  xBoundary.io.extend := io.extendX

  switch_array.transpose.zipWithIndex.foreach { case (row, y) =>
    xBoundary.io.wrapFwdIn(y) := row.last.io.xOutput
    row.head.io.xInput := xBoundary.io.wrapFwdOut(y)
    row.sliding(2, 1).foreach { case Seq(left: Switch, right: Switch) =>
      right.io.xInput := left.io.xOutput
    }
  }

  // Westbound (-X): packet from right enters switch's xNegInput; wrap
  // (leftmost → rightmost) also via the boundary.
  switch_array.transpose.zipWithIndex.foreach { case (row, y) =>
    xBoundary.io.wrapBwdIn(y) := row.head.io.xNegOutput
    row.last.io.xNegInput := xBoundary.io.wrapBwdOut(y)
    row.sliding(2, 1).foreach { case Seq(left: Switch, right: Switch) =>
      left.io.xNegInput := right.io.xNegOutput
    }
  }

  io.xFwdOut := xBoundary.io.extFwdOut
  xBoundary.io.extFwdIn := io.xFwdIn
  io.xBwdOut := xBoundary.io.extBwdOut
  xBoundary.io.extBwdIn := io.xBwdIn

  when(io.configEnable) {
    switch_array.head.head.io.xInput := io.configPacket
  }

  // connect column ports of the switches — Y wrap links also via a TorusBoundary.
  // Note: the topmost yOutput carries terminal deliveries too (valid=false packets
  // with the terminal flag); they are ignored by any downstream yInput handler, so
  // forwarding them through the boundary (or off-chip) is harmless.
  val yBoundary = Module(new TorusBoundary(DimX, tX, tY, config))
  yBoundary.io.extend := io.extendY

  // Northbound (+Y): packet from below enters switch's yInput; wrap: topmost → bottommost
  switch_array.zipWithIndex.foreach { case (col, x) =>
    yBoundary.io.wrapFwdIn(x) := col.last.io.yOutput
    col.head.io.yInput := yBoundary.io.wrapFwdOut(x)
    col.sliding(2, 1).foreach { case Seq(top: Switch, bot: Switch) =>
      bot.io.yInput := top.io.yOutput
    }
  }

  // Southbound (-Y): packet from above enters switch's yNegInput; wrap: bottommost → topmost
  switch_array.zipWithIndex.foreach { case (col, x) =>
    yBoundary.io.wrapBwdIn(x) := col.head.io.yNegOutput
    col.last.io.yNegInput := yBoundary.io.wrapBwdOut(x)
    col.sliding(2, 1).foreach { case Seq(top: Switch, bot: Switch) =>
      top.io.yNegInput := bot.io.yNegOutput
    }
  }

  io.yFwdOut := yBoundary.io.extFwdOut
  yBoundary.io.extFwdIn := io.yFwdIn
  io.yBwdOut := yBoundary.io.extBwdOut
  yBoundary.io.extBwdIn := io.yBwdIn


  // connect ios
  switch_array.flatten.
    zip(io.corePacketInput.flatten.zip(io.corePacketOutput.flatten))
    .foreach { case (_switch, (_in, _out)) =>
      _switch.io.lInput := _in
      _out := _switch.io.yOutput
      _out.valid := _switch.io.terminal
    }

}
