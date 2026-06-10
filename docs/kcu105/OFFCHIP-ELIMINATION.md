# Investigation: eliminating the dependence on off-chip storage

Goal (per the request): stop the processing elements (PEs) relying on the off-chip-storage
mechanism — **not** by backing it with BRAM (which is what the KCU105 port currently does via
`axi_bram_ctrl`), but by removing the reliance itself. This documents what off-chip storage is
used for, the consequences of removing it, and the concrete changes required.

## 1. What off-chip storage (global memory) actually does

In Manticore, "off-chip storage" = the **global memory**: a single privileged core is connected
to a write-back **cache** which is backed by an off-chip **DRAM bank** (paper §5.2). On the KCU105
we replaced that DRAM bank with on-chip BRAM behind an `axi_bram_ctrl`. Global memory serves
**three distinct roles** — important, because they have very different consequences:

| # | Role | Mechanism | Who depends on it |
|---|---|---|---|
| **A** | **Spilling large design memories** | Privileged `GlobalLoad`/`GlobalStore` → cache → DRAM. A `$mem` is promoted to global memory when `size×width ≥ nScratchPad×16` (= 32 KiB) — `WidthConversionCore.scala:2317`. Smaller memories stay in per-core scratchpad. | The PEs (the actual "supplement on-chip storage" role from the paper §4) |
| **B** | **Boot / instruction loading** | The bootloader (`Programmer.scala`) reads each core's instruction stream from global memory via the cache (`memory_backend`), then streams it to cores as NoC config packets. Host writes the program into DRAM + sets `global_memory_instruction_base`. | The bootloader (not the PEs directly) |
| **C** | **`$display` traces + host result readback** | `InterruptLoweringTransform` allocates a `DefGlobalMemory` trace buffer; the privileged PE writes the format args there via `GlobalStore`; the host reads them back from DRAM (paper §A.3.2 host-inspect). | The privileged PE + host |

**The key existing lever:** the ISA already has a `WithGlobalMemory` flag.
`ManticoreBaseISA` sets it **false** (no global load/store/cache in the cores at all);
`ManticoreFullISA` (what the KCU105 build uses) sets it **true**. Flipping the array to a
no-global-memory ISA removes role **A** from the PEs — but **B** and **C** still need a global
memory today, so they must be re-homed before the off-chip bank can be deleted.

> Note from the paper (§7.4, footnote on benchmarks): *"The benchmarks were sized to ensure their
> state fit in the Manticore on-chip scratchpads."* So the paper's evaluation did **not** use
> off-chip DRAM for design memory — it used it for boot/host-I/O. Off-chip is the *capacity
> escape hatch* for designs whose memories don't fit on-chip.

## 2. Consequences of removing off-chip storage

### A. A hard design-size ceiling (the real trade-off)
Every design memory must fit in a **per-core scratchpad** (each `$mem` < 32 KiB; threshold above),
and the total design state must fit across the cores' scratchpads. Designs with large RAMs
(processor data/instruction caches, frame buffers, big FIFOs) **cannot be simulated** — those are
precisely the designs off-chip exists for.

Quantified for the xcku040 (scratchpad = one 4096×64 URAM≈BRAM reshaped to 16384×16 = **32 KiB/core**):
- 4×4 = 16 cores → **≤ ~512 KiB total design memory** (minus scratchpad used for intermediates).
- vs an off-chip bank measured in GB (or the 256 KiB BRAM bank we currently emulate it with).

