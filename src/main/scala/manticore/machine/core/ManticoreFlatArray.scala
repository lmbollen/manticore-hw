package manticore.machine.core

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.stage.ChiselStage
import chisel3.util._
import manticore.machine.ISA
import manticore.machine.ManticoreBaseISA
import manticore.machine.ManticoreFullISA
import manticore.machine.memory.CacheCommand
import manticore.machine.memory.CacheConfig

import scala.annotation.tailrec

import manticore.machine.Helpers

/// registers written by the host
class HostRegisters extends Bundle {
  val schedule_config: UInt                = UInt(64.W)
  val global_memory_instruction_base: UInt = UInt(64.W)
  val trace_dump_base: UInt                = UInt(64.W)
  // |               schedule_config                  |
  // +------------------------------------------------+
  // |63            56|55                            0|
  // +----------------+-------------------------------+
  // |      CMD       |           CMD DATA            |
  // -----------------+--------------------------------

}

/// registers written by the device, i.e., the compute grid
class DeviceRegisters extends Bundle {
  val virtual_cycles: UInt    = UInt(64.W)
  val bootloader_cycles: UInt = UInt(32.W) // for profiling
  val exception_id: UInt      = UInt(32.W)
  val execution_cycles: UInt  = UInt(64.W)
  val trace_dump_head: UInt   = UInt(64.W)
  val device_info: UInt       = UInt(32.W)
  val clock_stalls: UInt      = UInt(64.W)
}

class ManticoreFlatArrayInterface extends Bundle {

  val host_registers   = Input(new HostRegisters)
  val device_registers = Output(new DeviceRegisters)

  val start: Bool    = Input(Bool())
  val done: Bool     = Output(Bool())
  val idle: Bool     = Output(Bool())
  val memory_backend = Flipped(CacheConfig.frontInterface())
  val clock_active   = Output(Bool())
  val compute_clock  = Input(Clock())
  val control_clock  = Input(Clock())
  val clock_stabled  = Input(Bool())
  val reset          = Input(Bool())
}

class ClockDistribution extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val root_clock       = Input(Clock())
    val root_rst_n       = Input(Bool())  // rst_n coming from the root clock domain
    val compute_clock    = Output(Clock())
    val control_clock    = Output(Clock())
    val compute_clock_en = Input(Bool())
    val locked           = Output(Bool())
    val sync_rst_n       = Output(Bool()) // reset that can be used in the output clock domains
  })
  addResource("/verilog/ClockDistribution.v")
}

