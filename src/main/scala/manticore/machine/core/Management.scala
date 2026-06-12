package manticore.machine.core

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import manticore.machine.ManticoreFullISA
import manticore.machine.memory.CacheConfig
import manticore.machine.memory.CacheCommand
import manticore.machine.PerfCounter

object ManagementConstants {

  val CMD_START         = 0.asUInt(7.W)
  val CMD_RESUME        = 1.asUInt(7.W)
  val CMD_CACHE_FLUSH   = 2.asUInt(7.W)
  val EXCEPTION_TIMEOUT = (1 << 16).asUInt(32.W)

}

class Management(dimX: Int, dimY: Int) extends Module {

  import ManagementConstants._
  val io = IO(new Bundle {

    val start = Input(Bool())
    val done  = Output(Bool())
    val idle  = Output(Bool())

    val device_registers = Output(new DeviceRegisters)

    val boot_start    = Output(Bool())
    val boot_finished = Input(Bool())

    val core_exception_occurred = Input(Bool())
    val clock_active            = Output(Bool())
    val clock_locked            = Input(Bool())

    val schedule_config = Input(UInt(64.W))
    val exception_id    = Input(UInt(32.W))

    val config_enable = Output(Bool())

    val cache_reset_start = Output(Bool())
    val cache_flush_start = Output(Bool())
    val cache_done        = Input(Bool())
    val execution_active  = Input(Bool())

    // from MemoryIntercept: a core global-memory access is still draining into
    // the cache. Exceptions must wait for it (see sVirtualCycle).
    val store_pending = Input(Bool())

    val soft_reset = Output(Bool())
  })

  val clock_active = RegInit(true.B)
  io.clock_active := clock_active
  require(dimX < 64 && dimY < 64)

  // these two registers help minimize the fan out of clock_active
  val timed_out = Reg(Bool())
  timed_out := false.B

  // an exception fired while a core global-memory store was still draining into the
  // cache; we stay in sVirtualCycle (clock gated, cache mux on the core) until the
  // drain completes, then take the exception (see sVirtualCycle)
  val exception_pending = RegInit(false.B)

  val core_exception_id = Reg(io.exception_id.cloneType)

  /* performance counters */
  val vcycleCount     = PerfCounter(40, 1) // 40-bit counter, i.e., count up to 256 trillion cycles
  val totalCycleCount = PerfCounter(40, 1) // 40-bit counter
  val bootCycleCount  = PerfCounter(32, 1) // 32-bit counter
  val clockStallCount = PerfCounter(32, 1) // 32-bit counter

  val start_reg   = RegNext(io.start)
  val start_pulse = WireDefault((!start_reg) & io.start)

  // val dev_regs = Reg(new DeviceRegisters)

  // |               schedule_config                  |
  // +------------------------------------------------+
  // |63            56|55                            0|
  // +----------------+-------------------------------+
  // |      CMD       |           CMD DATA            |
  // -----------------+--------------------------------
  val command         = WireDefault(io.schedule_config.head(8).tail(1))        // the first 7 bit are the run command
  val timeout_enabled = WireDefault(io.schedule_config.head(8).head(1).asBool) // 8th bit, used to enable timeout
  val timeout         = WireDefault(io.schedule_config.tail(8))                // timeout value

  object State extends ChiselEnum {
    val sIdle, sCoreReset, sCoreResetWait, sCacheReset, sCacheResetWait, sBoot, sVirtualCycle, sCacheFlush,
        sCacheFlushWait, sDone = Value
  }
  import State._

  val state = RegInit(sIdle)

  // Very conservative countdown value. Can technically be matched to the exact latency of the SoftResetTree.
  // I cap the minimum value to 32 cycles to ensure that even small arrays like 2x2 have enough time for the reset
  // to propagate to the SLRs before they fan out to the cores. It is a conservative number and can be reduced.
  val soft_reset_value           = math.max(32, 2 * dimX * dimY)
  val soft_reset_countdown_timer = Reg(UInt(unsignedBitLength(soft_reset_value).W))

  val done_out = RegNext(state === sDone)
  io.done := done_out

  val idle_out = RegNext(state === sIdle)
  io.idle := idle_out

