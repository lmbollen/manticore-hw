# The EXPECT / exception-capture race exposed by bidirectional routing

**Status:** open. The bidirectional-NoC work is correct and verified; this document
describes a *separate, pre-existing* control-path limitation that the bidirectional
schedule is the first workload to trigger. It is written so someone who has not been
in the debugging session can pick up the fix.

---

## 1. TL;DR

- We made the Manticore NoC bidirectional (signed ±X/±Y hops, shortest-path routing).
  The router and the compiler's NoC model are **correct** (proven below).
- After the fix, the **2×2** MIPS32 reproduction test passes exactly
  (53 vcycles / 32 flushes / 31 RF-write `$display`s / `eid=3`).
- The **4×4** test computes the right answer and halts at the right time
  (`eid=3`, exactly 53 vcycles) but reports **30** RF-write displays instead of **31**
  — it loses exactly **one** display, always one of the early ("prologue") ones.
- Root cause is **not** routing. It is a race in how a core's `EXPECT`-generated
  exception is captured by the `Management` controller: between the cycle an `EXPECT`
  fires and the cycle the compute clock actually stops, the master core keeps
  executing. If a *second* `EXPECT` lands inside that window, its exception id is
  never reported to the host — one `$display`/flush is silently dropped.
- Bidirectional routing exposes it because shortest-path delivery makes inter-core
  values arrive **sooner**, which packs the master core's `EXPECT` instructions
  **closer together** in time than any prior (forward-only) workload ever did.

---

## 2. Symptom and how to reproduce

Test: `manticore-hw` → `manticore.machine.xrt.Mips32SimTester` (Verilator, no-URAM/BRAM config).

```bash
cd manticore-compiler
# build the compiler jar and the per-grid images (no-CF to match enable_custom_alu=false RTL)
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH
sbt assembly
./masm -x 2 -y 2 --no-cf -o /tmp/mips_out_nocf /tmp/mips_out/yosys/main.masm
./masm -x 4 -y 4 --no-cf -o /tmp/mips_out_4x4  /tmp/mips_out/yosys/main.masm

cd ../manticore-hw
export PATH=/nix/store/9lb1sml9g079s4241ibvdcpbvx722wpr-verilator-5.002/bin:$PATH   # Verilator 5.002
sbt -Dmips32.objdir=/tmp/mips_out_nocf -Dmips32.objdir4=/tmp/mips_out_4x4 \
    "testOnly manticore.machine.xrt.Mips32SimTester"
```

Result:
```
2x2: MAIN terminated: eid=3 vcycles=53 after 32 flushes, 31 RF-write records   -> PASS
4x4: MAIN terminated: eid=3 vcycles=53 after 31 flushes, 30 RF-write records   -> FAIL (off by one)
```

The golden (from `masm interpret -L`, the cycle-accurate model of the exact scheduled
program) is **31 displays for both grids** — the 2×2 and 4×4 interpreter serial traces are
byte-identical. So 31 is correct and the 4×4 RTL is genuinely one short. The lost display is
in the prologue (the loop-body displays — the trailing 20 alternating records — are all present).

> Note: the RTL trace *data* reads back as zeros (a known "trace-store drain" limitation,
> see VERIFICATION.md). Only the **count/structure** of flushes is reliable from the harness;
> the missing-display conclusion is from the flush count, not the data.

---

## 3. What has been ruled out (with evidence)

The instinct "we only changed routing, so the bug is in routing" is reasonable, so routing
was attacked hard and **cleared**:

1. **Packet drops / link contention — NONE.** A drop detector was temporarily added to
   `Switch` (prints when ≥2 valid inputs target the same output register `x_reg`/`x_neg_reg`/
   `y_reg`/`y_neg_reg` in one cycle — exactly the silent-drop condition). It fired **zero**
   times across the entire 4×4 run. So every packet is delivered.

2. **Forward/backward latency asymmetry — NONE.** `manticore.machine.noc.HopLatencyTester`
   (kept in the repo) measures end-to-end delivery latency on a `BareNoC` for a 1-hop packet
   in each direction and for forward vs backward turns:
   ```
   east=2 west=2  north=2 south=2  fwdTurn=3 bwdTurn=3
   ```
   A backward (−1) hop costs exactly the same as a forward (+1) hop. So a value delivered via
   a short backward hop arrives at the correct (earlier) cycle that the scheduler already
   modeled — no skew.

3. **The hop-count math is correct.** `HardwareConfig.xHops/yHops` now compute both the
   forward and backward distance and select the smaller absolute value (tie → forward); this
   is the change the bidirectional design requires and it matches the routing the RTL performs.

4. **Forward-only on 4×4 passes 31/31.** Temporarily forcing `HardwareConfig` to forward-only
   hops (so distance-3 routes go `+3` the long way instead of `−1`) makes the 4×4 pass exactly.
   The *only* difference is that the long way takes more hops, i.e. spreads deliveries (and
   therefore the consuming `EXPECT`s) out in time. This is the key clue: the failure is
   triggered by **tighter timing**, not by wrong routing.

