# The remaining seam problem — status, evidence, analysis (2026-07-06)

**Symptom.** On the 8-chip demo (2×4 grid of 4×4 chips = folded 8×16 global torus),
`loop_multi`'s reporter reads `SIG = (0, 0, 0)` instead of the interpreter golden
`(225, 225, 225)`. Identical on the 8-FPGA rig (HITL runs 28610159159, 28774339849,
28775574380) and in the local `MultiChipPerMgmtSimTester` — the failure is fully
sim-reproducible. Everything structural is green: all 8 chips run lockstep to
vc = 1033, the reporter FINISHes, the seam mux-overflow and demux data-loss
detectors stay silent, boot skew is zero, and (on most compiles) the final stall
wave is clean.

## What it is NOT (verified-healthy layers)

The problem is tightly delimited by everything already verified:

- **Bittide transport**: elastic buffers stay centered through the whole run
  (EBMON watermarks: ±1-frame jitter, zero under/overflow), Callisto alive,
  UGN grooming applied. Seam frames physically flow.
- **Intra-chip NoC**: after the two RTL fixes (`Switch` `terminal_reg` clobber,
  `MemoryIntercept` late sample), the single-chip 8×4 run matches the
  interpreter golden on *every* displayed value; delivery correlation is
  1394/1394 transactions cycle-exact against `transactions.csv`.
- **The $display/trace mechanism**: verified end-to-end on hardware (CHK
  triples exactly golden on two independent rig runs); trace offsets now
  globally unique per process.
- **Boot/start alignment**: all cores/chips activate with 0-cycle skew;
  per-chip vcycle counts identical.

What remains is specifically the **value transport across particular
inter-chip paths**: some (not all) seam-crossing dataflow chains freeze, and
`loop_multi`'s three signature chains all cross broken paths, hence (0,0,0).

## Evidence

All from the instrumented debug run (chip 0 instrumented with the
TERM/INJ/SENDOUT/hop-trace probes; image `/tmp/mc8_dumps` with its full
compiler dumps; offline correlator `correlate8.py`):

1. **373/380 chip0-bound transactions deliver cycle-exact** — at
   `expectedRecv − 7`, every one of 1034 vcycles, including many seam-crossing
   transactions and at least one over the suspect "1298" cable. The compiler's
   NoC model and the sim's cable models agree almost everywhere.

2. **7 transactions never deliver** (0/1034; they vanish before the
   destination switch, so this is not the injection layer). Every one of them
   crosses the **chip(0,1) → chip(0,0) cable** — the two directed global-edge
   groups `(·,2,south)` and `(·,13,north)`, both carrying CSV latency **1298**.
   The victims sit mid-burst in otherwise-healthy 8-cycle-spaced trains from
   the same source to the same destination (e.g. `(0,4)→(0,1)`: scheds
   …803 ✓, 811 ✗, 819 ✓…), so it is not a capacity/rate effect.

3. **~21 phantom terminal deliveries recur every vcycle** on chip 0 and die in
   real y_reg collisions (exact per-register collision detector: terminal via
   `yNegInput` killed by a higher-priority northbound transit; stable
   positions 152–622). The phantoms' (core, dest-reg) pairs match **no
   transaction at all** (e.g. dest reg 20 at local (0,1), which isn't even a
   declared recv there). They arrive off the seams. One microscope example
   showed a packet at its supposed destination row still carrying ~6 remaining
   hops — i.e. hop-field values inconsistent with any legal route, suggesting
   the phantoms are legit packets whose hop/route state got mangled crossing a
   seam (which would simultaneously explain a dead victim and a phantom).

4. **The latency spread across cables is REAL, and the divergence tracks the
   extreme link.** The authoritative `manticore-latencies` output (golden-UGN
   derived) spans 15…1298 cycles per directed cable: most are 67–72, one
   direction reads **1298** (the chip(0,1)→chip(0,0) cable, both of its
   global-edge groups `(·,2,south)`/`(·,13,north)`), others 15 and 122/123.
   The high-latency link is a KNOWN property of the rig — not a bug. What
   matters is that the model-vs-RTL divergence concentrates exactly on it:
   ~70-cycle links are handled cycle-exact, while every never-delivered
   transaction crosses the 1298 link. Note the operating envelope: with
   period 8, T=1298 means a 1287-deep wire pipe (a packet spends ~46% of the
   2825-cycle vcycle in flight on one hop), and T=15 leaves only a 4-deep
   pipe — both are far outside the T≈25 regime where the TDM bridge
   invariants were designed and property-tested.

5. **Placement decides which values die.** The CHK experiment made this
   crisp: the same program compiled twice put the CHK state cones on
   different paths — the rig's CI compiles kept them on healthy routes (CHK
   exactly golden on hardware, twice), while local compiles routed them over
   the broken cable (CHK read `(125,0,799)` / `(128,0,225)`: counter healthy,
   LFSR/accumulator frozen). The breakage is deterministic per *path*, not
   random: a chain that crosses a bad path freezes identically every vcycle.

