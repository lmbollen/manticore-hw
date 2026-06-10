/** Copyright 2021 Mahyar Emami
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to
  * deal in the Software without restriction, including without limitation the
  * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
  * sell copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in
  * all copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
  * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
  * IN THE SOFTWARE.
  */
package manticore.machine.core

import Chisel._
import chisel3.DontCare
import firrtl.ir.Width
import manticore.machine.ISA

import manticore.machine.Helpers

class BareNoCBundle(val config: ISA) extends Bundle {
  val data: UInt    = UInt(config.DataBits.W)
  val address: UInt = UInt(config.IdBits.W)
  val valid: Bool   = Bool()
}

/** A data and control bundle that traverses NoC hops
  *
  * @param DimX
  *   number of switches in the X direction
  * @param DimY
  *   number of switches in the Y direction
  * @param config
  *   the configuration of the processors
  */
class NoCBundle(val DimX: Int, val DimY: Int, override val config: ISA) extends BareNoCBundle(config) {
  // Signed hop counts: positive = forward (+X/+Y), negative = backward (-X/-Y).
  // One extra bit beyond log2Ceil provides the sign while covering the full bidirectional range
  // (e.g., DimX=4 → SInt(3.W) represents -4..+3, max bidirectional distance is ±2).
  val xHops: SInt = SInt((log2Ceil(DimX) + 1).W)
  val yHops: SInt = SInt((log2Ceil(DimY) + 1).W)
}

object NoCBundle {

  def apply(DIMX: Int, DIMY: Int, config: ISA) =
    new NoCBundle(DIMX, DIMY, config)

  /** Create and empty packet with valid bit set to false
    *
    * @param DIMX
    * @param DIMY
    * @param config
    * @return
    */
  def empty(DIMX: Int, DIMY: Int, config: ISA): NoCBundle = {
    val bundle = Wire(new NoCBundle(DIMX, DIMY, config))
    bundle.valid := false.B

    /** we set the others to DontCare since we really don't care
     * this way we can use [[empty]] as initial value to [[RegInit]]
     * in an optimized manner. Because Chisel will not include the signals
     * that are [[DontCare]] in the actual reset circuit.
     */
    bundle.data    := DontCare
    bundle.address := DontCare
    bundle.xHops   := DontCare
    bundle.yHops   := DontCare
    bundle
  }

  /** Create a new packet from the original with xHops moved one step toward zero.
    * Positive xHops (eastbound) decrement; negative xHops (westbound) increment.
    * The routing logic picks the correct output port (xOutput or xNegOutput) based
    * on the sign; this function only adjusts the counter regardless of direction.
    */
  def passX(orig: NoCBundle): NoCBundle = {
    val passed = Wire(new NoCBundle(orig.DimX, orig.DimY, orig.config))
    passed       := orig
    passed.xHops := Mux(orig.xHops > 0.S, orig.xHops - 1.S, orig.xHops + 1.S)
    passed
  }

  /** Create a new packet from the original with yHops moved one step toward zero.
    * Positive yHops (northbound) decrement; negative yHops (southbound) increment.
    */
  def passY(orig: NoCBundle): NoCBundle = {
    val passed = Wire(new NoCBundle(orig.DimX, orig.DimY, orig.config))
    passed       := orig
    passed.yHops := Mux(orig.yHops > 0.S, orig.yHops - 1.S, orig.yHops + 1.S)
    passed
  }

  /** Create a terminal packet, i.e., an invalid packet (valid = false) that
    * retains the `data` and `address` fields
    *
    * @param orig
    *   the original packet
    * @return
    */
  def terminal(orig: NoCBundle): NoCBundle = {
    val reached = Wire(empty(orig.DimX, orig.DimY, orig.config))
    reached.address := orig.address
    reached.data    := orig.data
    reached
  }

}

/** NoC Switch input and output interface with three input interfaces X, Y, L
  * and two output interfaces X and Y. The is time-multiplexed between routing
  * packets through Y or delivering them to the local PE.
  *
  * @param DimX
  * @param DimY
  * @param config
  */
class SwitchInterface(DimX: Int, DimY: Int, config: ISA) extends Bundle {
  // Eastbound channel: packets traveling in the +X direction
  val xInput: NoCBundle  = Input(NoCBundle(DimX, DimY, config))
  val xOutput: NoCBundle = Output(NoCBundle(DimX, DimY, config))

