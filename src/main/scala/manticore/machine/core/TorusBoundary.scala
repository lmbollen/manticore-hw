package manticore.machine.core

import Chisel._
import manticore.machine.ISA

/** Conditional torus-boundary router for one dimension of the 2D torus.
  *
  * Sits on the wrap-around links of one dimension (e.g. X). Based on a single
  * boolean it either:
  *
  *   - `extend = false` (standalone): closes the bidirectional loop locally —
  *     the array's boundary outputs feed straight back into its boundary inputs,
  *     exactly like the hard-wired wrap it replaces (combinational, zero added
  *     latency). The external egress ports emit invalid packets.
  *
  *   - `extend = true` (multi-instance): the wrap traffic is routed off-chip —
  *     boundary outputs drive the external egress, and the external ingress
  *     drives the boundary inputs. Wiring two instances' egress→ingress (fwd and
  *     bwd cross-connected pairwise) forms a single LARGER torus: the NoC's
  *     addressing is purely relative (signed hops decremented toward zero at
  *     each switch), so no routing logic changes — only the hop-field width must
  *     cover the global torus (instantiate the switches/NoCBundle with the
  *     global dimensions via e.g. BareNoC's torusDimX).
  *
  * The component itself is purely combinational; the constant-latency
  * chip-to-chip link (handled externally) owns whatever delay the physical
  * crossing adds, mirroring the existing fixed-latency SLR-crossing pattern.
  * See docs/kcu105/MULTICHIP-ADDRESSING.md (the "flatten into one bigger
  * torus" scheme).
  *
  * @param nLinks number of parallel links crossing the boundary (= DimY when
  *               extending X; = DimX when extending Y)
  * @param DimX   NoCBundle sizing dims (the GLOBAL torus dims when extending)
  * @param DimY   ditto
  */
class TorusBoundary(nLinks: Int, DimX: Int, DimY: Int, config: ISA) extends Module {
  val io = IO(new Bundle {
    val extend = Input(Bool())

    // array side: the would-be wrap-around links
    val wrapFwdIn  = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))  // from last col/row fwd outputs
    val wrapFwdOut = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config))) // to first col/row fwd inputs
    val wrapBwdIn  = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))  // from first col/row bwd outputs
    val wrapBwdOut = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config))) // to last col/row bwd inputs

    // external side: to/from the neighbouring instance
    val extFwdOut = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config)))
    val extFwdIn  = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))
    val extBwdOut = Output(Vec(nLinks, new NoCBundle(DimX, DimY, config)))
    val extBwdIn  = Input(Vec(nLinks, new NoCBundle(DimX, DimY, config)))
  })

  for (i <- 0 until nLinks) {
    when(io.extend) {
      // open the loop: boundary traffic leaves the chip, neighbour traffic enters
      io.wrapFwdOut(i) := io.extFwdIn(i)
      io.wrapBwdOut(i) := io.extBwdIn(i)
      io.extFwdOut(i)  := io.wrapFwdIn(i)
      io.extBwdOut(i)  := io.wrapBwdIn(i)
    }.otherwise {
      // close the loop locally (identical to the hard-wired torus wrap)
      io.wrapFwdOut(i) := io.wrapFwdIn(i)
      io.wrapBwdOut(i) := io.wrapBwdIn(i)
      io.extFwdOut(i)  := NoCBundle.empty(DimX, DimY, config)
      io.extBwdOut(i)  := NoCBundle.empty(DimX, DimY, config)
    }
  }
}
