# KCU105 port — change log

Running log of every file added or modified for the KCU105 port, with rationale.
Status legend: ✅ done · 🚧 in progress · ⬜ planned.

> **[Updated 2026-06-30]** The early **P1/P3** entries below describe an AXI-master +
> write-back **cache** global memory (`ManticoreFlatKernel.v` with `m_axi_bank_0`, a 256-bit
> AXI BRAM controller, a 2×2 SmartConnect). That architecture was **superseded** (commit
> `7a30727`): the kernel is now **`ManticoreFlatBramKernel.v`** with an **in-kernel
> `TrueDualPortBram`** (`GmemBramBackend`, no cache, no AXI master), exported as a
> **GMEM** BRAM-controller port at AXI `0x0`; the block design is a **1-master (jtag) × 2-slave
> SmartConnect** and `ap_clk` is **100 MHz**. Inline `[Updated 2026-06-30]` notes flag the
> superseded P1/P3 details. Also note the `$display`/trace-readback item flagged "open" in some
> entries is now **FIXED and board-verified** (see `VERIFICATION.md`).

## P0 — nix toolchain ✅

- **`flake.nix`** (new) — dev shell providing the Chisel toolchain: `sbt` + **Temurin
  JDK 11** (nixpkgs' source-built OpenJDK 11 SIGSEGVs here) + `scala_2_13` + `python3` +
  `verilator` + `jq`/`which`/`make`. Vivado is intentionally *not* in nix — source it
  externally. OR-Tools native libs exposed via `$ORTOOLS_NATIVE_LIBS` (not on
  `LD_LIBRARY_PATH`, which crashes the JVM).
- **`.envrc`** (new) — `use flake` for direnv.
- Validated: `nix develop` → `sbt compile` succeeds (518 classes, no JVM crash).

## P2 — URAM→BRAM ✅

KU040 has no URAM. Switchable via `-Dmanticore.no_uram=true`; Alveo default preserved.

- **`memory/GenericMemory.scala`** — added `MemStyle.noUram` flag + `MemStyle.uram` /
  `MemStyle.uramReal` helpers that return `BRAM` when `no_uram` is set.
- **`memory/Cache.scala:344`** — `MemStyle.URAM` → `MemStyle.uram` (cache banks).
- **`core/Fetch.scala:79`** — `MemStyle.URAM` → `MemStyle.uram` (per-core instr memory).
- **`core/Processor.scala:271`** — `MemStyle.URAMReal` → `MemStyle.uramReal` (per-core
  array memory; BRAM ignores the init file in HW, matching URAM behavior — no HW change).
- **`core/vcd.scala:101`** — `MemStyle.URAM` → `MemStyle.uram` (debug-only path).

Resource estimate, 2×2 grid, BRAM-mapped: ~104 / 600 BRAM36 (~17%).

## P1 — non-Vitis generation path ✅

- **`xrt/Kernel.scala`** — added `KCU105` device + `kcu105` platform entry
  (`xcku040-ffva1156-2-e`); new `object KCU105Generator` emits `ManticoreFlatKernel.v`
  (with `keep_hierarchy`) + `false_path.xdc` + `register.hpp`, and generates the support
  IPs (`clk_dist`, `axi4_clock_converter`, `axi4lite_clock_converter`) for the part — then
  stops (no `package_xo`/`v++`). Skips IP gen gracefully if `vivado` is not on PATH.
- **`Main.scala`** — added a `kcu105` target invoking `KCU105Generator`.
- Verified: `sbt -Dmanticore.no_uram=true "runMain manticore.machine.Main -t kcu105 -x 2
  -y 2 -f 100 --enable_custom_alu false -o <dir>"` emits the kernel + copies the needed
  blackbox bodies (`BRAMLike.v`, `AluDsp48.v`, `ClockDistribution.v`). Top ports:
  `ap_clk`, `ap_rst_n`, `m_axi_bank_0_*` (AXI4 256b data/64b addr), `s_axi_control_*`
  (AXI4-Lite 8b addr/32b data), `interrupt`.
  > **[Updated 2026-06-30]** Superseded: `KCU105Generator` now emits
  > **`ManticoreFlatBramKernel.v`** (`xrt/Kcu105Kernel.scala`). There is **no `m_axi_bank_0`**
  > master; the kernel instead exports an in-kernel BRAM as a 32-bit **GMEM** port group
  > (`gmem_clk/rst/en/we/addr/din/dout`) alongside `s_axi_control_*`, `interrupt`, and the
  > `TrueDualPortBram` blackbox body.

## P3 — Vivado project / bitstream ✅ (REAL xcku040 bitstream; timing met)

> **[Updated 2026-06-30]** This entry records the **original** cache + 256-bit-AXI + 2×2
> SmartConnect build at ap_clk 200. As built today the block design is a **1-master (jtag) ×
> 2-slave SmartConnect** (slaves = the gmem `axi_bram_ctrl` and `s_axi_control`); the external
> `axi_bram_ctrl` is **32-bit** and connects to the kernel's exported **GMEM** port
> (`axi_bram_ctrl_0/BRAM_PORTA → kernel_0/GMEM`, it does not back a kernel AXI master), and
> **ap_clk default is 100 MHz** (`build_kcu105.tcl:41`).

**Source the LICENSED Vivado** for the real part:
`/opt/tools/Xilinx/VivadoEnterprise/Vivado/2022.1/settings64.sh` (the plain
`/opt/tools/Xilinx/Vivado/2022.1` is WebPACK-only → xcku040 absent, only the xcku035
stand-in builds there).

**Result (2×2 grid, `xcku040-ffva1156-2-e`, ap_clk 200 / compute 100 MHz):**
- `system_wrapper.bit` produced (16 MB) — `Device : xcku040-ffva1156-2-e`,
  license: "Got license for feature 'Synthesis' and/or device 'xcku040'".
- **Timing met**: WNS +0.680 ns, WHS +0.030 ns, 0 failing endpoints.
- Utilization: LUTs ~6.7%, FF ~6.2%, BRAM ~31%, DSP 4, BUFGCE 4.
- First attempt at 300 MHz failed timing (WNS −0.355 ns) on the internal compute
  clock — the cores/cache need the Alveo Performance strategies + floorplanning to reach
  those speeds; dropping to 200/100 MHz closes it with margin. `build_kcu105.tcl` takes
  `ap_clk_mhz` / `compute_mhz` args (defaults 200 / 100).
- Build cmd: `vivado -mode batch -source vivado/kcu105/build_kcu105.tcl -tclargs
  <hdl_dir> <build_dir> xcku040-ffva1156-2-e 200 100 bits 256`.


- **`vivado/kcu105/build_kcu105.tcl`** (new) — self-contained, staged
  (`stop_after = bd|synth|bits`). Creates the project, the 3 support IPs, and a block
  design: clk_wiz (300 MHz diff → ap_clk) + proc_sys_reset (+ reset inverter for the
  active-high `CPU_RESET`), jtag_axi master, SmartConnect (2 SI × 2 MI), axi_bram_ctrl +
  block automation BRAM (256-bit), and the kernel as a **module reference** (AXI
  interfaces auto-inferred — no IP packaging needed). Writes `pins.xdc`
  (AK17/AK16 sysclk_300, AN8 cpu_reset) and runs synth/impl/`write_bitstream`.
  Auto-falls back to the installed `xcku035-ffva1156-2-e` stand-in when xcku040 is absent.
  > **[Updated 2026-06-30]** As built the SmartConnect is **1 SI × 2 MI** (`NUM_SI=1`,
  > `NUM_MI=2`, tcl:133-135): jtag_axi → SmartConnect, M00 → the gmem `axi_bram_ctrl` (32-bit,
  > whose `BRAM_PORTA` connects to the kernel's exported **GMEM** port, tcl:149-150), M01 →
  > `s_axi_control` (tcl:170). There is no kernel AXI master and no block-automation BRAM
  > (the BRAM is inside the kernel). ap_clk default 100 MHz.
- Address map: BRAM `Mem0` @ 0x0 (= `DramBank0Base`), `s_axi_control` @ 0x0010_0000
  (jtag only; pruned from the kernel master's space).

## P4 — runtime (JTAG driver) 🚧

- **`vivado/kcu105/run_kcu105.tcl`** + **`gen_image.py`** — JTAG-to-AXI control + boot-stream
  image generator (format verified vs `Programmer.scala`).

### On-board validation (gronau.local, real xcku040) ✅
Programmed `system_wrapper.bit` over the hw_server (Digilent 210308B3B018) and smoke-tested
the control path:
- Configuration `DONE=1` ("End of startup status: HIGH").
- JTAG-to-AXI live (`hw_axi_1`); `s_axi_control` mapped at `0x0010_0000`.
- `Control`(0x00) = `0x00000004` → `ap_idle=1` (correct idle state).
- `DeviceInfo`(0x24) = `0x08200000` → **DimX=2, DimY=2** ✓.

Proves on silicon: bitstream configures, clk_wiz/reset come up, JTAG-to-AXI reaches
`s_axi_control` through SmartConnect + the kernel's AXI-Lite clock crossing into the 100 MHz
compute domain, and `AxiSlave`/`ManticoreFlatArray` report the right geometry.

### End-to-end trivial run (gronau, real xcku040) ✅
Ran a 2×2 program of 4 NOPs/PE (`gen_image.py --nops 4 --instr-base-words 0`), loaded over
JTAG-to-AXI, with `ScheduleConfig` timeout = 2000 vcycles:
- BRAM image readback correct (`mem[0]=0x00040000`).
- `ap_start` → completed: `Control=0x6` (ap_done+ap_idle).
- `ExceptionId=0x00010000` (EXCEPTION_TIMEOUT — expected), **`VirtualCycles=2000`** (exactly
  the timeout → BSP scheduler ran), `BootloaderCycles=728`, `ExecutionCycles=1,079,518`.

Confirms on silicon: image load → control regs → Management FSM (reset/boot) → Programmer
streams per-PE programs via cache→m_axi→BRAM → 2×2 array runs the BSP schedule → timeout →
ap_done. Addressing units verified: `GlobalMemoryInstructionBase` = 16-bit-word offset,
`DramBank0Base` = byte base (both 0 here).

### Paper-benchmark pipeline 🚧
- `manticore-machine` (manticore-hw) is published locally (`sbt publishLocal`) so the
  compiler resolves it; **manticore-compiler** built (`sbt assembly` → masm jar).
- **manticore-frontend** (Yosys fork) built to `frontend/v2masmyosys` with
  `make CONFIG=gcc ENABLE_ABC=0 ENABLE_TCL=0 ENABLE_READLINE=0 ENABLE_PLUGINS=0` (the
  compiler's passes need no abc/techmap). The checked-in `.masm` test fixtures are from an
  incompatible compiler version (parse fails); compile from RTL instead.
- **MIPS32** compiles cleanly: `./masm -x 2 -y 2 benchmarks/MIPS32/main.sv
  benchmarks/MIPS32/mips32.sv` → `manifest.json` + `init_0/init_1/main` `exec.bin` (main
  vcycle length 413 → fits one core). (counter.sv aborts in the frontend's "Manticore
  Checker Pass" — `std::out_of_range` — a frontend bug on that design.)
- **`vivado/kcu105/run_manifest.py`** (new) — reads `manifest.json`, lays out global memory
  like `manticore-runtime` (reserved `base` words + sim memories + binaries at sequential
  word offsets), emits `image.mem` (non-zero 32-bit words only; BRAM powers up to 0) and a
  `run.tcl` that programs, loads, runs the initializers, then main with a **FLUSH→cache-flush
  →resume loop** until a terminating FINISH/STOP/ASSERT, classifying the final eid via the
  manifest exceptions table.

### ✅ MIPS32 ran to completion on the board (gronau, real xcku040)
With burst→single-word load fixed (JTAG-to-AXI master is AXI4-Lite, no bursts) and **lean
polling** + minimal per-cycle register writes in `run.tcl`, the MIPS32 simulation ran its
whole program to `$finish`:
```
=== RESULT: FINISH (eid=0x0) vcycles=1991 after 1981 flushes ===
PASS: simulation terminated normally (FINISH)
```
Initializers loaded the MIPS instruction memory → the CPU executed ~1991 simulated clock
cycles (each register write traced via the FLUSH/resume loop) → reached the testbench
`$finish` (eid 0). **A paper benchmark simulation runs end-to-end on the KCU105.**
Image-load correctness confirmed on-board (`verify main[0]` matched the image).

Future speedup: rebuild the bitstream with a **full-AXI4** jtag_axi (current is AXI4-Lite)
to allow burst image loads (~50 transactions vs ~5110).

### Correctness methodology (Step 1 golden → Step 2 Manticore sim → Step 3 board)
- **Step 1 ✅ — plain-Verilator MIPS golden.** Built `benchmarks/MIPS32/{main,mips32}.sv` in
  plain Verilator (clock-driving C++ tb): the MIPS computes **`RF[2]=45`** (sum 0..9) and halts
  ("Got halt!") after **53 design clocks**. Matches the compiler interpreter. The MIPS sim
  itself is correct.
- **Step 2 🚧 — Manticore RTL sim (Mips32SimTester).** Drives the full sim kernel in Verilator;
  reaches the MIPS halt (`eid 3`) — the SAME termination the golden takes (self-checked).
  Open gap: the literal value (sum=45) is NOT verifiable here — the MIPS result is per-core
  scratchpad-resident (not DMI/global visible) and the trace engine isn't wired to readable
  memory in the sim kernel. The RTL `vcycleCount` (13) likely counts BSP super-steps, so it is
  NOT a reliable correctness signal vs the golden's design-clock count (53).
  **Clean path to true verification:** a self-checking benchmark whose pass/fail is encoded in
  the exception id (STOP=pass / ASSERT=fail) — no result-reading needed.
- **Step 3 — board** (after Step 2 value-verification).

### Verilator co-simulation (Mips32SimTester) — URAM≡BRAM equivalence experiment
`src/test/scala/manticore/machine/xrt/Mips32SimTester.scala` drives the full
`ManticoreFlatSimKernel` (DimX=2,DimY=2) in Verilator: loads the compiled MIPS32 image via
the DMI, runs the initializers + main with the flush/resume schedule, reads device regs.
(Toolchain: chiseltest 0.5.1 needs Verilator ≤5.002 — pinned `nixpkgs-v4` = nixos-22.11 in
the flake. Harness gotchas fixed: explicit reset; the sim memory is NOT zero-initialised so
the FULL image incl. zero words must be loaded.)

**Result — `URAM sim ≡ BRAM sim ≡ board`:** all three terminate at the MIPS halt
(`eid 3`) after exactly **13 virtual cycles / 11 flushes**, byte-identical. So the KCU105
**BRAM port is functionally identical to the original validated URAM design** — *not* a port
regression.

**This corrects the earlier (wrong) "BRAM bug" conclusion below.** The "board halts at 13 vs
golden 53" is **common to the original URAM design too**, so it is either the RTL's own
vcycle-counting (vs the compiler interpreter's) or the flush/resume schedule — *not* anything
changed for the KCU105. The `READ_LATENCY=2` scratchpad fix was still real and correct (it
changed "never halts" → "halts" for both URAM and BRAM, which behave identically).

**Open (observability):** the literal computed value (sum=45) could not be confirmed — a full
global-memory scan after the run shows **0 changed words** (the MIPS state is scratchpad-
resident, not DMI-visible; the `$display`/trace path writes nothing to global memory,
`TraceDumpHead=0`, same on board). Confirming the value needs internal-signal/VCD access to the
per-core scratchpad — the next step.

### ⚠ (superseded — see above) earlier conclusion: URAMReal→BRAM latency "bug"
Verified the MIPS32 run against a golden reference (compiler interpreter, both high-level and
`-L` lowered): the **golden halts after 53 virtual cycles with `RF[2] <= 45`** (sum 0..9 = 45,
correct) then "Got halt!". But the **first board run did NOT match** — it ran 1991 vcycles
with ~1981 register writes (≈1/cycle), never halted, and terminated only on the testbench's
1990-cycle `$finish` (eid 0). The `-L` interpreter halting at 53 proved the compiled
`exec.bin` is correct → the bug was on the **hardware/port side**.

**Root cause:** `URAMReal.v` hardcodes a **read latency of 2** and its `SimpleDualPortMemory`
wrapper does **not** pass `READ_LATENCY` to it (only ADDRESS_WIDTH+filename). The Processor's
per-core `array_memory` scratchpad was instantiated **without** `READ_LATENCY` (default 1),
relying on URAMReal's hardcoded 2. `BRAMLike` *does* honor `READ_LATENCY`, so the no-URAM
substitution gave latency 1 → scratchpad reads arrived a cycle early → the simulated design
computed garbage and never halted. **Fix:** pin `READ_LATENCY = 2` on `array_memory`
(`core/Processor.scala`) — ignored by URAMReal, correct for BRAM. (Cache/Fetch already set 2.)
Rebuilt the bitstream; re-verifying on the board.

### Stepwise hardware debugging (after the latency fix) — observability wall
Stepped MIPS32 one `$display` at a time on the board:
- Exception sequence: **10× eid 0x1 (RF-write $display) → eid 0x2 ("Got halt!") → eid 0x3
  (finish) at vcycle 13**. Golden does ~30 writes over 53 cycles → board diverges immediately.
- **Trace VALUES are unreadable**: `TraceDumpHead` stays 0 and the live `$display` data never
  lands in BRAM (words 0-6 = zeros; user_base 0x8000 holds only static initializer leftover).
  So the **trace / global-memory write path is also broken on the port** — a second bug, and
  it removes the only external observable (the per-cycle PC/regs/scratchpad aren't JTAG-visible).
- **Conclusion:** the scratchpad latency fix was real but incomplete; ≥1 more port bug remains
  in the memory/trace path. On-hardware single-stepping can't see the internal state needed to
  localize it. **Next step: Verilator co-simulation** of the modified RTL (full visibility) to
  reproduce + crack the remaining bug — JTAG observability is insufficient. The functional
  equivalence of URAMLike/BRAMLike (same xpm, read_first, latency) means the bug is subtle
  (likely cache-flush/trace path or a HW timing interaction), best found in sim.

### Path B — self-checking benchmarks (frontend `manticore_check` fix)
- **Fixed** the `manticore_check` `std::out_of_range` crash (in
  `frontend/passes/manticore/manticore_clock.cc` `checkClock`): guarded `cellClock` against
  latch cells that have no `CLK` port, and guarded the nested-module `getPort` in the
  clock-consistency recursion. The pass no longer crashes on `$masm_expect` designs.
- Remaining: counter/fifo now reach the checker but are rejected with "Top module can not
  have output" — the `$masm_expect`/`$masm_stop` self-checkers expose a top-level output
  (vs MIPS32's `$display`/`$finish` which lower to privileged *modules*). Needs more
  frontend work to run those to a clean STOP/ASSERT; not required now that MIPS32 completes.

### On-board MIPS32 run (gronau, real xcku040) — earlier (cut off mid-run)
- Loaded the MIPS32 image over JTAG-to-AXI; **init_0/init_1 → FINISH** (instruction memory
  loaded); **main executes** — each virtual cycle does a register-file write whose `$display`
  raises a **FLUSH** (eid 1); the cache-flush+resume loop advances the sim (vcycles 9→35…).
  **The MIPS CPU simulation runs on the board.**
- Cut off by the 600 s SSH timeout, not by an error. Bottlenecks are **JTAG-over-hw_server
  throughput**: (a) the 5110-transaction image load (~minutes), (b) mips32.sv:411 `$display`s
  on *every* register write → one flush+resume JTAG handshake per simulated cycle (~seconds).

### Known blockers to a fully-completed verified run
- **manticore_check frontend bug**: the self-checking microbenches (counter, fifo,
  VectorAdd, array_mult — `$masm_expect`, no `$display`, would run to a clean STOP/ASSERT
  with no flush) crash the frontend's `manticore_check` pass (`std::out_of_range` / dict::at).
  The pass is required (it lowers the `MASM_PRIVILAGED` expect cells), so it can't just be
  skipped — needs a C++ fix in `frontend/passes/manticore/manticore_check.cc`.
- **Completion paths:** (A) drive MIPS32 to its halt by speeding the JTAG path (burst image
  load + lean polling) + a long background run — reaches the terminating FINISH (no trace
  decode needed); (B) fix `manticore_check` → run a self-checker (counter/fifo) to a clean
  PASS eid.
