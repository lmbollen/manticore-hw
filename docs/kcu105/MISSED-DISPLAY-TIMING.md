# 4×4 missed MIPS display — RESOLVED: westbound boot packets misrouted at the master switch

**Status: FIXED — and verified on the board.** Root cause was a missing case in the
bidirectional `Switch`'s `xInput` handler, triggered by the Programmer's boot-packet
injection path. After the one-line fix, both 2×2 and 4×4 MIPS32 RTL co-sims pass with the
exact golden signature (53 vcycles / 32 flushes / 31 RF-write displays / eid 3), and all
cores start their virtual cycles simultaneously.

**Board verification (2026-06-10):** the fixed 4×4 design was synthesized for the KCU105
(xcku040, 200 MHz axi / 100 MHz compute, 64% BRAM; bitstream
`manticore_kcu105_4x4_bidir.bit`) and run on the physical board via JTAG-to-AXI
(`run_manifest.py` flow, same `--no-cf` image as the simulation). Result:
`FINISH (eid=0x3) vcycles=53 after 32 flushes — PASS` — an exact match to the RTL
simulation and the placed interpreter. The bidirectional NoC (including the westbound boot
path through the fixed crossover) works on silicon.

Timing closure note: the first build had 7 hold violations (worst −46 ps) on the dedicated
BRAM cascade (`CASDIN*`) inside a scratchpad — an artifact of the URAM→BRAM port (the
16K×16 scratchpads map to 8 cascaded BRAM36s; cascade hold is clock-skew-determined and
not fixable by phys_opt). Fixed structurally with `CASCADE_HEIGHT(1)` in `BRAMLike.v`
(fabric mux instead of dedicated cascade; sim path unaffected) plus post-route phys_opt in
`build_kcu105.tcl`. Final timing: **WNS +0.487 ns / WHS +0.030 ns, 0 failing endpoints**;
the board re-run with the clean bitstream reproduced the identical golden result. The
critical setup path is shell glue (axi_bram_ctrl → backing BRAM @200 MHz); the compute
clock has ~4 ns of slack at 100 MHz, and the topology limiter on the xcku040 is BRAM
capacity (64% at 4×4), not timing.

> History: two earlier hypotheses were investigated and disproven along the way — an
> "exception-capture race" (disproven: the 31 displays come from ONE predicated FLUSH fired
> across 31 vcycles; nothing to race) and a "loop-carried recv arrival-timing model error"
> (the observed +29/+58 arrival offsets were real, but they were a *symptom* of the boot
> misalignment below, not a scheduler model error). This document records the actual cause.

---

## 1. Symptom

4×4 bidirectional: halts correctly (`eid=3`, exactly 53 vcycles) but reports **30** RF-write
displays instead of **31** — the **first** display (vc=2) missing. 2×2 and forward-only-4×4
pass. Occasionally a freshly recompiled 4×4 binary instead looped with 400 flushes —
same root cause, different collateral (see §3).

## 2. Root cause chain

1. **Compiler** (`MachineCode`): boot destinations are now encoded with *signed shortest-path*
   hops. On a 4×4 torus, cores at x=3 are encoded `xHops = −1` (westbound). Verified in the
   binary: blocks 12–15 have `destHops = (−1, ·)`, and they are streamed **last**, right
   before the countdown sweep.

2. **Programmer injection**: all boot/config packets enter the NoC through the **master
   switch's `xInput`** (eastbound) port (`ManticoreFlatArray`:
   `master_core.switch.io.xInput := io.config_packet`).

3. **The bug** (`Switch.scala`, `xInput` handler): the handler checked `xHops > 0`
   (continue east), then `yHops ≠ 0` (turn), else terminal — **it never handled
   `xHops < 0`**. Genuine eastbound transit can never carry `xHops < 0` (hops move toward
   zero), so normal NoC traffic was unaffected — but the *injected* westbound boot packets
   hit the unhandled case and **skipped their X routing entirely**:
   - (3,0)-bound `(−1, 0)` → delivered **terminal at (0,0)** (the master!)
   - (3,1)-bound `(−1,+1)` → rode north, terminal at **(0,1)**
   - (3,2)-bound `(−1,+2)` → terminal at **(0,2)**
   - (3,3)-bound `(−1,−1)` → rode south, terminal at **(0,3)**

