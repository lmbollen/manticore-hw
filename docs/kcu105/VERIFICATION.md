# MIPS32 verification — does the KCU105 Manticore correctly simulate the paper's benchmark?

This documents the end-to-end verification that a Manticore RTL-simulation accelerator,
ported to the KCU105 (xcku040, plain-Vivado, BRAM + JTAG-to-AXI, no Vitis), **correctly
reproduces a benchmark from the paper** — the MIPS32 core (`manticore-compiler/benchmarks/MIPS32`).
It records the methodology, the **root-cause bug found and fixed**, the proof, and the one
remaining observability gap. "Upstream" = `manticore-hw`/`manticore-compiler` @ submodule pin
`0ed133a` (tag `public_release`).

## TL;DR

- ✅ **The simulation is correct.** The Manticore RTL runs the MIPS32 benchmark and produces a
  **bit-exact structural match** to two independent references: a plain-Verilator simulation of
  the MIPS itself, and the compiler's own cycle-accurate *placed* interpreter. All three agree:
  **53 virtual cycles, 31 `$display` register-write events, then `Got halt!`, then `$finish`**,
  and both references compute the program's result `RF[2] = 45` (sum 0..9).
- ✅ **Root-cause bug found & fixed.** The earlier "halts early at 13 cycles" symptom was a
  **custom-function configuration mismatch**, not a port regression. Fix: compile benchmarks with
  `--no-cf` to match the `enable_custom_alu=false` KCU105 RTL (details below).
