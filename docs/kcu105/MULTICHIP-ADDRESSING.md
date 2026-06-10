# Investigation: addressing-space consequences of a grid of 2D tori (multi-chip)

Goal (per the request): extend the topology from a **single 2D torus** (one chip) to a **grid of
2D tori** (multiple chips wired together, dynamically). This focuses on the consequences for the
**addressing space**, with the directly-coupled latency/lockstep issues called out because the
addressing is only *correct* if those hold.

> **Status update (2026-06-10):** since this investigation was written, the NoC has been made
> **bidirectional** (signed hops, min-|hops| shortest-path routing; verified in sim and on the
> KCU105 board), and the **"flatten" scheme's boundary router is implemented**: `TorusBoundary`
> (+ `BareNoC.extendX/extendY` with `torusDimX/torusDimY` hop sizing) conditionally closes each
> ring locally or chains instances into a larger torus at **runtime** — verified by
> `TorusExtensionTester` (two 2×2 instances forming 4×2 or 2×4, and two 4×4 forming 8×4, by a
> boolean). Sections below are updated accordingly.

## How addressing works today (single torus)

A NoC packet (`Switch.scala`) is:

| Field | Width | Meaning |
|---|---|---|
| `data` | `DataBits` = 16 | the value being sent |
| `address` | `IdBits` = **11** (2048) | the destination **register id** — *which register* at the destination core receives the value |
| `xHops` | `log2Ceil(DimX)+1` (**signed**) | hops in X to the destination core; sign = direction (+ east / − west) |
| `yHops` | `log2Ceil(DimY)+1` (**signed**) | hops in Y; sign = direction (+ north / − south) |
| `valid` | 1 | — |

Two facts dominate everything below:

1. **Addressing is purely *relative*.** A packet says "go `xHops` in X, then `yHops` in Y, then
   deliver." Each switch moves the count one step **toward zero**; when both reach 0 it delivers
   locally. **There are no absolute core coordinates anywhere in the NoC.** The torus is
   **bidirectional**: hops are signed, each direction has its own physical channel per ring
   (`x_reg`/`x_neg_reg`, `y_reg`/`y_neg_reg` — opposite directions verified non-interfering even
   in the same cycle on the same ring), and the compiler routes **min-|hops| shortest-path**
   (`HardwareConfig.xHops`: forward vs backward distance, smaller wins, ties break forward).
2. **The "where" and the "what" are separate.** `xHops/yHops` locate the *core* (topology-dependent);
   `address` selects the *register* (per-core, **topology-independent**, always 2048).

The compiler turns a `SEND` target into hops from the `ProcessId` difference, and models its
latency as `manhattan = (xHops+yHops) × nHops` with a **single uniform `nHops`**. The static BSP
schedule assumes this latency is *exact* — that is the whole basis of determinism.

There is already a fixed-latency **cross-die (SLR) crossing** in the send/recv path
(`Processor.scala`: `procSideLatency 3 + slrCrossingLatency 2 + switchSideLatency 2 = 7`). This is
the existing template for crossing a physical boundary with *known, fixed* latency — the closest
analog to a chip-to-chip hop.

## Consequences for the addressing space

### 1. The relative-hop fields are sized for one chip — they must grow
`xHops/yHops` hold `log2Ceil(DimX)+1 / log2Ceil(DimY)+1` **signed** bits (e.g. 3/3 for 4×4,
covering ±2 shortest-path hops). A grid of `Cx×Cy` chips, each `Dx×Dy`, spans a total of
`(Cx·Dx)×(Cy·Dy)` cores. To address across it the hop fields must widen to
`log2Ceil(Cx·Dx)+1 / log2Ceil(Cy·Dy)+1` bits (implemented: `BareNoC(torusDimX, torusDimY)`
sizes the bundles for the global torus while the local array stays `Dx×Dy`):