4. **Collateral**: the x=3 cores never receive their (empty) programs — by luck mostly
   harmless. But the **misdelivered words land in the column-0 cores' boot FSMs**: each is
   waiting in `DynamicReceiveCountDown` and consumes the first stray word (`body_len = 0`)
   as its countdown value, **starting execution immediately** — before the real countdown
   sweep arrives. The stray blocks stream ~30 cycles apart, so the cores start *staggered*:
   measured per-core `active` rising edges (sim trace): (0,0)=t, (0,1)=t+29, (0,2)=t+65,
   repeating every 460-cycle vcycle (periods are equal — body+epi+sleep = 460 for every
   core — so the boot stagger persists forever).

5. **The missed display**: the scheduler's NoC model assumes all cores issue schedule
   position P at the same global cycle. With (0,1) starting +29 late, every value it sends
   the master arrives +29 later than modeled. The master's RF-write FLUSH (one predicated
   instruction at position ~11, fired once per vcycle) reads a **loop-carried** predicate
   received from (0,1); with the stagger, the very first predicate value arrives *after*
   its first consumer read — so the first display (vc=2) never fires. Steady state
   self-corrects (constant stagger ⇒ the loop-carried chain settles), which is why
   everything else was exactly right.

6. Why the other configs passed: **2×2** — distance ties break *forward*, so no negative
   boot hops exist; **forward-only 4×4** — no negative hops by construction. Both boot
   aligned, both pass 31/31.

## 3. Evidence trail

- Per-core `active`-rise trace: constant +29/+65 stagger before the fix; **identical rise
  times (zero stagger) after the fix**.
- Per-core packet trace: (0,1)/(0,2) receive stray `data=0` packets ~2 cycles before
  activating; the master activates with no countdown packet at all (it consumed (3,0)'s
  stray `body_len=0`).
- Static: binary block parse shows `destHops=(−1,·)` for blocks 12–15, streamed last;
  code reading of the `xInput` handler shows the missing `xHops < 0` case.
- NoC delivery itself was never at fault: `NoCDropDetectionTester` shows 0 drops across all
  240 source/target pairs (plus a forced-collision negative control proving the detection
  works), and `HopLatencyTester` shows forward/backward latency symmetry.

## 4. The fix

`Switch.scala`, `xInput` handler — add the westbound crossover:

```scala
.elsewhen(io.xInput.xHops < 0.S) {
  x_neg_reg := NoCBundle.passX(io.xInput)   // crossover: go west
}
```

This branch can only fire for *injected* packets (in-transit eastbound traffic never has
`xHops < 0`), and restores dimension-order routing (X both signs first, then Y) for the
injection path. With it, (−1,−1)-style packets also route west-then-south to the correct
core.

Verified after fix: 2×2 **and** 4×4 pass exactly (53/32/31, eid 3, "VERIFIED" structural
match to the placed interpreter); per-core activation timestamps identical.

## 5. Hardening ideas (not yet done)

- Sim-only assertion in `Switch`: packets on the Y channels (`yInput`/`yNegInput`) must have
  `xHops == 0` (dimension-order invariant); packets on `xInput`/`xNegInput` carry the
  matching sign. Would have caught this immediately.
- The boot FSM (`Processor`) blindly trusts any terminal packet as its next boot word; a
  stray-packet guard (e.g., only accept during the Programmer's window for *this* core) is
  harder, but the assertion above makes strays loud in sim.
- `transactions.csv` (`masm --dump-all`) lists every NoC transaction (sender, receiver,
  schedule/enqueue/expected-recv cycles, registers) for hand-tracing schedules against RTL.

## 6. Key locations

| concern | location |
|---|---|
| the fix | `manticore-hw/.../core/Switch.scala` (`xInput` handler, westbound crossover) |
| boot injection path | `manticore-hw/.../core/ManticoreFlatArray.scala` (`config_packet` → master `xInput`) |
| boot FSM that consumed strays | `manticore-hw/.../core/Processor.scala` (`DynamicReceiveCountDown`) |
| signed boot dest encoding | `manticore-compiler/.../codegen/MachineCode.scala` |
| countdown alignment ("magic formula") | `manticore-hw/.../core/Programmer.scala` (`StreamCountDown`) |
| repro & golden | `manticore-hw` `Mips32SimTester`; `masm interpret -L` (31 displays) |
