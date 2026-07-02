# KCU105 topology resize: 4×4 vs the current 2×2 vs the paper

We reshaped the Manticore array from **2×2 (4 cores)** to **4×4 (16 cores)** to make better use of
the xcku040 without pushing place-and-route to the edge. This records the measured numbers and what
they mean for performance.

> **[Updated 2026-06-30]** All resource/timing figures in this doc were measured on the **older
> KCU105 architecture** — *with* the write-back **cache** and the 256-bit **`m_axi`** master
> (external `axi_bram_ctrl` + AXI clock crossers), at **ap_clk 200 / compute 100 MHz**. That
> architecture has since been replaced by an **in-kernel fixed-latency BRAM** (`GmemBramBackend`,
> no cache, no AXI master) and the **default clocks are now 100/100 MHz** (`build_kcu105.tcl:41`).
> Resource use therefore differs (no cache, no AXI clock converters, gmem BRAM now inside the
> kernel) and the numbers below should be treated as **not re-measured** for the current build.

## Resource utilization (xcku040-ffva1156-2-e, ap_clk 200 MHz / compute 100 MHz)

> **[Updated 2026-06-30]** Old architecture (cache + 256-bit `m_axi`). Current default is
> 100/100 MHz with in-kernel BRAM and no cache/AXI crossers — figures below not re-measured.

| Grid | Cores | BRAM tiles (of 600) | LUT (of 242k) | FF (of 485k) | DSP | Source |
|---|---:|---:|---:|---:|---:|---|
| **2×2** (baseline) | 4 | ~170 (**~28%**) | ~14k (~6%) | ~26k (~5%) | 4 | extrapolated¹ |
| 3×3 | 9 | 261 (**43%**) | 16.9k (7%) | 31k (6%) | 9 | OOC synth (measured) |
| **4×4** (built) | 16 | **386.5 (64.4%)** | **19,992 (8.25%)** | **38,330 (7.9%)** | 16 | **post-route (measured)** |
| 5×5 | 25 | ~600 (≈100%) | ~24k (~10%) | — | 25 | extrapolated — won't fit |

¹ 2×2 extrapolated from the two measured points (≈18 BRAM tiles/core + ~100 fixed for the BRAM
controller/IPs); consistent with the earlier xcku035 stand-in build (31% of 540).

**BRAM is the binding resource** (LUT/FF/DSP stay in single digits %). 4×4 lands at **64% BRAM** —
4× the compute of the 2×2 while keeping ~36% headroom, so PnR closes comfortably. 5×5 overflows.

## Timing (200/100 MHz)

