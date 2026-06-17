package manticore.machine.core

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Distributed resume (multi-IC stall wave): Management must ungate the compute clock
  * at an exact, host-scheduled free-running cycle R, not immediately.
  *
  * The host issues CMD_RESUME with `timeout_enabled` set and R in the timeout field;
  * Management holds the clock gated in sResumeWait until `totalCycleCount === R` (the
  * always-on counter that keeps ticking during a stall and is aligned across ICs), then
  * ungates into execution. This is what lets every IC leave a distributed stall on the
  * same global cycle.
  *
  * This unit-tests Management in isolation (no boot/image needed): from idle a scheduled
  * CMD_RESUME goes straight to sResumeWait, so we can observe clock_active gating until R.
  */
class ManagementResumeTester extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val CMD_RESUME = 1

  // schedule_config: bit63 = timeout_enabled, bits62:56 = command, bits54:0 = data (R)
  def resumeAtWord(r: Long): BigInt =
    (BigInt(1) << 63) | (BigInt(CMD_RESUME) << 56) | BigInt(r)

  // immediate resume: command = CMD_RESUME, timeout_enabled (bit63) clear
  def resumeNowWord: BigInt = BigInt(CMD_RESUME) << 56

  private def tieOff(dut: Management): Unit = {
    dut.io.start.poke(false.B)
    dut.io.boot_finished.poke(false.B)
    dut.io.core_exception_occurred.poke(false.B)
    dut.io.clock_locked.poke(true.B)
    dut.io.schedule_config.poke(0.U)
    dut.io.exception_id.poke(0.U)
    dut.io.cache_done.poke(false.B)
    dut.io.execution_active.poke(false.B)
    dut.io.store_pending.poke(false.B)
  }

  behavior of "Management scheduled resume (stallWave)"

  it should "hold the clock gated until the scheduled cycle R, then ungate" in {
    test(new Management(dimX = 2, dimY = 2, stallWave = true)) { dut =>
      dut.clock.setTimeout(0)
      tieOff(dut)
      dut.reset.poke(true.B)
      dut.clock.step(4)
      dut.reset.poke(false.B)
      dut.clock.step(8)

      // idle, clock free-running (init); read the free-running counter
      dut.io.idle.expect(true.B)
      dut.io.clock_active.expect(true.B)
      val t0 = dut.io.device_registers.execution_cycles.peek().litValue.toLong
      val margin = 40L
      val r = t0 + margin

      // issue a scheduled resume to cycle R
      dut.io.schedule_config.poke(resumeAtWord(r).U)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // step until the counter reaches R, asserting the clock stays gated the whole way
      var ungatedCycle = -1L
      var guard = 0
      while (ungatedCycle < 0 && guard < 1000) {
        val cyc    = dut.io.device_registers.execution_cycles.peek().litValue.toLong
        val active = dut.io.clock_active.peekBoolean()
        if (active) {
          ungatedCycle = cyc
        } else {
          // while gated, we must not have passed R yet (allow R itself: ungate edge)
          assert(cyc <= r, s"clock still gated at cycle $cyc, past scheduled R=$r")
          dut.clock.step()
          guard += 1
        }
      }
      assert(ungatedCycle >= 0, s"clock never ungated within guard window (R=$r)")
      info(s"scheduled resume: armed at t0=$t0, R=$r, ungated at execution_cycles=$ungatedCycle")
      // ungated at (about) R — allow a small pipeline slack on the free-running counter
      assert(ungatedCycle >= r && ungatedCycle <= r + 3,
        s"ungated at $ungatedCycle, expected ~R=$r (free-running counter has a few cycles of pipeline slack)")
      // and it actually entered execution (no longer idle)
      dut.io.idle.expect(false.B)
    }
  }

  it should "resume immediately (no deadlock) when armed after R already passed" in {
    // The host arms each IC individually, possibly sequentially / with variable latency,
    // so an IC can be armed only after its free-running counter is already past R. The
    // `>=` comparator must then ungate at once rather than waiting for a 40-bit wrap.
    test(new Management(dimX = 2, dimY = 2, stallWave = true)) { dut =>
      dut.clock.setTimeout(0)
      tieOff(dut)
      dut.reset.poke(true.B)
      dut.clock.step(4)
      dut.reset.poke(false.B)
      dut.clock.step(20) // let the counter advance well past any small R

      val t0 = dut.io.device_registers.execution_cycles.peek().litValue.toLong
      val rPast = math.max(0L, t0 - 5L) // a cycle already in the past
      dut.io.schedule_config.poke(resumeAtWord(rPast).U)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // must ungate within a couple cycles, not hang
      var active = false
      var guard  = 0
      while (!active && guard < 16) {
        active = dut.io.clock_active.peekBoolean()
        if (!active) { dut.clock.step(); guard += 1 }
      }
      assert(active, s"clock never ungated for a past R=$rPast (t0=$t0) — deadlock")
      info(s"late-arm: t0=$t0, R(past)=$rPast, ungated within ${guard} cycles")
      dut.io.idle.expect(false.B)
    }
  }

  it should "ungate immediately on a plain CMD_RESUME (timeout_enabled clear)" in {
    test(new Management(dimX = 2, dimY = 2, stallWave = true)) { dut =>
      dut.clock.setTimeout(0)
      tieOff(dut)
      dut.reset.poke(true.B)
      dut.clock.step(4)
      dut.reset.poke(false.B)
      dut.clock.step(8)

      dut.io.clock_active.expect(true.B)
      dut.io.schedule_config.poke(resumeNowWord.U)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      // a plain resume goes straight to execution (clock stays active, no sResumeWait gate)
      dut.clock.step(2)
      dut.io.clock_active.expect(true.B)
      dut.io.idle.expect(false.B)
    }
  }
}