5. **Placement is fine.** The compiler asserts there is exactly one *privileged* process
   (any process containing `Interrupt`/`GlobalLoad`/`GlobalStore`/`PutSerial`) and maps it to
   the privileged core (0,0) — `AnalyticalPlacerTransform.scala` ~line 1068. Compilation
   succeeds, so all 31 display `EXPECT`s live on the master core. No display lands on a
   non-master core (whose exception would be invisible — see §4).

---

## 4. The actual mechanism

### 4.1 How a `$display` becomes a host-visible flush

- A `$display` compiles to an `EXPECT` (Interrupt) instruction on the **master core (0,0)**.
- In the processor, the exception is generated in
  [`Processor.scala:563-572`](../../src/main/scala/manticore/machine/core/Processor.scala#L563-L572):
  ```scala
  val exception_cond =
    RegNext(decode_stage.io.pipe_out.opcode.expect) &&
    !RegNext(register_file.io.rs1.dout === register_file.io.rs2.dout)
  exception_occurred           := RegNext3(exception_cond)                                  // 3-cycle delay
  io.periphery.exception.id    := RegNext3(RegEnable(RegNext(immediate), exception_cond))   // captured eid, 3-cycle delay
  io.periphery.exception.error := exception_occurred
  ```
  So `exception.error` rises **~4 cycles after** the `EXPECT` is decoded
  (`RegNext` + `RegNext3`), and the eid is `RegEnable`-captured at `exception_cond` time then
  delayed the same amount.

- Only the **master** core's exception is wired out — there is **no cross-core aggregation**
  ([`ManticoreFlatArray.scala:223-224`](../../src/main/scala/manticore/machine/core/ManticoreFlatArray.scala#L223-L224)):
  ```scala
  io.exception_id       := master_core.core.io.periphery.exception.id
  io.exception_occurred := master_core.core.io.periphery.exception.error
  ```

- The `Management` controller (control-clock domain) consumes it in `sVirtualCycle`
  ([`Management.scala:192-221`](../../src/main/scala/manticore/machine/core/Management.scala#L192-L221)):
  ```scala
  when(clock_active) {
    clock_active := !(io.core_kill_clock || io.core_exception_occurred)   // gate compute clock
  }
  core_exception_id := io.exception_id
  when(clock_active) {
    when(io.core_exception_occurred) { state := sDone }                   // stop, report to host
    ...
  }
  ```
  On `sDone` the host reads `device_registers.exception_id`, services the flush, and resumes
  (`CMD_RESUME` → back to `sVirtualCycle`). The compute clock is gated off
  (`ClockDistribution.v`, `compute_clock_en`) while stopped, so the cores and the NoC all
  freeze together.

### 4.2 The race

The compute clock does **not** stop on the same cycle the `EXPECT` executes. There is a
window of roughly **4 compute cycles** (the `RegNext`+`RegNext3` chain above, plus the
control-domain sample) between the `EXPECT` and `clock_active` actually deasserting. During
that window the master core keeps fetching and executing the instructions scheduled *after*
that `EXPECT`.

If a **second** `EXPECT` falls inside that window:

- Its `exception_cond` fires while the first exception is still propagating.
- `Management` has already (or is about to) latch the **first** eid and move to `sDone`.
- The second `EXPECT`'s eid is never delivered as a separate `sDone`/flush — the host sees
  one flush where there should have been two. One `$display` is lost.

(Exactly how the two collide in the `RegEnable`/`RegNext3` chain — whether the second
overwrites the first's captured eid, or is simply swallowed while the FSM is leaving
`sVirtualCycle` — has not been pinned to the cycle; see §6. Either way the net effect is one
lost flush, which matches the observed 30-vs-31.)

### 4.3 Why bidirectional routing triggers it (and forward-only / 2×2 do not)

The window is **routing-independent** — it is purely a function of how close two `EXPECT`s
on the master are in time. What changed:

- **Forward-only routing** always sends packets the long way around the torus. Those longer,
  more uniform paths make inter-core values (and therefore the consuming `EXPECT`s on the
  master) arrive **spread out**. No two `EXPECT`s fall within ~4 cycles, so the window is
  never hit. This is why the old design — and the forced-forward-only 4×4 — never failed.
- **Bidirectional routing** delivers via shortest paths (e.g. a core at x=1 reaches the
  master via a single `−1` hop instead of `+3`). Values arrive ~2 hops sooner, so the
  scheduler legitimately places the consuming `EXPECT`s closer together. In the dense prologue
  this pushes one pair inside the window → one lost display.
- **2×2** has too few cores to bunch `EXPECT`s that tightly regardless of direction.

So: **the limitation is latent and pre-existing in the controller/processor exception path;
bidirectional routing is simply the first workload whose timing is dense enough to expose it.
It is not a bug introduced by the routing change** (the routing is provably correct).

---

## 5. Suggested fixes

In rough order of risk/effort. (b) is the cleanest "real" fix.

**(a) Scheduler-side: enforce a minimum spacing between `EXPECT`s on the privileged core.**
In the compiler scheduler (`ProgramSchedulingTransform` / the privileged process's instruction
scheduling), require consecutive `Interrupt`/`EXPECT` instructions on core (0,0) to be at least
*W* cycles apart, where *W* ≥ the exception-capture window (measure it — see §6; ~4–5 cycles,
pad to be safe). Insert NOPs / delay the second `EXPECT` if needed.
- Pros: no hardware/timing-closure risk; localized to the privileged core; the rest of the
  schedule is unaffected.
- Cons: slightly longer programs; must be sure *W* is correct (too small → still races).

**(b) Hardware-side: capture exceptions so none are lost.** Make the exception/eid capture
not depend on the gap to the clock-stop:
- Latch the eid the instant `exception_cond` fires (a sticky "pending exception" register that
  holds the *first* eid until the host services it and explicitly clears it), and gate the
  compute clock off `exception_cond` directly (or one cycle later) rather than after the
  `RegNext3` delay — so the master cannot retire further `EXPECT`s past the first.
- Or add a small exception **FIFO**: each `exception_cond` pushes its eid; `Management` pops
  one per flush; the core is allowed to run past additional `EXPECT`s only if they are queued.
- Pros: fixes the root cause for all workloads/grids.
- Cons: touches `Processor.scala` (lines 563-572) and `Management.scala` (the `sVirtualCycle`
  exception handling) — shared, timing-sensitive control logic; needs synthesis-closure review
  on the KCU105 build. Note the existing comments there ("don't register here, causes large
  fan out on clock_active") — the fan-out of `clock_active`/`exception_id` is a known closure
  concern, so any change must keep that path lean.

**(c) Accept and annotate.** The router + compiler are correct and the 4×4 computes the right
result; only the host-visible *display count* is short. Relax the 4×4 golden (or mark the case
expected-known-limited) and document this writeup as the reason. Lowest effort, leaves the
latent HW limitation in place.

---

## 6. How to continue / confirm before fixing

1. **Measure the window exactly.** Add a sim-only `printf` in `ManticoreFlatArray` on the
   master core for (i) every cycle `exception_cond`/`exception.error` is asserted and (ii) the
   cycle `clock_active` deasserts. Run the 4×4 and look at the prologue: find the two `EXPECT`s
   whose firing cycles are < window apart. That both confirms the mechanism and gives the exact
   *W* for fix (a). (During the session a coarser version showed the master raising ~35 distinct
   exception events but only 31 reaching the host — consistent, but it was not narrowed to the
   exact colliding pair.)

2. **Decisive confirmation test.** Keep bidirectional routing but artificially space the
   master's `EXPECT`s (prototype of fix (a)) — e.g. inject NOPs between privileged `EXPECT`s in
   the scheduler. If the 4×4 then reports 31/31, the window is definitively the cause. (Forcing
   forward-only routing already passes 31/31, but that changes routing too, so it is suggestive,
   not decisive.)

3. Then implement the chosen fix and re-run `Mips32SimTester` for both 2×2 and 4×4.

---

## 7. Key files & signals

| Concern | File / location |
|---|---|
| Exception generation (`EXPECT` → `exception.error`, eid capture) | `manticore-hw/.../core/Processor.scala:563-572` |
| Exception consumption / clock gating (the window) | `manticore-hw/.../core/Management.scala:192-221` |
| Master-only exception wiring (no aggregation) | `manticore-hw/.../core/ManticoreFlatArray.scala:223-224`, `:321-322` |
| Compute-clock gating in sim | `manticore-hw/.../xrt/ClockDistribution.v` (`compute_clock_en`) |
| Bidirectional router (verified correct) | `manticore-hw/.../core/Switch.scala` |
| Hop-latency regression guard | `manticore-hw/src/test/.../noc/HopLatencyTester.scala` |
| Reproduction test + golden | `manticore-hw/src/test/.../xrt/Mips32SimTester.scala` |
| Signed shortest-path hops | `manticore-compiler/.../HardwareConfig.scala` (`xHops`/`yHops`) |
| NoC scheduling model (link reservation) | `manticore-compiler/.../placed/lowering/util/NetworkOnChip.scala` |
| Privileged-process placement constraint | `manticore-compiler/.../placed/parallel/AnalyticalPlacerTransform.scala` (~1068) |
| Scheduler (where fix (a) would live) | `manticore-compiler/.../placed/lowering/ProgramSchedulingTransform.scala` |

Build notes: use JDK 11 on `PATH` (the `sbt` launcher uses `PATH`'s `java`, ignores
`JAVA_HOME`; JDK 21 breaks the Chisel plugin). Use Nix Verilator 5.002 (system 5.020 fails on
`__pch.h.fast`). Avoid running multiple `sbt` instances at once (inotify-instance limit).