> **[Updated 2026-06-30]** Measured on the old cache + `m_axi` build at 200/100 MHz; the current
> default is 100/100 MHz (the in-kernel gmem BRAM's port-A path was marginal at 200 MHz). Treat
> the slacks below as historical for that architecture.

| Grid | Setup WNS | Hold WHS | Status |
|---|---:|---:|---|
| 2×2 | +0.68 ns | met | clean (prior build) |
| **4×4** | **+0.629 ns** (met) | **−0.027 ns (5 endpoints)** | setup met w/ headroom; tiny hold violation |

The 4×4 **meets setup with +0.629 ns of slack** on the 200 MHz control clock; the cores' 100 MHz
compute clock has even more. There are 5 endpoints with a **−27 ps hold violation** — a routine
fixup (`phys_opt_design -directive AggressiveExplore` / a hold-fix pass), not a frequency wall. The
positive setup slack means there is **headroom to raise the clocks** later (the paper runs the
compute domain at ~475 MHz; we conservatively use 100 MHz).

## Performance — what "more cores" actually buys

Manticore's simulation speed is:

```
design-clocks/sec  =  compute_clock_freq  /  virtual_cycle_length
```

where `virtual_cycle_length` ≈ the max instructions mapped to any one core per simulated cycle
(plus NoC sync). More cores help **only** if the design has parallelism beyond the current core
count — they shorten `virtual_cycle_length` by spreading work over more cores. They do **not** speed
up a design that already fits comfortably in fewer cores.

Measured for our verified benchmark (MIPS32, `--no-cf`):

| | 2×2 | 4×4 |
|---|---:|---:|
| main virtual-cycle length | 462 | **460** |
| sim speed @ 100 MHz | ~217 k design-clk/s | ~217 k design-clk/s |

**MIPS32 is too small to benefit** — it barely fills 4 cores, so 16 cores leaves the vcycle length
(≈461) and thus the simulation speed essentially unchanged. This is expected and matches the paper's
observation that throughput depends on the design's available parallelism, not raw core count.

**So the 4×4 win is capacity, not speed-for-small-designs:**
- **4× the cores and 4× the on-chip scratchpad** for design state (~16 × 32 KiB ≈ 512 KiB total) —
  it can hold and exploit parallelism in designs ~4× larger / wider than the 2×2 can.
- For a design with enough parallelism, the vcycle length drops toward (total-instructions / 16)
  instead of (/4), i.e. up to ~4× faster — the regime where adding cores pays off.

## Versus the paper (Alveo U200 / VU9P)

| | This work (KCU105 / xcku040) | Paper (U200 / VU9P) |
|---|---|---|
| Device class | small Kintex dev board | large UltraScale+ datacenter card |
| Grids reported | 2×2 … **4×4** | **8×8 … 16×16** (64 … 256 cores) |
| Compute clock | **100 MHz** (setup has headroom for more) | **450–500 MHz** (Table 1) |
| Off-chip backing | none/BRAM-emulated (no DRAM, no Vitis) | real DRAM bank + XRT |

The paper's *smallest* grid (8×8 = 64 cores) is already 4× our 4×4, on a far larger FPGA, clocking
~4.75–5× faster. So in absolute peak throughput we are well below the paper — expected: the xcku040
is a fraction of the VU9P and we run the compute clock 5× slower. Two honest framings:

- **Per-core, normalized for clock**, our cores are the same microarchitecture; the gap is purely
  device size (cores) and clock. The +0.629 ns setup slack says the clock gap is partly our
  conservative 100 MHz choice, not a hard limit.
- **The contribution here** is fitting Manticore on a small, DRAM-less, Vitis-less board at all
  (BRAM-backed, JTAG-to-AXI) and scaling it to use the device well (2×2 → 4×4, 28% → 64% BRAM,
  timing met). The paper's scaling/floorplanning results (degradation past 12×12 on the U200) don't
  bite at our sizes.

## Bottom line

- **4×4 is the right resize for the xcku040**: 16 cores, 64% BRAM, setup met with headroom, only a
  trivial hold cleanup outstanding. 5×5 doesn't fit; 3×3 (43%) under-uses the device.
- It quadruples capacity (cores + on-chip design-state memory) and, for designs with enough
  parallelism, up to ~4× simulation speed. Small designs like MIPS32 see no speedup (nor regression).
- Next levers if more performance is wanted: **raise the compute clock** (positive setup slack
  suggests room beyond 100 MHz) and, per OFFCHIP-ELIMINATION.md, **reclaim the smartconnect/cache
  area** (~8k LUT + ~70 BRAM) to push toward 5×5 once the BRAM bank is removed.
  > **[Updated 2026-06-30]** Partly done: the **cache** is already gone (replaced by in-kernel
  > BRAM). The **smartconnect/`axi_bram_ctrl`** are still present (now the JTAG host window onto
  > the GMEM port), so their LUT is not yet reclaimed — that needs a different host bring-up.

Artifacts: rebuild via `build_kcu105.tcl` into a chosen `<build_dir>`; the bitstream and reports
land in `<build_dir>/manticore_kcu105.runs/impl_1/` (`system_wrapper.bit`,
`*_utilization_placed.rpt`, `*_timing_summary_routed.rpt`). (The original run used a volatile
`/tmp/...` build dir — not a stable path.)