6. **The cross-chip stall wave is one jitter away from breaking.** The
   scheduler ends each core's body right after its last RECV
   (`finalizeScheduleWithReceives`), so the synthesized per-chip heartbeat
   tiles get execution windows only **4–5 cycles** past their cross-chip
   heartbeat arrivals — and the hardware silently drops any packet arriving
   outside `StaticExecutionPhase` (the imem-injection path only exists in
   that state). On the v4 image this broke the stall wave outright (chips 4–7
   never gated; gate-violation at the final stall); the current image's
   margins happen to hold. Any real seam jitter ≥ ~4 cycles (TDM slot phase
   alone can be 0..7) re-breaks it.

## Analysis

The latencies are ground truth (the CSV drives both the compiler's schedule
and the sim's cable models, and the high-latency link is a known rig
property), so the sim is a faithful model and its residual divergence is the
real bug: with clock control verified live for the whole run, the bittide
abstraction guarantees constant-latency links, and frames must be neither
lost nor duplicated. They demonstrably are — so **something in our layers
(TDM bridge, boundary ingress, or the compiler's reservation/window model)
is incorrect specifically in the extreme-latency regime**, while the nominal
~70-cycle regime is cycle-exact.

Concretely, packets crossing the 1298 link either:

- **die in transit** (the 7 victims — never seen at the destination switch,
  no collision event at their terminals, mux-overflow and demux-loss flags
  silent), and/or
- **reappear as phantoms** — packets terminating at cores/registers no
  transaction targets, at stable early-vcycle positions, colliding with
  legitimately-scheduled traffic. A packet whose hop fields got mangled
  crossing the seam would produce exactly this pair of symptoms (victim +
  phantom); duplication at capture or a bank-release misalignment at extreme
  T are alternative producers.

Static review of the bridge shows the obvious things are right — the demux
release counter is sized from `totalLatency`, the `age` field is sender-side
slot wait (period-bounded, no accumulation over the wire), the cable
constructor pairs each direction's wire depth with its demux latency
correctly, and the wire-depth contract `T = period + wire + 3` holds by
construction at both extremes. But the property tests sweep small random T
(≈10–42) and never overlap in-flight same-link packets, whereas the demo
holds ~160 frames of one link in flight at the designed 1-cycle release
margin — `TdmExtremeLatencyTester` now covers exactly that regime
(T ∈ {15, 71, 122, 1298}, period-spaced trains, exact delivery-set
equality). The compiler side has an analogous untested corner: the
reservation model's no-wrap-around argument and mux slot-phase accounting
were reasoned about for T ≈ 25–70, not for links where a packet is in
flight for half a vcycle.

## Prioritized next steps

1. **Directed TDM-bridge unit tests at the real extremes** — add
   T = 1298 (deep) and T = 15 (shallow) cases to `TdmStaticLatencyTester` /
   `TdmLinkTester` (exact constant-latency delivery, no duplication, no
   frame-field corruption, correct behavior across many slot phases and
   back-to-back same-link trains). Minutes to run; either convicts or
   acquits the bridge at extreme T.
2. **Phantom forensics on the long cable**: enable `debug_enable` on chip
   (0,1) as well, trace both ends of the chip(0,1)→chip(0,0) cable with the
   hop-field probes, and diff each TDM frame at capture vs emit. A
   hop-corruption or duplication is caught red-handed in one run. All
   tooling exists (`[TERM]/[SENDOUT]/[XOUT…]` probes, `correlate8.py`,
   per-chip dumps).
3. **Audit the compiler's window/reservation model for T ≫ period and
   T ≈ period+4**: the no-wrap-around assumption, mux slot-phase
   accounting over ~160 in-flight periods, and same-link burst spacing at
   these depths (the victims sit mid-burst in 8-cycle trains).
4. **Stall-tile margin** (compiler): pad the post-last-RECV window by
   `tdmPeriod + margin` when hop latencies are configured, so the cross-chip
   heartbeat can never land in sleep. Small, local to
   `ProgramSchedulingTransform.finalizeScheduleWithReceives`.
5. Re-run the cascade: 8-chip sim → rig. The data-dependent asserts
   (sig2 == 225, full SIG triple reported; CHK already green) make the
   endpoint unambiguous.

## Where everything lives

- Testers: `MultiChipPerMgmtSimTester` (`-Dpermgmt.dir/csv`),
  `Pico84SingleChipTester` (single-chip golden), `Pico84TermTraceTester`
  (debug-enabled trace run to first FLUSH).
- Probes (all `debug_enable`-gated): terminal/send/hop traces in
  `ManticoreFlatArray`, packet-injection + RF-write traces in `Processor`,
  exact per-register collision detector in `SwitchPacketInspector`,
  gmem-request traces at both intercept ends.
- Offline analysis: `correlate8.py` / `correlate.py` / `orphans.py`
  (session scratchpad) — trace ↔ `transactions.csv` ↔ regalloc-dump
  correlation.
- Images: `/tmp/mc8_dumps` (with full `--dump-all` compiler artifacts),
  `/tmp/mc8_chk2` (current benchmark); CSV `/tmp/latencies_demo_v2.csv`
  (≡ `manticore-latencies` output).
- Fixed-layer commits: manticore-hw `7abac93` (Switch terminal_reg),
  `2a40118` (MemoryIntercept); manticore-compiler `3cf1bf1` (global trace
  offsets); bittide `aba12e23`/`a99284f9`/`4e7a6485` (synth-cache key,
  tdm-period, HITL latencies).