class ComputeArray(
    dimx: Int,
    dimy: Int,
    debug_enable: Boolean = false,
    enable_custom_alu: Boolean = true,
    prefix_path: String = ".",
    n_hop: Int = 1,
    // Multi-chip: hop fields must span the GLOBAL torus while this chip's array stays
    // dimx x dimy (0 = standalone, bundle dims == local dims). The X wrap then routes
    // through a TorusBoundary that can hand the seam links to a neighbouring chip at
    // runtime (extendX); the seams' latency/transport (TDM, transceivers) live OUTSIDE
    // this module — the array is topology-agnostic.
    torusDimX: Int = 0,
    torusDimY: Int = 0
) extends Module {

  val tX = if (torusDimX > 0) torusDimX else dimx
  val tY = if (torusDimY > 0) torusDimY else dimy
  require(tX >= dimx && tY >= dimy, "torus dims must be >= local array dims")

  val io = IO(new Bundle {
    val mem_access         = Flipped(CacheConfig.frontInterface())
    val config_packet      = Input(new NoCBundle(tX, tY, ManticoreFullISA))
    val config_enable      = Input(Bool())
    val exception_id       = Output(UInt(32.W))
    val exception_occurred = Output(Bool())
    val dynamic_cycle      = Output(Bool())
    val execution_active   = Output(Bool())
    // X-dimension torus extension (runtime): false = wrap closed locally (standalone)
    val extendX = Input(Bool())
    val xFwdOut = Output(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    val xFwdIn  = Input(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    val xBwdOut = Output(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    val xBwdIn  = Input(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    // val core_reset_done    = Output(Bool())
  })

  val equations = Seq.fill(1 << ManticoreBaseISA.FunctBits) {
    Seq.tabulate(ManticoreBaseISA.DataBits) { i => BigInt(1) << i }
  }

  def makeConfigData[T](gen: (Int, Int) => T): Seq[Seq[T]] =
    Seq.tabulate(dimx) { x =>
      Seq.tabulate(dimy) { y =>
        gen(x, y)
      }
    }

  def hasMemory(x: Int, y: Int): Boolean = (x == 0) && (y == 0)
  case class FatCore(core: ProcessorWithSendRecvPipe, switch: Switch, x: Int, y: Int)

  // Allow placement flexibility between cores and switches by using a dedicated reset tree for each.
  val core_reset_tree = Module(new CoreResetTree(dimx, dimy))

  // io.core_reset_done := core_reset_tree.io.last
  // val reset_tree = Module(new SoftResetTree(dimx, dimy))
  // io.core_reset_done := reset_tree.io.last

  val cores: Seq[Seq[FatCore]] = Seq.tabulate(dimx) { x =>
    Seq.tabulate(dimy) { y =>
      val core_conf = if (hasMemory(x, y)) ManticoreFullISA else ManticoreBaseISA

      val core = withReset(core_reset_tree.io.taps(x)(y)) {
        Module(
          new ProcessorWithSendRecvPipe(
            config = core_conf,
            DimX = tX, // hop fields span the global torus
            DimY = tY,
            x = x,
            y = y,
            equations = equations,
            initial_registers = s"${prefix_path}/rf_${x}_${y}.dat",
            initial_array = s"${prefix_path}/ra_${x}_${y}.dat",
            debug_enable = debug_enable,
            name_tag = s"CoreX${x}Y${y}",
            enable_custom_alu = enable_custom_alu
          )
        )
      }
      core.suggestName(s"core_${x}_${y}")

      val switch = Module(
        new Switch(tX, tY, core_conf, n_hop) // bundle dims = global torus; routing is relative
      )
      switch.suggestName(s"switch_${x}_${y}")

      FatCore(core, switch, x, y)
    }
  }

  // The X wrap links pass through a TorusBoundary: closed locally by default
  // (standalone torus, identical to the old hard wrap), or handed to a neighbouring
  // chip via the io.x* ports when extendX is set at runtime.
  val xBoundary = Module(new TorusBoundary(dimy, tX, tY, ManticoreFullISA))
  xBoundary.io.extend := io.extendX
  Range(0, dimy).foreach { y =>
    xBoundary.io.wrapFwdIn(y) := cores(dimx - 1)(y).switch.io.xOutput
    xBoundary.io.wrapBwdIn(y) := cores(0)(y).switch.io.xNegOutput
  }
  io.xFwdOut := xBoundary.io.extFwdOut
  xBoundary.io.extFwdIn := io.xFwdIn
  io.xBwdOut := xBoundary.io.extBwdOut
  xBoundary.io.extBwdIn := io.xBwdIn

  // connect the cores via switches
  Range(0, dimx).foreach { x =>
    Range(0, dimy).foreach { y =>
      // Eastbound (+X): packet from x-1 enters this switch; wrap via the boundary
      cores(x)(y).switch.io.xInput := {
        if (x == 0) xBoundary.io.wrapFwdOut(y)
        else        cores(x - 1)(y).switch.io.xOutput
      }
      // Westbound (-X): packet from x+1 enters this switch; wrap via the boundary
      cores(x)(y).switch.io.xNegInput := {
        if (x == dimx - 1) xBoundary.io.wrapBwdOut(y)
        else                cores(x + 1)(y).switch.io.xNegOutput
      }
      // Northbound (+Y): packet from y-1 enters this switch; wrap from dimy-1 to 0
      cores(x)(y).switch.io.yInput := {
        if (y == 0) cores(x)(dimy - 1).switch.io.yOutput
        else        cores(x)(y - 1).switch.io.yOutput
      }
      // Southbound (-Y): packet from y+1 enters this switch; wrap from 0 to dimy-1
      cores(x)(y).switch.io.yNegInput := {
        if (y == dimy - 1) cores(x)(0).switch.io.yNegOutput
        else                cores(x)(y + 1).switch.io.yNegOutput
      }

      if (debug_enable) {
        val switch_watcher = Module(
          new SwitchPacketInspector(
            DimX = dimx,
            DimY = dimy,
            config = ManticoreBaseISA,
            pos = (x, y),
            fatal = true
          )
        )
        val debug_time = RegInit(UInt(64.W), 0.U)
        debug_time                               := debug_time + 1.U
        cores(x)(y).core.io.periphery.debug_time := debug_time

        switch_watcher.io.xInput := {
          if (x == 0) cores(dimx - 1)(y).switch.io.xOutput
          else cores(x - 1)(y).switch.io.xOutput
        }
        switch_watcher.io.xNegInput := {
          if (x == dimx - 1) cores(0)(y).switch.io.xNegOutput
          else cores(x + 1)(y).switch.io.xNegOutput
        }
        switch_watcher.io.yInput := {
          if (y == 0) cores(x)(dimy - 1).switch.io.yOutput
          else cores(x)(y - 1).switch.io.yOutput
        }
        switch_watcher.io.yNegInput := {
          if (y == dimy - 1) cores(x)(0).switch.io.yNegOutput
          else cores(x)(y + 1).switch.io.yNegOutput
        }
        switch_watcher.io.lInput := cores(x)(y).core.io.packet_out

      } else {
        cores(x)(y).core.io.periphery.debug_time := 0.U
      }

      // connect the switches to the cores
      cores(x)(y).core.io.packet_in       := cores(x)(y).switch.io.yOutput
      cores(x)(y).core.io.packet_in.valid := cores(x)(y).switch.io.terminal
      cores(x)(y).switch.io.lInput        := cores(x)(y).core.io.packet_out

      cores(x)(y).core.io.periphery.cache.done  := false.B
      cores(x)(y).core.io.periphery.cache.idle  := false.B
      cores(x)(y).core.io.periphery.cache.rdata := 0.U
    }

    val master_core = cores.flatten.filter(c => hasMemory(c.x, c.y)).head

    master_core.core.io.periphery.cache <> io.mem_access

    // connect the configuration packet to the master core switch
    when(io.config_enable) {
      master_core.switch.io.xInput := io.config_packet
    }

    io.exception_id       := master_core.core.io.periphery.exception.id
    io.exception_occurred := master_core.core.io.periphery.exception.error
    io.dynamic_cycle      := master_core.core.io.periphery.dynamic_cycle
    io.execution_active   := master_core.core.io.periphery.active
  }

}
class ManticoreFlatArray(
    dimx: Int,
    dimy: Int,
    debug_enable: Boolean = false,
    enable_custom_alu: Boolean = true,
    prefix_path: String = ".",
    n_hop: Int = 1,
    // Multi-chip: global torus dims (0 = standalone). The local array is dimx x dimy;
    // hop fields/Programmer span the global torus; the X seam links are exposed via
    // the optional `xb` IO so the harness/system can attach TDM bridges + transceivers.
    torusDimX: Int = 0,
    torusDimY: Int = 0
) extends RawModule {

  val tX = if (torusDimX > 0) torusDimX else dimx
  val tY = if (torusDimY > 0) torusDimY else dimy
  private val multiChip = torusDimX > 0 || torusDimY > 0

  val io = IO(new ManticoreFlatArrayInterface)

  /** multi-chip boundary IO (present only when torus dims are set) */
  class XBoundaryIO extends Bundle {
    val extendX = Input(Bool())
    val fwdOut  = Output(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    val fwdIn   = Input(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    val bwdOut  = Output(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    val bwdIn   = Input(Vec(dimy, new NoCBundle(tX, tY, ManticoreFullISA)))
    // system-composition signals the harness needs to run a secondary (compute-only) chip
    val softResetOut    = Output(Bool()) // controller's soft reset (drives the other chip's array)
    val configEnableOut = Output(Bool()) // boot window (drives the TDM bridges' bypass)
  }
  val xb: Option[XBoundaryIO] = if (multiChip) Some(IO(new XBoundaryIO)) else None

  val controller = withClockAndReset(
    reset = io.reset,
    clock = io.control_clock
  ) {
    Module(new Management(dimx, dimy))
  }

  val memory_intercept = withClockAndReset(
    clock = io.control_clock,
    reset = io.reset
  ) {
    Module(new MemoryIntercept)
  }

  io.clock_active := controller.io.clock_active

  val bootloader = withClockAndReset(
    clock = io.compute_clock,
    reset = controller.io.soft_reset
  ) {
    Module(
      // the Programmer addresses the GLOBAL torus (its boot stream covers every core
      // of the composed system; the countdown sweep spans all tX x tY positions)
      new Programmer(ManticoreFullISA, tX, tY)
    )
  }
  controller.io.start         := io.start
  io.done                     := controller.io.done
  io.idle                     := controller.io.idle
  io.device_registers         := controller.io.device_registers

  controller.io.boot_finished := bootloader.io.running
  bootloader.io.start         := controller.io.boot_start
  bootloader.io.instruction_stream_base := io.host_registers.global_memory_instruction_base
    .pad(bootloader.io.instruction_stream_base.getWidth)
  bootloader.io.finish := false.B

  controller.io.core_revive_clock := memory_intercept.io.core_revive_clock

  val debug_time = withClockAndReset(
    clock = io.control_clock,
    reset = io.reset
  ) {
    RegInit(UInt(64.W), 0.U)
  }
  debug_time := debug_time + 1.U

  val compute_array = withClockAndReset(
    clock = io.compute_clock,
    reset = controller.io.soft_reset
  ) {
    Module(
      new ComputeArray(
        dimx = dimx,
        dimy = dimy,
        debug_enable = debug_enable,
        enable_custom_alu = enable_custom_alu,
        prefix_path = prefix_path,
        n_hop = n_hop,
        torusDimX = torusDimX,
        torusDimY = torusDimY
      )
    )
  }

  memory_intercept.io.cache_flush := controller.io.cache_flush_start
  memory_intercept.io.cache_reset := controller.io.cache_reset_start

  withClockAndReset(
    clock = io.control_clock,
    reset = controller.io.soft_reset
  ) {
    compute_array.io.config_enable := controller.io.config_enable
    compute_array.io.config_packet := bootloader.io.packet_out
  }

  compute_array.io.mem_access <> memory_intercept.io.core
  bootloader.io.memory_backend <> memory_intercept.io.boot

  io.memory_backend <> memory_intercept.io.cache

  memory_intercept.io.core_clock    := io.compute_clock
  memory_intercept.io.config_enable := controller.io.config_enable

  controller.io.exception_id            := compute_array.io.exception_id
  controller.io.core_exception_occurred := compute_array.io.exception_occurred
  controller.io.execution_active        := compute_array.io.execution_active
  controller.io.schedule_config         := io.host_registers.schedule_config
  controller.io.clock_locked            := io.clock_stabled

  controller.io.core_kill_clock := compute_array.io.dynamic_cycle
  controller.io.cache_done      := io.memory_backend.done

  // X seam boundary: standalone closes the ring inside ComputeArray (extendX=false,
  // empty ingress). Multi-chip exposes the cut links + control signals to the harness.
  xb match {
    case Some(b) =>
      compute_array.io.extendX := b.extendX
      b.fwdOut := compute_array.io.xFwdOut
      b.bwdOut := compute_array.io.xBwdOut
      compute_array.io.xFwdIn := b.fwdIn
      compute_array.io.xBwdIn := b.bwdIn
      b.softResetOut    := controller.io.soft_reset
      b.configEnableOut := controller.io.config_enable
    case None =>
      compute_array.io.extendX := false.B
      val empty = Wire(new NoCBundle(tX, tY, ManticoreFullISA))
      empty := DontCare
      empty.valid := false.B
      Range(0, dimy).foreach { y =>
        compute_array.io.xFwdIn(y) := empty
        compute_array.io.xBwdIn(y) := empty
      }
  }

}