  val config_enable = RegNext(state =/= sVirtualCycle)
  io.config_enable := config_enable

  val execution_active         = RegNext(io.execution_active)
  val execution_active_negedge = RegNext(execution_active && !io.execution_active)

  val enable_core_reset = WireDefault(false.B)

  io.soft_reset        := RegNext(state === sCoreReset)
  io.cache_reset_start := (state === sCacheReset)
  io.cache_flush_start := (state === sCacheFlush)
  io.boot_start        := false.B

  // performance counter events
  totalCycleCount.inc()
  when(state === sVirtualCycle && execution_active_negedge) {
    vcycleCount.inc()
  }
  when(state === sBoot) {
    bootCycleCount.inc()
  }
  when(state === sVirtualCycle && !clock_active) {
    clockStallCount.inc()
  }

  // dev_regs.execution_cycles := dev_regs.execution_cycles + 1.U

  switch(state) {

    is(sIdle) {
      when(start_pulse && io.clock_locked) {
        state := Mux(command === CMD_START, sCoreReset, Mux(command === CMD_RESUME, sVirtualCycle, sCacheFlush))
        clock_active := Mux(
          command === CMD_RESUME,
          true.B,
          false.B
        ) // only enable the clock if we are resuming execution
        when(command === CMD_START) {

          totalCycleCount.clear()
          vcycleCount.clear()
          bootCycleCount.clear()
          clockStallCount.clear()

        }
        soft_reset_countdown_timer := soft_reset_value.U
      }
    }
    is(sCoreReset) {
      clock_active               := true.B
      soft_reset_countdown_timer := soft_reset_countdown_timer - 1.U
      when(soft_reset_countdown_timer === (soft_reset_value / 2).U) {
        state := sCoreResetWait
      }
    }
    is(sCoreResetWait) {
      clock_active               := true.B // enable the clock so that cores can be reset
      soft_reset_countdown_timer := soft_reset_countdown_timer - 1.U
      when(soft_reset_countdown_timer === 0.U) {
        state := sCacheReset
      }
    }
    is(sCacheReset) {
      state := sCacheResetWait
    }
    is(sCacheResetWait) {
      when(io.cache_done) {
        state         := sBoot
        io.boot_start := true.B
      }
      // dev_regs.bootloader_cycles := dev_regs.bootloader_cycles + 1.U
    }
    is(sCacheFlush) {
      state := sCacheFlushWait
    }
    is(sCacheFlushWait) {
      when(io.cache_done) {
        state := sDone
      }
    }
    is(sBoot) {
      // dev_regs.bootloader_cycles := dev_regs.bootloader_cycles + 1.U
      clock_active := true.B // enable the clock so that NoC can be used
      when(io.boot_finished) {
        state := sVirtualCycle
      }
    }

    is(sVirtualCycle) {

      // The gmem clock-kill is gone (global memory is a fixed-latency on-chip
      // BRAM, see GmemBramBackend): during execution the clock only ever gates
      // on an exception, and stays gated until the host resumes via
      // CMD_RESUME in sIdle.
      when(clock_active) {
        clock_active := !io.core_exception_occurred
      }
      core_exception_id := io.exception_id
      when(clock_active) {
        // check exceptions for stopping execution
        when(io.core_exception_occurred) {
          // Precise-exception drain guard: a $display/FLUSH is trace GSTs followed by
          // the interrupt, with no drain NOPs — the last store's data may still be in
          // MemoryIntercept's control-clock FSM. Leaving sVirtualCycle right away
          // flips config_enable, which resets that FSM and switches the cache mux to
          // the boot side MID-REQUEST: the cache commits the line with wdata=0 (the
          // all-zero $display readback). Hold the state (cache mux stays on the core,
          // compute clock stays gated) until the store has fully drained.
          when(io.store_pending) {
            exception_pending := true.B
          } otherwise {
            state := sDone
            // don't register here, causes large fan out on clock_active!
            // dev_regs.exception_id := io.exception_id
            timed_out := false.B
          }

        }.elsewhen(timeout_enabled && vcycleCount.value === timeout) {
          // or when we timeout
          // don't register exception_id here, causes large fan out on clock_active!
          // dev_regs.exception_id := EXCEPTION_TIMEOUT
          timed_out := true.B
          // give some id that users can not
          // make (they are restricted to 16 bits, i.e., up to 0xFFFF)
          // NOTE: max time out  is 1 << 56 (more than enough)
          state := sDone
        }

      }
      // deferred exception: the in-flight store has drained, now stop for real
      when(exception_pending && !io.store_pending) {
        exception_pending := false.B
        timed_out         := false.B
        state             := sDone
      }

    }
    is(sDone) {
      clock_active := false.B
      state        := sIdle
    }
  }

