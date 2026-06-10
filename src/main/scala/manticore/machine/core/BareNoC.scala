package manticore.machine.core

import Chisel._
import manticore.machine.ISA


class BareNoCInterface(DimX: Int, DimY: Int, config: ISA) extends Bundle {
  def makePacketArray(): Vec[Vec[NoCBundle]] = Vec(DimX, Vec(DimY, new NoCBundle(DimX, DimY, config)))

  val corePacketInput: Vec[Vec[NoCBundle]] = Input(makePacketArray())
  val corePacketOutput: Vec[Vec[NoCBundle]] = Output(makePacketArray())
  val configPacket: NoCBundle = Input(new NoCBundle(DimX, DimY, config))
  val configEnable: Bool = Input(Bool())
}

/**
 * Bare-bone NoC that only contains an 2D torus of switches
 * @param DimX
 * @param DimY
 * @param config
 */
class BareNoC(DimX: Int, DimY: Int, config: ISA, val n_hop: Int = 2) extends Module {

  val io = IO(new BareNoCInterface(DimX, DimY, config))

  val switch_array: Seq[Seq[Switch]] = Seq.fill(DimX) {
    Seq.fill(DimY) {
      Module(new Switch(DimX, DimY, config, n_hop))
    }
  }

  // connect the row ports in the switches
  // Eastbound (+X): packet from left enters switch's xInput; wrap: rightmost → leftmost
  switch_array.transpose.foreach { row =>
    row.head.io.xInput := row.last.io.xOutput
    row.sliding(2, 1).foreach { case Seq(left: Switch, right: Switch) =>
      right.io.xInput := left.io.xOutput
    }
  }

  // Westbound (-X): packet from right enters switch's xNegInput; wrap: leftmost → rightmost
  switch_array.transpose.foreach { row =>
    row.last.io.xNegInput := row.head.io.xNegOutput
    row.sliding(2, 1).foreach { case Seq(left: Switch, right: Switch) =>
      left.io.xNegInput := right.io.xNegOutput
    }
  }

  when(io.configEnable) {
    switch_array.head.head.io.xInput := io.configPacket
  }

  // connect column ports of the switches
  // Northbound (+Y): packet from below enters switch's yInput; wrap: topmost → bottommost
  switch_array.foreach { col =>
    col.head.io.yInput := col.last.io.yOutput
    col.sliding(2, 1).foreach { case Seq(top: Switch, bot: Switch) =>
      bot.io.yInput := top.io.yOutput
    }
  }

  // Southbound (-Y): packet from above enters switch's yNegInput; wrap: bottommost → topmost
  switch_array.foreach { col =>
    col.last.io.yNegInput := col.head.io.yNegOutput
    col.sliding(2, 1).foreach { case Seq(top: Switch, bot: Switch) =>
      top.io.yNegInput := bot.io.yNegOutput
    }
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