For "small-state" RTL (the paper's regime, and our MIPS32 — its whole state is scratchpad-resident)
there is **no loss**. For memory-heavy designs it is a firm capacity limit.

### B. Boot can no longer stage instructions off-chip
Instructions currently transit DRAM. Without it, the program must reach the cores another way
(options in §3). The PEs are unaffected — they only ever see instructions as NoC config packets.

### C. `$display`/trace + result readback lose their channel
The host-inspect path (write trace to DRAM, host reads it) disappears. (In our 2×2/board harness
this path is *already* non-functional — the store-drain bug in `VERIFICATION.md` — so this is also
a chance to replace it with something that works.)

## 3. Required changes

### RTL (`manticore-hw`)
1. **Build the array with `WithGlobalMemory=false`** (use `ManticoreBaseISA` for the cores).
   This compiles out `GlobalLoad`/`GlobalStore` and the cores' global-memory interface
   (`Execute.scala:250`, `MemoryAccess.scala:50/112/134`, `Processor.scala:545`). Role **A** gone.
2. **Delete the cache + off-chip bank.** Remove `CacheSubsystem`/`AxiCacheAdapter` and, on the
   KCU105, the `axi_bram_ctrl` + `smartconnect` + the `m_axi` clock converters from the block
   design (`Kernel.scala` KCU105 generator + `vivado/kcu105/build_kcu105.tcl`).
3. **Re-home boot (role B).** Repoint `Programmer.io.memory_backend` from the cache to either:
   - (i) an on-chip **program BRAM initialised at bitstream build** (MEMORY_INIT) → *fixed-program*
     bitstream (rebuild to change the simulated design). Simplest; no host loader, no off-chip.
   - (ii) a small on-chip **staging BRAM loaded over JTAG-to-AXI** (sized to the program, ~KB–tens
     of KB), which the `Programmer` reads. Keeps reconfigurability without an off-chip bank.
   The `Programmer` already talks a generic `CacheConfig.frontInterface`, so it can be pointed at
   any memory that honours that interface — this is a localized change.
4. **Re-home trace/readback (role C)** — pick per appetite:
   - (i) **Drop `$display`/serial interrupts**; keep only `FINISH`/`STOP`/`ASSERT` (data-less)
     interrupts. Correctness is then checked via the exception id (self-checking benchmarks) and
     final-state inspection.
   - (ii) Add a **dedicated on-chip trace FIFO** drained over JTAG/AXI-Lite (a lightweight sink,
     *not* the cache path). The privileged PE writes to it instead of `GlobalStore`.
   - (iii) Add a **debug read port on the per-core scratchpad** to read final state over JTAG.
5. **Keep global clock-gating only for exceptions.** The clock-gating mechanism is used for both
   cache-stall *and* precise-exception stall (paper §5.2). Removing the cache removes the
   cache-stall use; keep the exception-stall use.

### Compiler (`manticore-compiler`)
1. Drive a **no-global-memory hardware config** so the scheduler/codegen never emit global
   load/store.
2. In `WidthConversionCore` (the `size×width ≥ nScratchPad×16` test at lines 2317/2394): instead of
   **promoting** an over-size memory to global, **error out at compile time** ("memory X does not
   fit on-chip; off-chip disabled") — so unsupportable designs are caught early instead of silently
   needing DRAM.
3. `InterruptLoweringTransform`: provide a no-global trace lowering matching the RTL choice in
   §3.4 (drop serial interrupts, or target the new trace channel). `mkGlobalMemory` for the trace
   buffer (line 84) goes away.
4. Boot-stream generation is unchanged (instructions are still produced); only the host-side
   *staging* of those bytes changes (build-time init vs JTAG load).

### KCU105 build (`vivado/kcu105`)
Drop `axi_bram_ctrl`, `smartconnect`, and the AXI clock converters from the block design; keep
`jtag_axi` for control + the new program/trace channels. `run_manifest.py` changes from "load image
into BRAM bank" to "init program BRAM / stream over JTAG; drain trace FIFO".

## 4. The payoff (resources + timing)

Removing the global-memory subsystem is not just neutral — it frees significant area on the small
xcku040 (measured from the 3×3 synth, sum of OOC runs):

| Block removed | LUT | BRAM tiles |
|---|---|---|
| `smartconnect` | **~7,950** | 0 |
| `axi_bram_ctrl` + BRAM bank | ~440 | **~64** |
| AXI clock converters | ~740 | 0 |
| cache (`CacheSubsystem`, part of kernel) | (in kernel) | **4 URAM-equiv (~8–16 BRAM)** |

For scale, the entire **9-core kernel is ~7,110 LUT** — i.e. the `smartconnect` alone costs *more
LUTs than the compute array*. Reclaiming it (plus ~70+ BRAM tiles) directly buys **more cores**
and/or eases PnR, and removing the cache-stall path off the global timing net should help **Fmax**.

## 5. Assessment & recommendation

- **Feasible and largely supported already** — the `WithGlobalMemory=false` ISA exists; the
  bootloader's memory interface is generic; the only design memory our verified benchmark (MIPS32)
  uses off-chip is the 7-word trace buffer.
- **Recommended target:** a **scratchpad-only Manticore** for on-chip-fitting designs (the paper's
  regime): cores at `WithGlobalMemory=false`, **boot from a JTAG-loaded on-chip program BRAM**
  (keeps reconfigurability, no off-chip), and **traces via a JTAG-readable FIFO** *or* dropped in
  favour of self-checking-via-exception + scratchpad readback (which also sidesteps the broken
  trace-store path).
- **The one thing you give up** is large-memory designs. Make that explicit at compile time so it
  fails loudly rather than silently requiring DRAM.
- **What you gain:** a simpler, fully-deterministic PE with no cache-stall path, a chunk of LUT and
  BRAM back (room for more cores), and likely higher Fmax — on a board where, unlike the paper's
  U200, every resource is scarce.