  io.device_registers.exception_id      := RegEnable(Mux(timed_out, EXCEPTION_TIMEOUT, core_exception_id), state === sDone)
  io.device_registers.bootloader_cycles := bootCycleCount.value
  io.device_registers.virtual_cycles    := vcycleCount.value
  io.device_registers.execution_cycles  := totalCycleCount.value
  io.device_registers.clock_stalls      := clockStallCount.value

  io.device_registers.device_info := Cat(dimX.U(6.W), dimY.U(6.W), 0.U(20.W))

  io.device_registers.trace_dump_head := 0.U
}

class MemoryIntercept extends Module {

  val io = IO(new Bundle {

    val core          = CacheConfig.frontInterface()
    val boot          = CacheConfig.frontInterface()
    val config_enable = Input(Bool())

    val cache_flush = Input(Bool())
    val cache_reset = Input(Bool())

    val cache      = Flipped(CacheConfig.frontInterface())
    val core_clock = Input(Clock())
    // a core-initiated gmem access is still in flight (incl. one arriving this
    // cycle); Management must not leave sVirtualCycle while this is high, or the
    // config_enable mux below cuts the backend off mid-request
    val pending = Output(Bool())

  })

  // request lifetime with the fixed-latency BRAM backend: start (core) ->
  // start+1 (backend capture, done) -> start+2 (rdata registered into the core
  // clock domain)
  private val inflight1 = RegNext(!io.config_enable && io.core.start, false.B)
  private val inflight2 = RegNext(inflight1, false.B)
  io.pending := (!io.config_enable && io.core.start) || inflight1 || inflight2

  io.boot.done  := false.B
  io.boot.idle  := false.B
  io.boot.rdata := DontCare

  io.core.done := false.B

  io.core.idle := false.B

  // Register the read result in the core (compute) clock domain: the core was
  // always designed against a "2-cycle SRAM" gmem contract (address at the pins
  // one cycle after start, data two cycles after that). With the fixed-latency
  // BRAM backend that contract is now physically true — no clock-kill needed —
  // but the core-domain register stays: if an exception freezes the core while
  // a read is in flight, it holds the result until the core resumes.
  io.core.rdata := withClock(clock = io.core_clock) {
    RegNext(io.cache.rdata)
  }
  io.cache.start := false.B

  when(!io.config_enable) {
    // PASS THROUGH, do not latch at io.core.start: the core's gmem address/wdata
    // settle at its pins one compute cycle AFTER gmem.start (Execute aligns start
    // via RegNext(opcode) but addr/wdata come straight from the register file
    // reads). So the backend request fires one cycle after io.core.start, with
    // the address/wdata combinationally passed from the (settled) pins. If an
    // exception gates the compute clock meanwhile, the BUFGCE freeze holds the
    // pins ON the settled values, so this control-clock capture still reads the
    // correct request (see docs/kcu105/MISSED-DISPLAY-TIMING.md /
    // VERIFICATION.md for the one-behind failure mode that latching at the
    // start cycle causes).
    io.cache.addr  := io.core.addr
    io.cache.wdata := io.core.wdata
    io.cache.cmd   := io.core.cmd
    io.cache.start := RegNext(io.core.start, false.B)
  } otherwise {
    io.cache <> io.boot

    when(io.cache_flush) {
      io.cache.cmd   := CacheCommand.Flush
      io.cache.start := true.B
    }.elsewhen(io.cache_reset) {
      io.cache.cmd   := CacheCommand.Reset
      io.cache.start := true.B
    }

  }

}