  // Westbound channel: packets traveling in the -X direction
  val xNegInput: NoCBundle  = Input(NoCBundle(DimX, DimY, config))
  val xNegOutput: NoCBundle = Output(NoCBundle(DimX, DimY, config))

  // Northbound channel: packets traveling in the +Y direction.
  // yOutput is also used for terminal delivery to the local PE (terminal == true).
  val yInput: NoCBundle  = Input(NoCBundle(DimX, DimY, config))
  val yOutput: NoCBundle = Output(NoCBundle(DimX, DimY, config))

  // Southbound channel: packets traveling in the -Y direction
  val yNegInput: NoCBundle  = Input(NoCBundle(DimX, DimY, config))
  val yNegOutput: NoCBundle = Output(NoCBundle(DimX, DimY, config))

  // input from the local PE
  val lInput: NoCBundle = Input(NoCBundle(DimX, DimY, config))

  // terminal: true when yOutput carries a packet destined for the local PE
  val terminal: Bool = Output(Bool())
}

/** Bidirectional NoC switch for a 2D torus.
  *
  * Routing is dimension-ordered: X dimension first, then Y. Hop fields in NoCBundle are
  * signed (positive = forward/+X/+Y, negative = backward/-X/-Y); each switch moves the
  * count one step toward zero and forwards the packet on the correct directional channel.
  * When both hop fields reach zero the packet is delivered to the local PE via yOutput
  * (terminal == true) regardless of the direction it arrived from.
  *
  * Five input ports — xInput (eastbound), xNegInput (westbound), yInput (northbound),
  * yNegInput (southbound), lInput (local) — and four output channels plus terminal:
  *   xOutput    : eastbound (+X) in-transit packets
  *   xNegOutput : westbound (-X) in-transit packets
  *   yOutput    : northbound (+Y) in-transit packets AND terminal delivery to the PE
  *   yNegOutput : southbound (-Y) in-transit packets
  *
  * Priority (highest wins, implemented by writing lower-priority inputs first in Chisel):
  *   xInput > xNegInput > yInput > yNegInput > lInput
  *
  * At most two packets can be routed per cycle when their output ports are distinct (e.g.
  * xInput continuing east while lInput turns north). Excess packets sharing an output are
  * dropped respecting the priority order.
  *
  * @param DimX
  * @param DimY
  * @param config
  * @param n_hop pipeline register depth per hop (1 = registered once, 2 = two stages, …)
  */
object Switch {
  // Toggle the simulation-only packet-drop detector with -Dmanticore.debug_drops=true.
  val DEBUG_DROPS: Boolean =
    sys.props.get("manticore.debug_drops").exists(v => v == "true" || v == "1")
}

class Switch(DimX: Int, DimY: Int, config: ISA, n_hop: Int) extends Module {
  val io = IO(new SwitchInterface(DimX, DimY, config))

  val empty = Wire(NoCBundle(DimX, DimY, config))

  // One register per output channel; all default to empty/false each cycle.
  val x_reg: NoCBundle     = Reg(NoCBundle(DimX, DimY, config))
  val x_neg_reg: NoCBundle = Reg(NoCBundle(DimX, DimY, config))
  val y_reg: NoCBundle     = Reg(NoCBundle(DimX, DimY, config))
  val y_neg_reg: NoCBundle = Reg(NoCBundle(DimX, DimY, config))
  val terminal_reg: Bool   = Reg(Bool())

  x_reg        := empty
  x_neg_reg    := empty
  y_reg        := empty
  y_neg_reg    := empty
  terminal_reg := false.B

  // Priority: lInput (lowest) — written first so higher-priority inputs can overwrite.

  when(io.lInput.valid) {
    when(io.lInput.xHops > 0.S) {
      x_reg := NoCBundle.passX(io.lInput)           // go east
    }.elsewhen(io.lInput.xHops < 0.S) {
      x_neg_reg := NoCBundle.passX(io.lInput)        // go west
    }.elsewhen(io.lInput.yHops > 0.S) {
      y_reg := NoCBundle.passY(io.lInput)            // go north (xHops == 0)
    }.elsewhen(io.lInput.yHops < 0.S) {
      y_neg_reg := NoCBundle.passY(io.lInput)        // go south (xHops == 0)
    }
    // both == 0: self-message, drop silently (compiler must not generate these)
  }

