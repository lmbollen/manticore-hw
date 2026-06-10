# The Manticore ISAs — a complete overview

"ISA" means three related things in this architecture, layered from the hardware up to
the assembly language in `.masm` files:

1. [the **hardware execution ISA**](#1-the-hardware-execution-isa) — what a core executes
   (64-bit instructions, two configurations),
2. [the **compiler IR**](#2-the-compiler-ir--one-grammar-two-levels) — one instruction
   grammar instantiated at two levels (Unconstrained / Placed),
3. [the **machine encoding & system calls**](#3-system-calls-everything-is-expect) — how
   the IR maps onto the hardware opcodes, and how `$display`/`$finish` work.

Sources of truth: `manticore-hw/src/main/scala/manticore/machine/ISA.scala` (hardware),
`manticore-compiler/src/main/scala/manticore/compiler/assembly/Instruction.scala` (IR
grammar), `…/levels/codegen/MachineCode.scala` (encoding).

---

## 1. The hardware execution ISA

Every core executes **64-bit instructions** over **16-bit data**, with this fixed field
layout (`ISA.scala`):

```
|63                                                                              0|
|    rs4    |    rs3    |    rs2    |    rs1    |  funct  |    rd     |  opcode   |
|<-- 11 --> |<-- 11 --> |<-- 11 --> |<-- 11 --> |<-- 5 -->|<-- 11 --> |<--- 4 --->|
|<------ 16 ------>|<- 4 ->|                              |<-- 4 -->|
|       imm        | slice |                              | cust_ram_idx         |
```

The 16-bit `imm` and 4-bit `slice_ofst` fields overlay rs4/rs3; `cust_ram_idx` overlays
the top of rd.

### The two hardware configurations

Same encoding; one capability switch:

| parameter | `ManticoreBaseISA` | `ManticoreFullISA` |
|---|---|---|
| `DataBits` | 16 | 16 |
| `NumBits` (instruction) | 64 | 64 |
| `IdBits` (register id) | 11 → **2048 registers/core** | same |
| `FunctBits` | 5 → 32 funct slots (16 used) | same |
| `NumPcBits` | 12 → ≤ 4096 instructions/core | same |
| `CarryCount` | 64 carry registers | same |
| `WithGlobalMemory` | **false** (compute cores) | **true** |

Physically the array is Base-ISA cores plus one Full-ISA **privileged core at (0,0)**
that owns global memory (cache/DRAM) and exception reporting.

Orthogonally, the **custom ALU (CFU)** can be omitted at hardware-generation time
(`--enable_custom_alu false`, as in the KCU105 build). Then `CUST`/`CONFIGCFU` do not
exist and programs **must** be compiled with `--no-cf` — a CF-extracted program
mis-executes on a no-CFU array (classic symptom: MIPS32 halting at 13 instead of 53
virtual cycles).

### The 14 opcodes

| opcode | meaning |
|---|---|
| `NOP` | idle slot (the scheduler's latency/NoC padding) |
| `SET` | rd ← 16-bit immediate |
| `ARITH` | rd ← funct(rs1, rs2[, rs3]) — functs below |
| `CUST` | rd ← custom function (16 parallel 4-input LUTs) over rs1..rs4 |
| `CONFIGCFU` | load one LUT-equation word into custom-function RAM `cust_ram_idx` |
| `LLOAD` / `LSTORE` | scratchpad load/store (base+offset; the store is predicated) |
| `GLOAD` / `GSTORE` | global-memory access, 48-bit address in {rs1,rs2,rs3} — **privileged core only** |
| `EXPECT` | if rs1 ≠ rs2 → raise exception with `imm` as exception id (eid). Implements **all** system calls (§3) |
| `SEND` | transmit rs2's value into register `rd` of another core; `imm` = **signed** hop counts: low byte xHops, high byte yHops (positive = east/north, negative = west/south on the bidirectional torus) |
| `PREDICATE` | set the store-predicate bit from rs1 (guards the next `LSTORE`) |
| `SETCARRY` | write a carry register (0/1) |
| `SLICE` | rd ← rs1[slice_ofst +: length] (bit-field extract) |

### The 16 ALU functs (`ISA.Functs`)

`ADD2, SUB2, MUL2, MUL2H` (upper half), `MUL2S`, `AND2, OR2, XOR2`, `SLL, SRL, SRA`,
`SEQ`, `SLTU, SLTS`, `MUX` (rs3 selects rs1/rs2), `ADDC` (add with carry register rs3 —
how the compiler builds wide adders from 16-bit limbs).

### Per-core state

- 2048 × 16-bit register file (4 read ports rs1–rs4; BRAM, 2-cycle read latency the
  pipeline depends on),
- 64 carry bits,
- 16 Ki-word scratchpad (URAM on Alveo, BRAM on the KCU105),
- 4096-deep instruction memory.

### Execution model: linear and periodic — no flow control

**There are no flow-control instructions.** None of the 14 opcodes touches the PC, and
the fetch unit has no branch path at all (`Fetch.scala`): while execution is enabled the
PC increments by 1 every cycle; at the final instruction it resets to 0. Each core's
program is therefore a straight line executed identically every virtual cycle:

```
        ┌──────────── one virtual cycle (length L, same for every core) ───────────┐
core i: │ body (compute)  │ epilogue (Recv slots / NoC padding) │ sleep (L − N_i)   │ → repeat from PC=0
```

`body+epilogue` = the core's `N_i` instructions; `sleep_length` pads every core to the
common period `L` so all cores re-enter execution simultaneously — the BSP superstep.
One virtual cycle simulates one clock cycle of the design under test; state carries
across vcycles only through registers/scratchpad (the `_curr/_next` reg pairs).

Where "control flow" went:

- **Data-level**: the compiler computes both sides and selects — `MUX`, `PREDICATE`d
  stores, `SLICE`; the IR's `ParMux`/`JumpTable`/`Phi` case constructs are normalized
  and lowered away before scheduling (`JumpTableNormalizationTransform`; the scheduler
  errors if one survives — "JumpTables are a thing of the past", and the hardware never
  had a jump).
- **System-level**: the only run-time control events are `EXPECT` exceptions — the
  Management controller gates the compute clock (the whole array freezes in lockstep),
  reports the eid, and the host decides: service a FLUSH and resume, or stop on
  FINISH/STOP/ASSERT. Resuming continues exactly where the array froze; it does not
  alter the program.
- **Boot-level**: the processor's FSM phases (receive program → countdown → execute ⇄
  sleep) wrap the program but are not instructions.

Consequence: execution time is fully static — `L` is known at compile time, a simulated
design clock always costs exactly `L` Manticore cycles, and the NoC schedule can assume
every instruction's issue cycle. This determinism is the foundation the whole
architecture (NoC reservations, Recv placement, multi-chip latency modeling) rests on.

### Sleep and the receive mechanism (`Processor.scala`)

One `countdown_timer` register drives all phases: boot countdown → execution
(`countdown := body+epilogue`) ⇄ sleep (`countdown := sleep_length`). On entering sleep
the fetch unit is disabled (PC resets to 0, NOPs issue) but the core's **clock keeps
running** — its switch keeps routing. Since every core's `N_i + sleep_i` equals the same
period `L`, all cores wake on the same cycle: synchronization is pure arithmetic, with
no barrier — valid only because execution is branch-free (and why a boot-time stagger
persists forever).

**Receives are self-modifying code.** There is no NoC write port into the register
file. During execution, an arriving packet `(register, value)` is assembled into a
`SET register, value` instruction and written into **instruction memory** at
`program_pointer`, which starts at `program_body_length` (reset on every wake) and
increments per arrival — the epilogue region is a landing zone, filled in arrival
order. Received values reach the register file when the PC later sweeps the epilogue
and executes those `SET`s through the ordinary pipeline.

The scheduler guarantees the timing: `epilogue_length` (in the boot stream) is the
number of expected receives; the virtual `Recv` instructions are placed at their
modeled arrival cycles with NOP padding, so the core is still awake until the last
expected packet has landed — sleep begins only afterwards (the sleep state has no
packet handling; a packet arriving during sleep would be lost). A value sent in
virtual cycle N is typically consumed in vcycle N+1 — registered, loop-carried
communication, matching the `_curr/_next` semantics of the simulated design.

---

## 2. The compiler IR — one grammar, two levels

`Instruction.scala` defines a single instruction grammar (`ManticoreAssemblyIR`) that is
instantiated twice. `UnconstrainedAssemblyParser` parses `.masm` text into the first:

| | **UnconstrainedIR** | **PlacedIR** |
|---|---|---|
| produced by | the Yosys frontend (`v2masmyosys`) / `.masm` parser | the middle-end (`ToPlaced`, process splitting, placement) |
| value widths | **arbitrary** (e.g. 128-bit adds) | exactly **16 bit** (`UInt16`) after width conversion |
| names | strings (`ys_w315`) | name + allocated physical register id |
| structure | one big process | `DefProcess` per core with `ProcessId(x,y)` — **placement is the topology mapping** |

Variable kinds (both levels): `const`, `wire`, `reg` (the `_curr/_next` pairs of
sequential logic), `input`/`output` (closed into reg pairs by the frontend passes),
`memory`. A program is
`DefProgram { DefProcess { registers, DefFunc (custom functions), label groups, global memories, body } }`.

### IR instruction set → machine encoding

| IR instruction | machine encoding |
|---|---|
| `BinaryArithmetic` (ADD/SUB/MUL/MULH/AND/OR/XOR/SLL/SRL/SRA/SEQ/SLTU/SLTS) | `ARITH` + funct |
| `Mux` | `ARITH` funct `MUX` (no dedicated opcode) |
| `AddCarry` / `SetCarry` / `ClearCarry` | `ARITH ADDC` / `SETCARRY` (clear = set 0) |
| `SetValue` / `Mov` | `SET` / `ARITH ADD rs, zero` |
| `LocalLoad` / `LocalStore` / `Predicate` | `LLOAD` / `LSTORE` / `PREDICATE` |
| `GlobalLoad` / `GlobalStore` | `GLOAD` / `GSTORE` (privileged) |
| `CustomInstruction` / `ConfigCfu` | `CUST` / `CONFIGCFU` (absent in `--no-cf` flows) |
| `Slice` / `PadZero` | `SLICE` / lowered |
| `Send` | `SEND`, DestX/DestY = signed 2's-complement hop bytes computed from the **ProcessId difference** on the global torus (min-\|hops\| shortest path) — where topology meets the ISA |
| `Recv` | **virtual** — never encoded. Marks *when* the NoC packet writes the destination register so the scheduler can place readers/writers safely |
| `Interrupt` / `PutSerial` | `EXPECT` / `GSTORE` — see §3 |
| `ParMux`, `JumpTable`+`Phi`+`BreakCase`, `Lookup` | structural/optimization constructs, fully lowered before codegen |
| `Nop` | `NOP` |

Ordering metadata: `Interrupt`/`PutSerial` carry a `SystemCallOrder`, memory ops a
`MemoryAccessOrder` — explicit total orders the scheduler must preserve.

---

## 3. System calls: everything is `EXPECT`

There are no dedicated syscall opcodes. The frontend's `$display` / `$finish` / `$stop`
/ assertions become IR `Interrupt`s with an action — `SerialInterrupt(fmt)` (a FLUSH),
`FinishInterrupt`, `StopInterrupt`, `AssertionInterrupt` — and `$display` arguments
become `PutSerial`s (lowered to `GlobalStore`s into a trace buffer). Code generation
maps every `Interrupt` to:

```
EXPECT SEQ rs1=condition, rs2=(1 if assertion else 0), imm=eid
```

— "raise exception `eid` when the comparison fails". The Management controller catches
the exception, gates the compute clock (the whole array freezes in lockstep), and
reports the eid to the host, which classifies it via `manifest.json`:

| action | eid namespace | host behaviour |
|---|---|---|
| `SerialInterrupt` (FLUSH, `$display`) | success counter (0,1,2,…) | cache-flush, read trace words, resume |
| `FinishInterrupt` (`$finish`) | success counter | stop: simulation completed |
| `StopInterrupt` / `AssertionInterrupt` | ≥ `0x8000` (failure namespace) | stop: failure |

Because `Interrupt`, `GlobalLoad`, `GlobalStore` and `PutSerial` are **privileged**, the
placer asserts there is exactly **one privileged process** and pins it to core (0,0).
Consequence for testbenches: only one module may `$display`/`$finish` — multi-unit
designs route results to a single reporter (see
`manticore-compiler/benchmarks/picorv32/loop_multi.v`). Note the masm frontend currently
rejects `$masm_expect`/`$masm_stop` on the top module ("Top module can not have
output") — use the `$display`/`$finish` style.

---

## How the layers connect

```
Verilog/SystemVerilog
  └─ Yosys frontend (v2masmyosys) ─▶ UnconstrainedIR        (arbitrary widths)
       └─ width conversion ─▶ 16-bit                         (limbs + carries)
            └─ split + place ─▶ PlacedIR on the (x,y) torus  (ProcessId per core)
                 └─ schedule  ─▶ NoC reservations, hop latencies, Recv placement
                      └─ MachineCode ─▶ 64-bit words + per-core boot stream
```

The hardware ISA fixes *what a core can do per cycle*; the IR is that same set plus the
virtual/structural constructs the compiler needs; and the `SEND` hop encoding / boot
stream is where the **topology** (grid dimensions, signed bidirectional hops, per-link
latencies from `--hop-latencies`) gets baked into otherwise topology-agnostic programs.

Related reading: `docs/kcu105/MULTICHIP-ADDRESSING.md` (how the addressing scales to
multi-chip tori), the top-level `README.md` in the umbrella directory (the full
topology→silicon flow), and the instruction-format figures in `docs/`
(`instructions_arith.png`, `instructions_cust.png`, `instructions_priv.png`).