| System | Total grid | signed hop-field bits (single 4×4 chip: 3/3) |
|---|---|---|
| 2×2 grid of 4×4 | 8×8 = 64 | 4 / 4 |
| 4×4 grid of 4×4 | 16×16 = 256 | 5 / 5 |
| 8×8 grid of 4×4 | 32×32 = 1024 | 6 / 6 |

Every packet, every switch's hop register, and every decrement widen. **The `address` (register-id)
field does NOT change** — each core still has 2048 registers. So the address space grows *only* in
its spatial part.

### 2. Two ways to structure the address space — and the NoC's pure-relativity forces the choice

**(a) Flatten into one big torus** (one global relative-hop space).
- The grid of tori becomes a single `(Cx·Dx)×(Cy·Dy)` torus; the intra-chip wrap links at chip edges
  are *repurposed as inter-chip links* to the neighbour, and the wrap moves to the system perimeter.
- Addressing change = **just widen `xHops/yHops`**; routing logic is unchanged (dimension-order).
- **This is the natural fit**, precisely because the NoC has *no absolute coordinates*: flattening
  keeps everything relative. The edge-link selection is now **runtime-switchable** (implemented:
  `TorusBoundary` closes each ring locally or hands its two directed strings to the neighbour,
  per dimension, on a boolean). Remaining catch: per-hop latency is no longer uniform if the
  physical link adds cycles (§4).

**(b) True hierarchical "grid of tori"** — absolute chip coordinate + intra-chip hops.
- Add a `(chipX, chipY)` field to the packet; chip-edge "gateway" switches forward toward the
  destination chip when `chip ≠ local`, else fall back to the existing intra-chip relative routing.
- Lets the top level be a *mesh* (no wrap) of tori and supports dynamically-sized systems cleanly.
- **More invasive**: it introduces *absolute* identity into a NoC that today is 100% relative —
  new packet field, new routing layer, new gateway logic, and the compiler's flat `manhattan` model
  becomes two-level. The hardware currently has no notion of "which chip am I," so this must be
  added (e.g. a per-chip strap/ID register).

### 3. Core-identity / ProcessId space grows
The compiler's `ProcessId` is `(x,y) ∈ [0,DimX)×[0,DimY)`. Multi-chip makes it either a flat global
`(gx,gy) ∈ [0,Cx·Dx)×[0,Cy·Dy)` (choice a) or hierarchical `(chipX,chipY,x,y)` (choice b). Core count
grows from `Dx·Dy` to `(Cx·Dx)·(Cy·Dy)`; identifying a core needs `+log2(Cx·Cy)` bits. This touches
placement, boot addressing, and SEND-target encoding — all of which derive hops from ProcessId
differences and must now span the full grid.

### 4. The latency model must become NON-uniform (the critical, easy-to-miss one)
The schedule's correctness rests on `manhattan × nHops` with a **single `nHops`**. Inter-chip links
(off-chip GTH/GTY SerDes + board traces) have **higher and different** latency than an on-chip hop.
So:
- `nHops` can no longer be one constant. `HardwareConfig.xHops/yHops/manhattan` must be rewritten to
  **sum per-hop latencies along the route**, charging each inter-chip crossing its real cost
  (intra-chip hop = 1; inter-chip hop = K cycles).
- The inter-chip link **must be fixed-latency and deterministic** — exactly like the existing
  `slrCrossingLatency` pipeline, scaled up. An elastic/async SerDes (variable-latency FIFO) **breaks
  static scheduling** and is not usable as-is; the link needs a phase-aligned, fixed-latency
  (or deterministically-padded) crossing.
- With the **bidirectional** torus (min-|hops| shortest path), worst-case hop distance is
  `⌊W/2⌋+⌊H/2⌋` of the *whole* grid (e.g. 8×8 → 8 hops; the old unidirectional torus needed
  `(W-1)+(H-1)` = 14) — bidirectionality **halves the worst-case distance**, which is exactly
  what makes scaling to large multi-chip topologies viable. Still, with some hops being costly
  inter-chip crossings, the **per virtual-cycle sync latency grows** → the virtual-cycle length
  (and so the sim-time floor) rises. This is a real throughput cost of going multi-chip,
  independent of the bit-width changes.