  // yNegInput: southbound in-transit or terminal delivery
  when(io.yNegInput.valid) {
    when(io.yNegInput.yHops < 0.S) {
      y_neg_reg    := NoCBundle.passY(io.yNegInput)  // continue south
      terminal_reg := false.B
    }.otherwise {
      // yHops reached 0: terminal delivery via yOutput regardless of arrival direction
      y_reg        := NoCBundle.terminal(io.yNegInput)
      terminal_reg := true.B
    }
  }

  // yInput: northbound in-transit or terminal delivery
  when(io.yInput.valid) {
    when(io.yInput.yHops > 0.S) {
      y_reg        := NoCBundle.passY(io.yInput)     // continue north
      terminal_reg := false.B
    }.otherwise {
      y_reg        := NoCBundle.terminal(io.yInput)  // terminal
      terminal_reg := true.B
    }
  }

  // xNegInput: westbound in-transit; transitions to Y when X is done
  when(io.xNegInput.valid) {
    when(io.xNegInput.xHops < 0.S) {
      x_neg_reg := NoCBundle.passX(io.xNegInput)     // continue west
    }.elsewhen(io.xNegInput.yHops > 0.S) {
      y_reg        := NoCBundle.passY(io.xNegInput)  // turn north
      terminal_reg := false.B
    }.elsewhen(io.xNegInput.yHops < 0.S) {
      y_neg_reg    := NoCBundle.passY(io.xNegInput)  // turn south
      terminal_reg := false.B
    }.otherwise {
      y_reg        := NoCBundle.terminal(io.xNegInput) // terminal
      terminal_reg := true.B
    }
  }

  // xInput: eastbound in-transit (highest priority — written last, wins all conflicts)
  when(io.xInput.valid) {
    when(io.xInput.xHops > 0.S) {
      x_reg := NoCBundle.passX(io.xInput)            // continue east
    }.elsewhen(io.xInput.yHops > 0.S) {
      y_reg        := NoCBundle.passY(io.xInput)     // turn north
      terminal_reg := false.B
    }.elsewhen(io.xInput.yHops < 0.S) {
      y_neg_reg    := NoCBundle.passY(io.xInput)     // turn south
      terminal_reg := false.B
    }.otherwise {
      y_reg        := NoCBundle.terminal(io.xInput)  // terminal
      terminal_reg := true.B
    }
  }

  // We subtract 1 as x_reg/y_reg/terminal_reg each count as 1 hop.
  io.xOutput    := Helpers.InlinePipeWithStyle(x_reg, n_hop - 1)
  io.xNegOutput := Helpers.InlinePipeWithStyle(x_neg_reg, n_hop - 1)
  io.yOutput    := Helpers.InlinePipeWithStyle(y_reg, n_hop - 1)
  io.yNegOutput := Helpers.InlinePipeWithStyle(y_neg_reg, n_hop - 1)
  io.terminal   := Helpers.InlinePipeWithStyle(terminal_reg, n_hop - 1)
}

class SwitchPacketInspector(
    DimX: Int,
    DimY: Int,
    config: ISA,
    pos: (Int, Int),
    fatal: Boolean = false
) extends Module {
  val io            = IO(new SwitchInterface(DimX, DimY, config))
  val clock_counter = RegInit(UInt(64.W), 0.U)
  clock_counter := clock_counter + 1.U
  def error(fmt: String, data: Bits*): Unit = {
    if (fatal)
      assert(
        false.B,
        s"[%d: SwitchX${pos._1}Y${pos._2}]: ${fmt}",
        (clock_counter +: data): _*
      )
    else
      printf(
        s"[%d: SwitchX${pos._1}Y${pos._2}]: ${fmt}",
        (clock_counter +: data): _*
      )
  }
  // report any packet loss that occurs (hop comparisons use signed literals)
  when(io.lInput.valid && io.lInput.xHops === 0.S && io.lInput.yHops === 0.S) {
    error("self packet detected!")
  }

  // when eastbound X causes Y or L to be dropped
  when(io.xInput.valid && io.xInput.xHops === 0.S) {
    when(io.yInput.valid) {
      error("dropping Y because of X")
    }
    when(io.lInput.valid && io.lInput.xHops === 0.S) {
      error("dropping L because of X")
    }
  }

  // when northbound Y causes L to be dropped
  when(io.yInput.valid && io.yInput.yHops === 0.S) {
    when(io.lInput.valid && io.lInput.xHops === 0.S) {
      error("dropping local input")
    }
  }

}
