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
class TdmFrame(val DimX: Int, val DimY: Int, val config: ISA, val nBanks: Int, val cyclesPerSlot: Int = 1)
    extends Bundle {
  val valid  = Bool()                                          // frame carries a packet
  val tag    = UInt(log2Ceil(nBanks).W)                        // which logical link (bank) it belongs to
  val age    = UInt(log2Ceil(nBanks * cyclesPerSlot + 2).W)    // cycles spent waiting in the input bank
  val packet = new NoCBundle(DimX, DimY, config)
}

object TdmFrame {
  def empty(DimX: Int, DimY: Int, config: ISA, nBanks: Int, cyclesPerSlot: Int = 1): TdmFrame = {
    val f = Wire(new TdmFrame(DimX, DimY, config, nBanks, cyclesPerSlot))
    f.valid  := false.B
    f.tag    := 0.U
    f.age    := 0.U
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
    val tx        = Output(new TdmFrame(DimX, DimY, config, nBanks, cyclesPerSlot))
    val overflow  = Output(Bool()) // a bank was overwritten before its slot drained it
  })

  val banks     = Reg(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
  val bankValid = RegInit(VecInit(Seq.fill(nBanks)(false.B)))
  // cycles each waiting packet has spent in its bank — transmitted as the frame's
  // `age`, so the receiver can release every packet at a CONSTANT total latency.
  val bankAge = Reg(Vec(nBanks, io.tx.age.cloneType))

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

  // age all waiting banks
  for (i <- 0 until nBanks) {
    when(bankValid(i)) { bankAge(i) := bankAge(i) + 1.U }
  }

  // transmit at the first cycle of a bank's slot (reads the pre-capture register value)
  val txValid = io.connected && slotStart && bankValid(slot)
  io.tx.valid  := txValid
  io.tx.tag    := slot
  io.tx.age    := bankAge(slot)
  io.tx.packet := banks(slot)
  when(txValid) { bankValid(slot) := false.B }

  // capture (written after drain: a same-cycle capture into the draining bank wins
  // the next-state update, so the new packet is kept and nothing is lost)
  val overflowNow = WireDefault(false.B)
  for (i <- 0 until nBanks) {
    when(io.in(i).valid) {
      banks(i)     := io.in(i)
      bankValid(i) := true.B
      bankAge(i)   := 0.U
      when(bankValid(i) && !(txValid && slot === i.U)) {
        overflowNow := true.B // previous packet still waiting -> schedule oversubscribed the link
      }
    }
  }
  io.overflow := overflowNow
}

/** Ingress: route an arriving frame into the output bank named by its tag, then hold it
  * until the packet's TOTAL latency (capture at the sender's input bank -> presentation
  * here) reaches exactly `totalLatency` cycles, and present it for one cycle.
  *
  * The release uses the frame's `age` (slot wait at the sender): residual =
  * totalLatency - age - wireLatency - constants. A CONSTANT per-crossing latency is what
  * the compiler's per-link model (`--hop-latencies`) charges, and it preserves the
  * scheduler's collision guarantees exactly: a packet that arrived early in the slot
  * rotation simply rests in its output bank, never appearing in the NoC sooner than the
  * schedule expects. Requires totalLatency >= the worst path
  * (nBanks*cyclesPerSlot + wireLatency + fixed registers), checked at elaboration.
  */