### 5. Global-memory (48-bit) addressing — partitioning, not width
Global load/store use 48-bit addresses owned by the single privileged core (core (0,0)) per chip.
48 bits is 256 TB — width is *not* the constraint. The issue is **partitioning + routing**: with
multiple chips, either each chip owns a disjoint global-memory region (design global memories
partitioned per chip, no cross-chip global access) or a chip-id must be folded into the global
address to route a global access to the owning chip's privileged core (expensive, and it breaks the
"one privileged core" efficiency the paper relies on). *Note:* if off-chip storage is eliminated
(see `OFFCHIP-ELIMINATION.md`), this whole dimension disappears — scratchpad-only cores never do
cross-chip memory access, which makes multi-chip dramatically simpler.

### 6. Boot / config addressing
The bootloader streams config packets addressed by `(x,y)` to load instruction memories. Multi-chip
favours **one bootloader per chip** (each boots its local torus; the host orchestrates), rather than
one bootloader streaming across chips. Either way the boot config-packet address inherits the
widened/hierarchical scheme from §1–2.

## The prerequisite the addressing rides on: cross-chip lockstep
The address scheme is only *meaningful* if delivery latency is deterministic and known to the
compiler. Manticore keeps all cores + the NoC in strict lockstep via global clock-gating (the stall
for exceptions/cache). Across chips this requires:
- a **synchronized, phase-aligned compute clock** across all chips (or a deterministic clocking
  scheme), so one global schedule is valid system-wide; and
- the **global stall/barrier signal to span chips** with bounded, known latency (today it's a single
  on-chip global clock buffer; multi-chip turns it into a distributed barrier with the inter-chip
  latency folded in).

Without these, the static-latency assumption behind every NoC address is invalid.

## Summary of required addressing changes

| Area | Change |
|---|---|
| Packet (`Switch.scala` `NoCBundle`) | widen `xHops/yHops` to cover the full grid (choice a), **or** add an absolute `(chipX,chipY)` field (choice b). `address`/register-id unchanged. |
| Switch routing (`Switch.scala`) | choice a: none (just wider counters); choice b: new chip-edge gateway forwarding + intra/inter decision. |
| Latency model (`HardwareConfig.scala`) | replace uniform `nHops` with a route-aware per-hop latency (intra=1, inter=K); rewrite `xHops/yHops/manhattan`. |
| ProcessId / placement | extend to global `(gx,gy)` or hierarchical `(chipX,chipY,x,y)`. |
| Send/recv pipe (`Processor.scala`) | add a fixed-latency inter-chip crossing pipe (the `slrCrossing` pattern, with the real SerDes latency). |
| Global memory | partition per chip (or fold chip-id into the 48-bit address) — or eliminate off-chip entirely to sidestep it. |
| Boot | per-chip bootloaders; widened/hierarchical config addressing. |

## Recommendation
- **Cheapest path that works: flatten** the multi-chip system into one larger relative-hop torus
  (choice a). It matches the NoC's existing pure-relative design — only `xHops/yHops` width, the
  chip-edge link rewiring, and the **non-uniform per-hop latency model** change. No absolute IDs.
- **If you want a true dynamically-composable "grid of tori"** (mesh of chips, hot-add chips), pay
  for the **hierarchical scheme** (choice b): absolute chip IDs + gateway routing. More RTL +
  compiler work, but it's the scheme that scales and composes dynamically.
- **Either way, two things are mandatory and dominate the effort more than the bit-widths:** a
  **deterministic fixed-latency inter-chip link** and **cross-chip lockstep** (synchronized clock +
  distributed stall). The address fields are the easy part; making the latency static across chips
  is the hard part.
- **Strongly consider eliminating off-chip/global memory first** (`OFFCHIP-ELIMINATION.md`): a
  scratchpad-only, homogeneous array removes the privileged-core/global-address dimension entirely
  and leaves a pure SEND/RECV NoC — which is the only thing that actually has to cross chips.
