# Porting Manticore to the KCU105 (XCKU040) — Plan

## Goal

Run a **trivial reproducer** of the Manticore RTL-simulation accelerator (the paper's
BSP architecture) on a **Xilinx KCU105** evaluation board, built with **Vivado 2022.1**.

First milestone (the gate the user asked for): **produce a bitstream**. Running a tiny
program on the board comes after, once the board is physically available.

## Target board / part

- Board: **KCU105**, part **`xcku040-ffva1156-2-e`** (Kintex **UltraScale**, single SLR).
- Relevant board resources: a **300 MHz differential system clock**, push-buttons (reset),
  DDR4 SODIMM (not used in phase 1), USB-JTAG.
- Capacity vs the Alveo U200 it currently targets: roughly **1/5** the logic; crucially
  **no URAM** (UltraScale, not UltraScale+).

## Strategy (decided)

- **No Vitis / no XRT.** The current flow packages the design as a Vitis RTL kernel
  (`.xo`) and links it into an Alveo platform with `v++` → `.xclbin`. We drop that
  entirely and instantiate the kernel in a **plain Vivado block design** → `.bit`.
  (Also forced by tooling: only Vivado 2022.1 is installed here — no Vitis 2022.1, no
  XRT, no Alveo platforms.)
- **On-chip BRAM** backs the kernel's `m_axi_bank_0` memory master (no DDR in phase 1).
- **JTAG-to-AXI master** replaces the XRT host: a Vivado Tcl script writes the program
  image into the BRAM and pokes `s_axi_control` to start the device and read results.

## What the kernel needs from its environment (and how we supply it)

The Manticore RTL itself is board-agnostic. Vitis/XRT only provided the *environment*:

| Kernel boundary (from `ManticoreFlatKernel`) | XRT/Alveo supplied | KCU105 replacement |
|---|---|---|
| `ap_clk` (300 MHz), `ap_rst_n` | platform shell | board 300 MHz diff clock → clk_wiz; reset from `proc_sys_reset` + button |
| `m_axi_bank_0` — AXI4, 64-bit addr, **256-bit** data, id=1 | platform DDR/HBM + MIG | AXI BRAM controller + Block Memory (256-bit) |
| `s_axi_control` — AXI4-Lite, **8-bit** addr, 32-bit data | PCIe/XDMA + `xrt::ip` | JTAG-to-AXI master |
| fill memory / read results | `xrt::bo` DMA | JTAG-to-AXI read/write transactions |
| `interrupt` | platform IRQ | left unconnected (we poll `ap_done`) |
| bitstream load | `load_xclbin` | Vivado `write_bitstream` + JTAG program |

Everything else — cores, NoC, switches, cache, bootloader (`Programmer`), the
`AxiSlave` control register file, the internal `ClockDistribution` (clk_wiz) and the AXI
clock-domain crossings — is reused **verbatim**.

## Device↔host contract (what the JTAG driver must do)

From `manticore-runtime` + `AxiSlave` + `Programmer`:

1. Build one memory image (16-bit words): program binaries placed at their `.base`
   word offsets, rest zeroed. The bootloader reads a per-core **config-packet stream**
   from this image.
2. Write **`DramBank0Base` (0x90, 64-bit)** = memory base we chose (e.g. `0x0`).
3. Write **`GlobalMemoryInstructionBase` (0x84, 64-bit)** = word offset of the boot stream.
4. Write **`ScheduleConfig` (0x6c, 64-bit)** = command(start) | timeout.
5. Pulse **`Control` (0x00) bit0 = ap_start**.
6. Poll **`Control` (0x00)**: bit2 = ap_idle / bit1 = ap_done.
7. Read results: **`ExceptionId` (0x10)** (0 = ok), cycle counts (`CycleCount` 0x2c,
   `VirtualCycleCount` 0x38, ...), and read the result back out of BRAM via JTAG.

Register offsets are computed in `AxiSlave.addressOf` (user regs start at 0x10; DevReg32
takes 8 bytes, 64-bit/pointer regs take 12). Confirmed map:

| reg | offset | width | dir |
|---|---|---|---|
| Control | 0x00 | 32 | host RW (ap_start/done/idle/ready) |
| ExceptionId | 0x10 | 32 | dev→host |
| TraceDumpHead | 0x18 | 64 | dev→host |
| DeviceInfo | 0x24 | 32 | dev→host (DimX[31:26], DimY[25:20]) |
| CycleCount | 0x2c | 64 | dev→host |
| VirtualCycleCount | 0x38 | 64 | dev→host |
| BootloaderCycleCount | 0x44 | 32 | dev→host |
| ClockStalls | 0x4c | 32 | dev→host |
| CacheHits/Misses/Stalls | 0x54/0x5c/0x64 | 32 | dev→host |
| ScheduleConfig | 0x6c | 64 | host→dev |
| TraceDumpBase | 0x78 | 64 | host→dev |
| GlobalMemoryInstructionBase | 0x84 | 64 | host→dev |
| DramBank0Base | 0x90 | 64 | host→dev |

## Phases

- **P0 — nix toolchain.** DONE. `manticore-hw/flake.nix` + `.envrc`; `nix develop` → `sbt`
  works (Temurin JDK 11). Vivado stays external (`source .../2022.1/settings64.sh`).
- **P1 — RTL for KU040.** Add a non-Vitis generation path that emits
  `ManticoreFlatKernel.v` (+ `keep_hierarchy`) and generates the support IPs
  (`clk_dist` clk_wiz, `axi4_clock_converter`, `axi4lite_clock_converter`) for the part,
  then stops (no `package_xo`, no `v++`).
- **P2 — URAM→BRAM.** Map all URAM-backed memories to BRAM on the KU040 (no URAM on chip).
- **P3 — Vivado project.** Block design: clk_wiz (300 MHz diff → ap_clk) + proc_sys_reset,
  JTAG-to-AXI master, SmartConnect, AXI BRAM controller + Block Memory (256-bit), and the
  packaged kernel. KCU105 board/part + XDC. → synth → impl → **`write_bitstream`**.
- **P4 — run (later, needs board).** Trivial program image + JTAG-to-AXI Tcl driver;
  load, start, read back a computed result.

## Key parameters for the first bitstream

- Grid: **2×2** (`DimX=DimY=2`) — smallest practical, fastest build.
- `enable_custom_alu = false` for bring-up (fewer DSPs / simpler), revisit later.
- Internal compute/control clock (`freqMhz`): **low (e.g. 100 MHz)** for easy timing;
  `ap_clk` stays **300 MHz** to match `clk_dist`'s `PRIM_IN_FREQ`.

## Which Vivado to source (licensing)

The plain `/opt/tools/Xilinx/Vivado/2022.1` is **WebPACK-only** — `get_parts xcku040*` is
empty there, so it can only build the free **`xcku035-ffva1156-2-e`** stand-in (same kintexu
family + ffva1156 package as the xcku040, so the design/pins/timing transfer, but the `.bit`
won't program real xcku040 silicon).

**For the real KCU105, source the licensed Enterprise install:**
```bash
source /opt/tools/Xilinx/VivadoEnterprise/Vivado/2022.1/settings64.sh
```
This exposes `xcku040-ffva1156-2-e` and the KCU105 board parts
(`xilinx.com:kcu105:part0:1.6`/`:1.7`) and loads the license. `build_kcu105.tcl` then targets
`xcku040-ffva1156-2-e` automatically (it only falls back to xcku035 when 040 is absent).

KCU105 pin assignments (from the board files, package ffva1156 — valid for the stand-in):
- 300 MHz diff sysclk: `sysclk_300_p = AK17`, `sysclk_300_n = AK16` (LVDS)
- reset button: `CPU_RESET = AN8` (LVCMOS18, active-high)

## Open risks / watch-list

- **Kernel as BD cell.** Need the Chisel-emitted `ManticoreFlatKernel.v` packaged so
  Vivado recognizes `m_axi_bank_0` / `s_axi_control` as AXI interfaces. Reuse a trimmed
  version of the existing `package_kernel.tcl` interface-association (drop the Vitis
  `sdx_kernel`/`kernel.xml` bits).
- **256-bit AXI at 300 MHz** on the BRAM path may stress timing; fallback is lowering
  `ap_clk` (and regenerating `clk_dist` with a matching `PRIM_IN_FREQ`).
- **Memory init.** BRAM is not initialized in hardware (matches existing URAM behavior);
  all state is loaded at runtime by the bootloader — the trivial program must not assume
  pre-initialized array memory.
- **Boot-stream format** (per-core headers, countdown) must be produced exactly; verified
  against `Programmer.scala` + `control/ProgrammerTestUtils.scala` when building P4.

See `CHANGES.md` for the running log of concrete file edits/additions.
