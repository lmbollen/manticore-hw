# The seam problem — SOLVED (2026-07-06)

**Resolution.** Five stacked root causes (two in the compiler/sim stack, one in the latencies derivation, one in the driver's boot sequencing, one in the bittide transceiver stack); the first two were found via the
sim-as-baseline frame-conservation hunt, after which the 8-chip demo sim
passed its full data-dependent golden for the first time — `SIG =
(225,225,225)`, `CHK = (128,11707,115475)`, FINISH at vc 1033, zero
overflow/loss/gate violations. The rig still read `SIG = (0,0,0)` with the
identical software, which isolated the third cause to the one thing the sim
cannot check by construction: whether the latencies CSV matches the physical
rig (the sim builds its cables FROM the CSV, so any self-consistent value
stays green).

1. **Compiler: register reservations anchored at hop ARRIVAL, not physical
   occupancy** (manticore-compiler `2b203f9`). A hop's near-end switch
   register is physically occupied one cycle after the previous hop — a slow
   seam link spends its latency BETWEEN registers, in the TDM bridge. The
   model reserved the cell at departure+L instead of departure+1: identical
   for 1-cycle intra-chip hops (single-chip schedules bit-identical, which is
   why single-chip was always exact), off by L−1 on seam links (1297 cycles
   on the known long cable). Physically-colliding register uses at
   seam-adjacent switches were invisible to the scheduler; in RTL the
   higher-priority port won and the loser was silently dropped (caught live:
   a northbound transit killed by a westbound turn at (0,13), every vcycle).
   Post-fix the image has zero physically-colliding register writes
   (exhaustive offline check), zero undelivered transactions, and every
   delivery lands at exactly the modeled cycle.

2. **Sim harness: CFU mismatch** (manticore-hw `55a4c29`). The demo images
   are compiled WITH custom functions (matching the rig's CFU-enabled
   bitstream), but `MultiChipPerMgmtSimKernel` elaborated its arrays with
   `enable_custom_alu = false` — every CF-extracted computation was dead
   silicon and the guest sat in an all-zero fixed-point from vcycle 0,
   indistinguishable from a transport bug. This confounded ALL prior 8-chip
   sim results (the rig never had this issue). The tester now enables the
   CFU (`-Dpermgmt.cfu`).

3. **Rig-only: the latencies CSV borrowed the WireDemo's `internalDelay =
   −4`, but the Manticore seam datapath needs −2** (bittide-hardware
   `dc71acff`). The CSV formula is `latency = goldenUGN + marginFrames +
   internalDelay + period + 3`, where `internalDelay` backtracks the
   MU-measured UGN to the application's tap points. WireDemo's −4 was
   measured for ITS ring-buffer PE taps; the Manticore seam ports sit at
   different pipeline depths. Exact register accounting (per directed
   cable, `P` = physical flight GTH-TX-register → elastic-buffer output):
   the UGN probe path has **5** stages around `P` (`sendUgn` stamps
   combinationally at the ring-buffer TX point → handshake TX `dflipflop`
   → `gthTxR` → P → `rxs2` → `rxs4` → `captureUgn`'s input register,
   captured combinationally on trigger), while the seam port-to-port path
   has **3** (`gthTxR` → P → `rxs2` → `mkSeamIn` register). The
   bridge-internal stages cancel exactly between rig and sim — the chip's
   static `seamLatency=26 / wireLat=15` bridge and the sim kernels'
   per-CSV bridges both hold frames `totalLatency−4−wireLat−age = 7−age`
   cycles — so the effective seam latency in groomed chip counters is
   `UGN+margin + 9` while the CSV said `UGN+margin + 7`: **every seam
   frame landed 2 cycles later than the compiler's schedule assumed**,
   uniformly, in both directions of every cable. Mistimed epilogue
   injection at every destination ⇒ seam-crossed `SIG = (0,0,0)` while
   chip-local `CHK` stayed golden — and WireDemo keeps passing since −4
   is correct for its own taps. Fixed as a Manticore-specific
   `seamInternalDelay = −2` in `ManticoreDemo/Latencies.hs` (all 160
   directed seam rows move up by exactly 2).

4. **Rig-only: the driver booted chips with their seams extended**
   (bittide-hardware `f4516321`). The sim kernel has gated `extend=false`
   around per-chip boots since the ic5 body-truncation fix — explicitly
   marked sim-only because the real chip's extend bits are
   driver-controlled — but the driver never did the equivalent: boot NoC
   frames could leak across live seams, and frames left frozen in the
   boundary bridges by a stall-gated run thawed into the next boot
   (reload-boots hung; the sweep runs proved gating cures them). Every
   coordinated phase now retracts all extends (edges U-turn — the
   per-chip boot topology the split images are built for), arms, settles,
   verifies every chip parked in `sResumeWait`, re-extends, and ungates
   together at S over clean seams.

5. **Rig-only, CONFIRMED with direct counters: the bittide transceiver
   ResetManager never disarms and a single post-init line error kills a
   groomed link pair** (bittide-hardware `Bittide.Transceiver`:
   `errorAfterRxInitDone = mux rxDataInitDone rxCtrlOrError (pure False)`
   feeding the `Monitor` state of `ResetManager`, whose own in-code TODO
   acknowledges the gap). One 8b/10b coding/disparity/control-symbol
   error on any channel — content-independent line noise; trips hit
   non-seam channels too — sends `Monitor -> ResetUserTx`: the channel's
   TX falls back to commas/PRBS mid-application, the partner's RX counts
   those as errors and trips as well, the pair re-locks at the
   transceiver layer (rx/tx_data_init_dones read all-ones afterwards) —
   but auto-centering is stopped after grooming, so the elastic buffers
   come back railed and the fabric-level RX stream stays PERMANENTLY
   frozen at a handshake-era word with a pinned datacount. EBMON is
   structurally blind to it (a frozen counter never crosses a
   watermark). Per-boot roulette: one dead direction in run 28808445252
   (seam ILA: node2->node0, 116 frames TX'd, 0 arrive), ~17/20 seam
   directions in run 28812004895 (rig-wide RX ring-buffer map), seven
   `failAfterUps` trips with exact partner `rxRetries=8/rxFullRetries=1`
   pairing in run 28813504842 (transceiver statistics dump). The
   reporter's north feed (node2->node0, plausibly the fiber-spool cable
   = worst eye) died in all three observed boots — severing every
   signature chain: seam-crossed SIG=(0,0,0) exactly, while chip-local
   CHK, grooming (pre-trip), the margin-padded stall wave and the
   (short-running) WireDemo all stay green.

   Fix options (core-library decision): (1) RTL — implement the
   ResetManager TODO: latch `Monitor` on failure / gate
   `errorAfterRxUser` once commissioned, so transient errors do not nuke
   groomed links; (2) firmware — poll `failAfterUps` + EB liveness and
   re-groom (requires keeping re-centering alive); (3) driver — a
   fail-fast pre-CMD_START gate on `failAfterUps == 0` everywhere,
   turning silent data death into a loud, retryable bring-up failure.
   Recommended: (1) + (3).

**What the hunt verified along the way** (the bittide abstraction holds):
the TDM link core is exact at T ∈ {15, 71, 122, 1298} in directed tests
(`TdmExtremeLatencyTester`, incl. ~160 in-flight same-link frames), and in
vivo all eight stream endpoints of every probed cable conserve perfectly —
~2.5M packet events, exact counts, constant per-pair latency equal to the
CSV, zero corruption. No frames are lost or duplicated at any seam.

**Tooling left in place**: `[SPKT]` per-cable frame-conservation probes
(`-Dseampkt.cables`), per-chip debug (`-Dpermgmt.debugchip`), the fixed
debug-watcher wiring (edge switches were blind before), the exact
per-register collision detector, and the offline correlators
(conservation diff, route-conformance vs binary-decoded SENDs, physical
collision predictor, data-progression frontier).

Historical analysis below (pre-resolution evidence trail).

---

# The remaining seam problem — status, evidence, analysis (historical)

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