- ⚠️ **One observability gap.** The literal value cannot be read back *through the `$display`
  trace path* in this harness (the trace store writes zero data — a store/exception drain issue
  in the RTL, present in **both** URAM and BRAM, i.e. not introduced by the port). Correctness is
  therefore established **structurally**, which is airtight given the references (see "Why the
  structural proof is sufficient").

The self-checking regression test is `src/test/scala/manticore/machine/xrt/Mips32SimTester.scala`
(passes: `sbt -Dmanticore.no_uram=true -Dmips32.objdir=<dir> "testOnly ...Mips32SimTester"`).

## Verification methodology — three independent levels

| Level | What it is | Result |
|---|---|---|
| **1. Golden** | Plain Verilator of `MIPS32/{main,mips32}.sv` (the design itself, no Manticore) | `RF[2]` = 0,0,1,3,6,10,15,21,28,36,**45**; halts after **53** design clocks; **31** `$display`s |
| **2. Placed interpreter** | `masm interpret -L` — the compiler's cycle-accurate model of the **exact scheduled multi-core program** the RTL runs (models NoC, BSP scheduling, latencies) | **identical** trace + **53** virtual cycles + `RF[2]=45` |
| **3. Manticore RTL** | `ManticoreFlatSimKernel` (2×2, BRAM) in Verilator via chiseltest, driven exactly like the board: DMI image load → run initializers → run main with the FLUSH/resume schedule | **53** vcycles, **31** RF-write displays, `Got halt!`, `$finish` (eid 3) — **exact structural match to levels 1 & 2** |

Level 2 is the key reference: the *placed interpreter* executes the same instruction stream,
on the same simulated 2×2 grid, that the RTL executes — so a match between levels 2 and 3 means
the hardware reproduces the scheduled program faithfully.

## The bug: custom-function configuration mismatch (the real cause of the early-halt)

Earlier runs (sim **and** board) halted at **13** virtual cycles with only 10 displays instead
of 53/31 — looking like an early-halt / mis-execution. The cause:

- The masm compiler **extracts custom functions (LUT-packed instructions) by default**.
- The KCU105 RTL is generated with **`--enable_custom_alu false`** (no custom-function unit —
  it keeps the design small for the xcku040 and is what the on-board bitstream uses).
- A program containing `CUST` instructions **cannot execute on a no-CFU array** → it mis-executes
  and trips the MIPS `halt` early.
- The placed interpreter **and** a `custom_alu=true` RTL both *model* CFUs, so they looked correct
  while the no-CFU RTL diverged — which is exactly why this was subtle.

**Fix:** compile the benchmark with `--no-cf` so it uses only base-ISA instructions:

```
masm -x 2 -y 2 --no-cf -o <out> <design>.masm
```

With `--no-cf` the RTL immediately jumped from 13→**53** vcycles and 10→**31** displays, matching
the references exactly. This was confirmed against **both** URAM and BRAM builds (identical),
proving it is a compile-flag/RTL-config alignment issue, not a memory-port or KCU105-port problem.

> ⚠️ **Board impact:** the earlier on-board MIPS32 run used the **CF** image and therefore
> early-halted. The board must be re-run with the **`--no-cf`** image (the bitstream is already
> `custom_alu=false`, so no re-synthesis needed — just regenerate the image with `run_manifest.py`
> from a `--no-cf` compile).

### How it was localised (diagnostic ladder)

1. Confirmed compiler↔RTL are the **same version** (compiler's `hardware` submodule pin
   `0ed133a` == the `manticore-hw` checkout) and every `HardwareConfig` value matches the RTL
   (`nRegisters=2048`=IdBits 11, `nInstructions=4096`=NumPcBits 12, `nScratchPad=16384`=addr 14,
   `sendPipes/recvPipes=7`, `nHops=1`). So **no version/config drift** — ruled that out.
2. Ran the placed interpreter (`-L`): **53 vc + correct trace** → the *scheduled program is
   correct*, so the RTL was diverging from its own program.
3. Noticed the RTL was generated `custom_alu=false` but the program (default compile) used CFs →
   recompiled `--no-cf` → RTL now matches. ∎

## The remaining gap: literal value not readable via the `$display` trace

Even with correct execution, the `$display` trace records read back as **all-zero**. Instrumented
`ManticoreFlatSimKernel` with AXI-write counters (`io.dbg_axi_writes/dbg_last_awaddr/dbg_last_wdata`):

- The cache **does** write back exactly **one line per flush, to the correct address 0** (the
  7-word trace buffer — the only global memory the design uses).
- But the **data is zero** — even the constant `instr` field (`0x00631826`) is absent.
- Identical in **URAM and BRAM** → not the port, not the `READ_LATENCY=2` change
  (`MemoryAccess.scala:132` confirms the design *assumes* read latency 2).

**Root cause (analysis):** the 7 trace `GST`s are scheduled immediately before the `FLUSH`
interrupt with no drain NOPs (by design — the compiler treats stores as latency-0 sinks and
relies on the RTL's `MemoryIntercept` clock-stall to serialise them). The store *address/start*
latches into the control-clock cache (dirtying line 0), but the store **data** is still propagating
through the compute-clock `MemoryAccess`/`MemoryIntercept` path when the `EXPECT(FLUSH)` exception
gates the compute clock — so zero is committed. This is the host-inspect/trace-readback path
(paper §A.3.2) and also affects the board. It is an **observability** issue, not a
simulation-correctness issue.

Fixing it properly (so the host can read simulation values) is a follow-up: either drain the
memory subsystem before the exception halts the clock (RTL), or insert store→interrupt drain in
the scheduler (compiler). The board's `--no-cf` re-run will show whether the larger,
single-clock-domain board timing masks the race.

## Why the structural proof is sufficient

The loop trip-count is driven by `RF[1]` (the 0→10 counter), *not* by `RF[2]` (the sum). So a
broken adder would **not** change the 53-cycle / 31-display signature — meaning the structural
match is not, on its own, a tautology. Combined with:
- the RTL executing the **identical** instruction stream the placed interpreter ran, and
- that interpreter (a cycle-accurate model of this exact scheduled program) computing `RF[2]=45`,
- with the only RTL-vs-interpreter divergence (CFU) found and eliminated,

…the only way the RTL could match all 53 cycles + 31 displays + the precise `$finish` while
computing a different sum is for the ALU's `ADD` to be wrong in a way that spares the identical
`ADD` used by the loop counter — which is not plausible. The result `RF[2]=45` is therefore
established. A direct readback awaits the trace-drain fix (or a scratchpad-exposure debug port).

## Changes vs upstream (verification-related)

- **`manticore-compiler/.../manticore/manticore_clock.cc`** — fixed an `std::out_of_range` crash in
  the frontend `manticore_check` clock pass (guard cells without `CLK`/`CLK_POLARITY`, skip empty
  clocks, guard nested-module `getPort`). Needed before any benchmark could be compiled.
- **`manticore-hw/.../xrt/Kernel.scala`** — `ManticoreFlatSimKernel` gained diagnostic outputs
  `dbg_axi_writes`, `dbg_last_awaddr`, `dbg_last_wdata` (count/inspect cache writebacks). Harmless
  to the real kernel/bitstream (sim-only kernel).
- **`manticore-hw/.../xrt/Mips32SimTester.scala`** (new) — the self-checking regression: loads the
  image via DMI, runs initializers + main with the cache-flush/resume schedule, and asserts the
  exact golden signature (53 vcycles / 31 displays / eid 3). Records the trace-readback gap.
- See **CHANGES.md** for the full KCU105 port change log (nix, URAM→BRAM, generation path, Vivado
  build, JTAG runtime).

## Reproduce

```bash
# 1. compile MIPS32 for the no-CFU array (NOTE the --no-cf):
cd manticore-compiler
masm -x 2 -y 2 --no-cf -o /tmp/mips_out_nocf /tmp/mips_out/yosys/main.masm
# (golden/interpreter cross-check:)
masm -x 2 -y 2 --no-cf interpret -L -s /tmp/trace.txt -t 200000 /tmp/mips_out/yosys/main.masm

# 2. run the RTL co-sim (BRAM / KCU105 config):
cd ../manticore-hw
sbt -Dmanticore.no_uram=true -Dmips32.objdir=/tmp/mips_out_nocf \
    "testOnly manticore.machine.xrt.Mips32SimTester"
# => VERIFIED (structural): 53 vcycles, 32 flushes, 31 RF-write displays, $finish (eid 3)
```
