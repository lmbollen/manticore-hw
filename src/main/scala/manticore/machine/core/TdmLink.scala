package manticore.machine.core

import Chisel._
import chisel3.VecInit
import chisel3.WireDefault
import manticore.machine.ISA

/** Time-division multiplexing of many logical NoC boundary links onto few physical
  * transceiver links (low-radix multi-chip interconnect).
  *
  * The array side is completely unaware of the transceiver: the TorusBoundary keeps
  * presenting/accepting its per-row logical links; this layer serializes them. Each
  * logical link has an INPUT BANK register (egress side, captured packet waiting for
  * its slot) and an OUTPUT BANK register (ingress side, written when a frame with the
  * link's tag arrives, presented to the array for exactly one cycle).
  *
  * Determinism: the slot rotation is FREE-RUNNING (a packet's wait for its slot
  * depends only on its statically-known arrival cycle, never on other traffic), so the
  * compiler can model each logical link as a fixed-rate channel. With `nBanks` logical
  * links and `cyclesPerSlot` N, each link owns one transmit opportunity every
  * `nBanks * N` cycles — that period (plus the transceiver's fixed latency) bounds the
  * per-link latency the schedule must charge (`--hop-latencies`).
  *
  * The static schedule must not oversubscribe a link (at most one packet per bank per
  * rotation period); a violation overwrites the waiting packet and pulses
  * `io.overflow` so simulation catches it.
  *
  * For Z > 1 transceivers, instantiate one mux/demux pair per transceiver over a
  * partition of the logical links.
  */
class TdmFrame(val DimX: Int, val DimY: Int, val config: ISA, val nBanks: Int) extends Bundle {
  val valid  = Bool()                       // frame carries a packet
  val tag    = UInt(log2Ceil(nBanks).W)     // which logical link (bank) it belongs to
  val packet = new NoCBundle(DimX, DimY, config)
}

object TdmFrame {
  def empty(DimX: Int, DimY: Int, config: ISA, nBanks: Int): TdmFrame = {
    val f = Wire(new TdmFrame(DimX, DimY, config, nBanks))
    f.valid  := false.B
    f.tag    := 0.U
    f.packet := NoCBundle.empty(DimX, DimY, config)
    f
  }
}

/** Egress: capture each logical link's packets into its input bank; a free-running
  * rotation visits one bank per `cyclesPerSlot` cycles and transmits (then clears)
  * the bank at the first cycle of its slot.
  */
class TdmLinkMux(nBanks: Int, cyclesPerSlot: Int, DimX: Int, DimY: Int, config: ISA) extends Module {
  require(nBanks >= 2 && cyclesPerSlot >= 1)
  val io = IO(new Bundle {
    val in        = Input(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
    val connected = Input(Bool()) // transceiver attached & link up
    val tx        = Output(new TdmFrame(DimX, DimY, config, nBanks))
    val overflow  = Output(Bool()) // a bank was overwritten before its slot drained it
  })

  val banks     = Reg(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
  val bankValid = RegInit(VecInit(Seq.fill(nBanks)(false.B)))

  // free-running rotation: slot -> bank index, sub counts cycles within the slot
  val slot = RegInit(0.U(log2Ceil(nBanks).W))
  val sub  = RegInit(0.U(log2Ceil(cyclesPerSlot + 1).W))
  val slotStart = sub === 0.U
  when(sub === (cyclesPerSlot - 1).U) {
    sub  := 0.U
    slot := Mux(slot === (nBanks - 1).U, 0.U, slot + 1.U)
  } otherwise {
    sub := sub + 1.U
  }

  // transmit at the first cycle of a bank's slot (reads the pre-capture register value)
  val txValid = io.connected && slotStart && bankValid(slot)
  io.tx.valid  := txValid
  io.tx.tag    := slot
  io.tx.packet := banks(slot)
  when(txValid) { bankValid(slot) := false.B }

  // capture (written after drain: a same-cycle capture into the draining bank wins
  // the next-state update, so the new packet is kept and nothing is lost)
  val overflowNow = WireDefault(false.B)
  for (i <- 0 until nBanks) {
    when(io.in(i).valid) {
      banks(i)     := io.in(i)
      bankValid(i) := true.B
      when(bankValid(i) && !(txValid && slot === i.U)) {
        overflowNow := true.B // previous packet still waiting -> schedule oversubscribed the link
      }
    }
  }
  io.overflow := overflowNow
}

/** Ingress: route an arriving frame into the output bank named by its tag and present
  * it to the array for exactly one cycle (the NoC treats every valid cycle as a new
  * packet, so banks pulse rather than hold).
  */
class TdmLinkDemux(nBanks: Int, DimX: Int, DimY: Int, config: ISA) extends Module {
  require(nBanks >= 2)
  val io = IO(new Bundle {
    val rx        = Input(new TdmFrame(DimX, DimY, config, nBanks))
    val connected = Input(Bool())
    val out       = Output(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
  })

  val outBanks = Reg(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
  for (i <- 0 until nBanks) {
    outBanks(i) := NoCBundle.empty(DimX, DimY, config) // default: 1-cycle pulse semantics
  }
  when(io.rx.valid && io.connected) {
    outBanks(io.rx.tag) := io.rx.packet
  }
  io.out := outBanks
}

/** One chip-edge bridge for an X-dimension torus boundary: serializes the boundary's
  * 2*nLinks logical links (fwd egress banks 0..nLinks-1, bwd egress banks
  * nLinks..2*nLinks-1) onto a single transceiver link, and de-serializes the inverse.
  * Wire `tx`->neighbour `rx` (through the fixed-latency transceiver) and vice versa;
  * the bank index convention is symmetric so two identical bridges interoperate.
  */
class TdmTorusBoundaryBridge(nLinks: Int, cyclesPerSlot: Int, DimX: Int, DimY: Int, config: ISA) extends Module {
  val nBanks = 2 * nLinks
  val io = IO(new Bundle {
    // array-facing (connect to BareNoC's xFwdOut/xBwdOut and xFwdIn/xBwdIn)
    val fwdOut = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))  // chip egress, fwd channel
    val bwdOut = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))  // chip egress, bwd channel
    val fwdIn  = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config))) // chip ingress, fwd channel
    val bwdIn  = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config))) // chip ingress, bwd channel
    // transceiver-facing
    val tx        = Output(new TdmFrame(DimX, DimY, config, 2 * nLinks))
    val rx        = Input(new TdmFrame(DimX, DimY, config, 2 * nLinks))
    val connected = Input(Bool())
    val overflow  = Output(Bool())
  })

  val mux   = Module(new TdmLinkMux(nBanks, cyclesPerSlot, DimX, DimY, config))
  val demux = Module(new TdmLinkDemux(nBanks, DimX, DimY, config))

  for (i <- 0 until nLinks) {
    mux.io.in(i)          := io.fwdOut(i)
    mux.io.in(nLinks + i) := io.bwdOut(i)
    io.fwdIn(i) := demux.io.out(i)
    io.bwdIn(i) := demux.io.out(nLinks + i)
  }
  mux.io.connected   := io.connected
  demux.io.connected := io.connected
  io.tx       := mux.io.tx
  demux.io.rx := io.rx
  io.overflow := mux.io.overflow
}