class TdmLinkDemux(
    nBanks: Int,
    cyclesPerSlot: Int,
    wireLatency: Int,
    totalLatency: Int,
    DimX: Int,
    DimY: Int,
    config: ISA
) extends Module {
  require(nBanks >= 2)
  // Timeline for a packet captured at the sender during cycle t:
  //   transmit at slot start s = t + 1 + age          (age = slot wait, 0..period-1)
  //   frame at this demux during s + wireLatency
  //   output bank holds it from  s + wireLatency + 1
  //   presented (1-cycle pulse) at p = s + wireLatency + 1 + release
  //   destination switch register occupied at p + 1
  // Constant-latency contract: (p + 1) - t == totalLatency for EVERY packet, i.e.
  //   release = totalLatency - 3 - wireLatency - age   (>= 0 must hold at age = period-1)
  private val period = nBanks * cyclesPerSlot
  require(
    totalLatency >= period + wireLatency + 2,
    s"totalLatency $totalLatency must cover the worst TDM path ${period + wireLatency + 2} " +
      s"(capture + slot wait ${period - 1} + wire $wireLatency + bank + switch)"
  )
  val io = IO(new Bundle {
    val rx        = Input(new TdmFrame(DimX, DimY, config, nBanks, cyclesPerSlot))
    val connected = Input(Bool())
    val out       = Output(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
    val overflow  = Output(Bool()) // an output bank was overwritten before its release
  })

  val outBanks  = Reg(Vec(nBanks, new NoCBundle(DimX, DimY, config)))
  val bankValid = RegInit(VecInit(Seq.fill(nBanks)(false.B)))
  val release   = Reg(Vec(nBanks, UInt(log2Ceil(totalLatency + 2).W)))

  for (i <- 0 until nBanks) {
    io.out(i) := NoCBundle.empty(DimX, DimY, config) // default: 1-cycle pulse semantics
    when(bankValid(i)) {
      when(release(i) === 0.U) {
        io.out(i)    := outBanks(i)
        bankValid(i) := false.B
      } otherwise {
        release(i) := release(i) - 1.U
      }
    }
  }

  val overflowNow = WireDefault(false.B)
  when(io.rx.valid && io.connected) {
    // a same-cycle overwrite of a bank presenting RIGHT NOW is fine (the present
    // happens this cycle, the new content lands next); earlier overwrites lose a packet
    when(bankValid(io.rx.tag) && !(release(io.rx.tag) === 0.U)) { overflowNow := true.B }
    outBanks(io.rx.tag)  := io.rx.packet
    bankValid(io.rx.tag) := true.B
    release(io.rx.tag)   := (totalLatency - 3 - wireLatency).U - io.rx.age
  }
  io.overflow := overflowNow
}

/** One chip-edge bridge for an X-dimension torus boundary: serializes the boundary's
  * 2*nLinks logical links (fwd egress banks 0..nLinks-1, bwd egress banks
  * nLinks..2*nLinks-1) onto a single transceiver link, and de-serializes the inverse.
  * Wire `tx`->neighbour `rx` (through the fixed-latency transceiver) and vice versa;
  * the bank index convention is symmetric so two identical bridges interoperate.
  */
class TdmTorusBoundaryBridge(
    nLinks: Int,
    cyclesPerSlot: Int,
    wireLatency: Int,  // the transceiver's fixed latency between tx and rx
    totalLatency: Int, // CONSTANT register-to-register seam latency (== the --hop-latencies value)
    DimX: Int,
    DimY: Int,
    config: ISA
) extends Module {
  val nBanks = 2 * nLinks
  val io = IO(new Bundle {
    // array-facing (connect to BareNoC's xFwdOut/xBwdOut and xFwdIn/xBwdIn)
    val fwdOut = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))  // chip egress, fwd channel
    val bwdOut = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))  // chip egress, bwd channel
    val fwdIn  = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config))) // chip ingress, fwd channel
    val bwdIn  = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config))) // chip ingress, bwd channel
    // transceiver-facing
    val tx        = Output(new TdmFrame(DimX, DimY, config, 2 * nLinks, cyclesPerSlot))
    val rx        = Input(new TdmFrame(DimX, DimY, config, 2 * nLinks, cyclesPerSlot))
    val connected = Input(Bool())
    val overflow  = Output(Bool())
  })

  val mux   = Module(new TdmLinkMux(nBanks, cyclesPerSlot, DimX, DimY, config))
  val demux = Module(new TdmLinkDemux(nBanks, cyclesPerSlot, wireLatency, totalLatency, DimX, DimY, config))

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
  io.overflow := mux.io.overflow || demux.io.overflow
}
