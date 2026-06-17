package manticore.machine.xrt

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.stage.ChiselStage
import chisel3.util.Cat
import chisel3.util.is
import chisel3.util.log2Ceil
import chisel3.util.pla
import chisel3.util.switch
import manticore.machine.ManticoreFullISA
import manticore.machine.core.ClockDistribution
import manticore.machine.core.DeviceRegisters
import manticore.machine.core.HostRegisters
import manticore.machine.core.ManticoreFlatArray
import manticore.machine.core.MemoryReadWriteInterface
import manticore.machine.memory.CacheConfig
import manticore.machine.memory.GmemBramBackend
import manticore.machine.memory.GmemBramPort
import manticore.machine.memory.SimGmem

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.collection.immutable.ListMap
import scala.util.matching.Regex
import manticore.machine.Helpers

class MemoryPointers extends Bundle {
  val pointer_0: UInt = UInt(64.W)
  // val pointer_1: UInt = UInt(64.W)

}

class ManticoreFlatKernel(
    DimX: Int,
    DimY: Int,
    enable_custom_alu: Boolean = true,
    debug_enable: Boolean = false,
    freqMhz: Double = 200.0,
    n_hop: Int = 1
    // m_axi_path: Seq[String] =
    //   Seq() // path to m_axi implementation if exits, uses simulation models otherwise
) extends RawModule {

  val clock = IO(Input(Clock()))
  clock.suggestName("ap_clk")
  val reset_n = IO(Input(Bool()))
  reset_n.suggestName("ap_rst_n")
  // val reset = Wire(Bool())
  // reset := ~reset_n

  val m_axi_bank_0  = IO(new AxiMasterIF(AxiCacheAdapter.CacheAxiParameters))
  val s_axi_control = IO(new AxiSlave.AxiSlaveCoreInterface())
  val interrupt     = IO(Output(Bool()))

  val clock_distribution = Module(new ClockDistribution())
  val reset              = WireDefault(!clock_distribution.io.sync_rst_n)
  clock_distribution.io.root_rst_n := reset_n
  clock_distribution.io.root_clock := clock

  val axi_cache = withClockAndReset(
    clock = clock_distribution.io.control_clock,
    reset = reset
  ) {
    Module(new CacheSubsystem)
  }
  val m_axi_bank_0_clock_crossing = Module(new Axi4ClockConverter(AxiCacheAdapter.CacheAxiParameters))
  val s_axi_clock_crossing = Module(
    new AxiLiteClockConverter(s_axi_control.AWADDR.getWidth, s_axi_control.WDATA.getWidth)
  )
  m_axi_bank_0_clock_crossing.s_axi_aclk    := clock_distribution.io.control_clock
  m_axi_bank_0_clock_crossing.s_axi_aresetn := clock_distribution.io.sync_rst_n
  m_axi_bank_0_clock_crossing.m_axi_aclk    := clock // connect to shell clock
  m_axi_bank_0_clock_crossing.m_axi_aresetn := reset_n
  s_axi_clock_crossing.m_axi_aclk           := clock_distribution.io.control_clock
  s_axi_clock_crossing.m_axi_resetn         := clock_distribution.io.sync_rst_n
  s_axi_clock_crossing.s_axi_aclk           := clock
  s_axi_clock_crossing.s_axi_resetn         := reset_n

  val slave =
    withClockAndReset(
      clock = clock_distribution.io.control_clock,
      reset = reset
    ) {
      Module(new AxiSlave(ManticoreFullISA))
    }

  slave.io.core <> s_axi_clock_crossing.m_axi
  s_axi_control <> s_axi_clock_crossing.s_axi

  interrupt := slave.io.control.interrupt

  val manticore =
    Module(new ManticoreFlatArray(DimX, DimY, debug_enable, enable_custom_alu = enable_custom_alu, n_hop = n_hop))

  manticore.io.reset         := reset
  manticore.io.control_clock := clock_distribution.io.control_clock
  manticore.io.compute_clock := clock_distribution.io.compute_clock
  manticore.io.clock_stabled := clock_distribution.io.locked

  clock_distribution.io.compute_clock_en := manticore.io.clock_active

  axi_cache.io.core <> manticore.io.memory_backend

  m_axi_bank_0_clock_crossing.m_axi <> m_axi_bank_0
  axi_cache.io.bus <> m_axi_bank_0_clock_crossing.s_axi

  axi_cache.io.base := slave.io.pointer_regs.pointer_0

  manticore.io.host_registers := slave.io.host_regs

  slave.io.dev_regs := manticore.io.device_registers

  slave.io.cache_regs := axi_cache.io.counters

  manticore.io.start := slave.io.control.ap_start

  slave.io.control.ap_done  := manticore.io.done
  slave.io.control.ap_idle  := manticore.io.idle
  slave.io.control.ap_ready := manticore.io.done // is this correct?

}

class ManticoreFlatSimKernel(
    DimX: Int,
    DimY: Int,
    debug_enable: Boolean = false,
    enable_custom_alu: Boolean = true,
    prefix_path: String = ".",
    // Distributed scheduled stall wave: gate the compute clock only on the reserved
    // STALL interrupt (countdown heartbeat) instead of immediately on any exception.
    // False (default) = legacy gate-on-exception, so existing tests are unchanged.
    stallWave: Boolean = false
) extends Module {

  clock.suggestName("ap_clk")
  reset.suggestName("ap_rst")

  class KernelRegisters extends Bundle {
    val host   = Input(new HostRegisters)
    val device = Output(new DeviceRegisters)
  }
  class KernelControl extends Bundle {
    val start: Bool = Input(Bool())
    val done: Bool  = Output(Bool())
    val idle: Bool  = Output(Bool())
  }

  class DirectMemoryInterface extends Bundle {
    val rdata: UInt  = Output(UInt(16.W))
    val wdata: UInt  = Input(UInt(16.W))
    val locked: Bool = Input(Bool())
    val wen: Bool    = Input(Bool())
    val addr: UInt   = Input(UInt(64.W))
  }

  class KernelInterface extends Bundle {
    val kernel_registers = new KernelRegisters
    val kernel_ctrl      = new KernelControl
    val dmi              = new DirectMemoryInterface
    // diagnostics: observe whether the cache ever writes back to global memory
    val dbg_axi_writes   = Output(UInt(32.W)) // count of AXI write-address handshakes into axi_mem
    val dbg_last_awaddr  = Output(UInt(64.W)) // last AXI write byte-address
    val dbg_last_wdata   = Output(UInt(64.W)) // low 64 bits of the last written cache line
  }

  val io = IO(new KernelInterface)

  val clock_distribution = Module(new ClockDistribution())

  clock_distribution.io.root_clock := clock

  val manticore =
    Module(new ManticoreFlatArray(DimX, DimY, debug_enable, enable_custom_alu, prefix_path, stallWave = stallWave))

  manticore.io.reset         := reset
  manticore.io.control_clock := clock_distribution.io.control_clock
  manticore.io.compute_clock := clock_distribution.io.compute_clock
  manticore.io.clock_stabled := clock_distribution.io.locked

  manticore.io.host_registers := io.kernel_registers.host
  io.kernel_registers.device  := manticore.io.device_registers

  manticore.io.start  := io.kernel_ctrl.start
  io.kernel_ctrl.done := manticore.io.done
  io.kernel_ctrl.idle := manticore.io.idle

  clock_distribution.io.compute_clock_en := manticore.io.clock_active

  // fixed-latency BRAM gmem (replaces CacheSubsystem + AxiMemoryModel; the gmem
  // clock-kill is gone — see GmemBramBackend)
  val gmem_backend = withClockAndReset(
    clock = clock_distribution.io.control_clock,
    reset = reset
  ) {
    Module(new GmemBramBackend(GmemBramBackend.addrBitsFor(1 << 20)))
  }
  val gmem = withClockAndReset(
    clock = clock_distribution.io.control_clock,
    reset = reset
  ) {
    Module(new SimGmem(1 << 20))
  }

  gmem_backend.io.front <> manticore.io.memory_backend
  gmem.io.bram <> gmem_backend.io.bram

  gmem.io.dmi.addr  := io.dmi.addr
  gmem.io.dmi.wdata := io.dmi.wdata
  gmem.io.dmi.wen   := io.dmi.wen
  io.dmi.rdata      := gmem.io.dmi.rdata
  // io.dmi.locked is unused: gmem is dual-ported, the host port never contends

  // the cache + AXI memory model are gone; the AXI write-observation debug
  // ports are kept for tester compatibility, tied off
  io.dbg_axi_writes  := 0.U
  io.dbg_last_awaddr := 0.U
  io.dbg_last_wdata  := 0.U

}

object GenerateIPs {

  def apply(
      ip_dir: Path,
      scripts_path: Path,
      part_number: String,
      cacheline_width: Int,
      freq: String
  ) = {

    if (Files.exists(ip_dir)) {
      scala.reflect.io.Directory(ip_dir.toFile()).deleteRecursively()
    }

    Files.createDirectories(ip_dir)
    Files.createDirectories(scripts_path)

    val fp     = scripts_path.resolve("gen_ip.tcl")
    val writer = Files.newBufferedWriter(fp)
    writer.write(scala.io.Source.fromResource("hls/gen_ip.tcl").mkString)
    writer.close()

    import scala.sys.process.{Process, ProcessLogger}
    val cmd =
      s"vivado -mode batch -source ${fp.toAbsolutePath()} -tclargs ${part_number} ${ip_dir.toAbsolutePath} ${freq} ${cacheline_width} "
    println(s"Running:\n${cmd}")
    val ret = Process(
      command = cmd,
      cwd = ip_dir.toFile()
    ).!(ProcessLogger(println(_)))
    if (ret != 0) {
      throw new Exception("Failed generating IPs")
    }
    ip_dir
  }
}
object PackageKernel {

  def apply(
      verilog_path: Path,
      ip_path: Path,
      packaged_path: Path,
      temp_kernel_package_path: Path,
      scripts_path: Path,
      kernel_xml_path: Path,
      xo_dir: Path,
      target: String,
      platform: String,
      top_name: String = "ManticoreKernel",
      slave_interface: String = "s_axi_control",
      master_interface: Seq[String] = Seq.tabulate(4) { i =>
        "m_axi_bank_" + i
      }
  ) = {

    val package_directory_path = packaged_path
    val temp_project_path      = temp_kernel_package_path
    val verilog_directory      = verilog_path
    val substitutions = Map(
      "@VERILOG_PATH@"             -> verilog_directory.toAbsolutePath().toString(),
      "@PACKAGED_PATH@"            -> package_directory_path.toAbsolutePath().toString(),
      "@TEMP_KERNEL_PACKAGE_PATH@" -> temp_project_path.toAbsolutePath().toString(),
      "@KERNEL_XML@"               -> kernel_xml_path.toAbsolutePath().toString(),
      "@SLAVE_INTERFACE@"          -> slave_interface,
      "@IP_PATH@"                  -> ip_path.toAbsolutePath().toString(),
      "@FPGA_NAME@"                -> ManticoreKernelGenerator.platformDevice(platform).part,
      "@TARGET@"                   -> target
    ) ++ master_interface.zipWithIndex.map { case (m, i) =>
      ("@MASTER_INTERFACE_" + i + "@") -> m
    }.toMap

    def substitute(line: String, subst: Map[String, String]): String = {
      subst
        .foldLeft(line) { case (l, (k, v)) => l.replace(k, v) }
    }

    Files.createDirectories(scripts_path)

    def createHlsTclScript(resource: String, fixer: String => String): Path = {
      val fp = scripts_path.resolve(resource + ".tcl")
      val tcl_commands = scala.io.Source
        .fromResource(s"hls/${resource}.tcl")
        .getLines()
        .map(fixer)
        .mkString("\n")
      val writer = Files.newBufferedWriter(fp)
      writer.write(tcl_commands)
      writer.close()
      fp
    }

    def writeXdc(fname: String)(content: => String) = {
      val fp     = verilog_path.resolve(fname)
      val writer = Files.newBufferedWriter(fp)
      writer.write(content)
      writer.close()
    }

    writeXdc("false_path.xdc") {
      s"""|
           |set_false_path -to [get_pins clock_distribution/rst_sync1_reg/CLR]
           |set_false_path -to [get_pins clock_distribution/rst_sync2_reg/CLR]
           |set_false_path -to [get_pins clock_distribution/rst_sync3_reg/CLR]
           |""".stripMargin
    }

    val packaging_tcl_fp = createHlsTclScript("package_kernel", line => substitute(line, substitutions))
    val gen_xo_tcl_fp = createHlsTclScript(
      "gen_xo",
      line =>
        substitute(
          line,
          substitutions ++ Map(
            "@PACKAGE_KERNEL_TCL@" -> packaging_tcl_fp
              .toAbsolutePath()
              .toString()
          )
        )
    )

    import scala.sys.process._

    if (
      "which vivado".!(
        ProcessLogger(l => println("which vivado gives: " + l))
      ) != 0
    ) {
      throw new Exception(
        "vivado not found, make sure vivado related environment variables are set!"
      )
    }

    Files.createDirectories(xo_dir)
    val xo_file =
      xo_dir.resolve(top_name + "." + target + "." + platform + ".xo")
    // remove the outpu path files if they already exist
    if (Files.exists(package_directory_path)) {
      scala.reflect.io
        .Directory(package_directory_path.toFile())
        .deleteRecursively()
    }
    if (Files.exists(temp_project_path)) {
      scala.reflect.io.Directory(temp_project_path.toFile()).deleteRecursively()
    }
    val success =
      sys.process
        .Process(
          command = s"vivado -mode batch -source ${gen_xo_tcl_fp.toAbsolutePath().toString()} -tclargs " +
            s"${xo_file.toAbsolutePath().toString} ${top_name} ${target} ${platform}",
          cwd = xo_dir.toFile()
        )
        .!(ProcessLogger(println(_))) == 0
    if (!success) {
      throw new Exception("Failed to package as XO")
    }
    xo_file

  }

}

object BuildXclbin {

  def apply(
      bin_dir: Path,
      xo_path: Path,
      target: String,
      platform: String,
      freqMhz: Double,
      dimx: Int,
      dimy: Int,
      top_name: String = "ManticoreKernel",
      ext_pblock: Option[File] = None,
      strategies: Seq[String] = Nil
  ) = {

    import scala.sys.process._

    if (
      "which vivado".!(
        ProcessLogger(l => println("which vivado gives: " + l))
      ) != 0
    ) {
      throw new Exception(
        "vivado not found, make sure vivado related environment variables are set!"
      )
    }

    Files.createDirectories(bin_dir)

    def getSystemInfo(): Int = {
      val lscpu           = "lscpu"
      val cpu_matcher     = raw"CPU\(s\):\s*(\d+)".r
      val thread_matcher  = raw"Thread\(s\) per core:\s*(\d+)".r
      val stdout          = new StringBuilder
      var cpus            = 0
      var thread_per_core = 0
      Process("lscpu").! {
        ProcessLogger { ln =>
          ln match {
            case cpu_matcher(x) =>
              cpus = x.toInt
            case _ => // nothing
          }
          ln match {
            case thread_matcher(x) => thread_per_core = x.toInt
            case _                 => // nothing
          }
        }
      }
      if (cpus == 0) {
        throw new Exception("Could not determine the number of CPUs")
      }
      if (thread_per_core == 0) {
        throw new Exception("Could not determine the number of CPUs threads")
      }
      cpus / thread_per_core
    }

    val xclbin_path =
      bin_dir.resolve(s"${top_name}.${target}.${platform}.xclbin")
    val max_threads = Runtime.getRuntime().availableProcessors()

    def createVivadoTclScript(srcName: String, dstName: String): Path = {
      val fp     = bin_dir.resolve(dstName)
      val writer = Files.newBufferedWriter(fp)
      writer.write(scala.io.Source.fromResource(s"vivado/${srcName}").mkString)
      writer.close()
      fp
    }

    def createPblocksTcl(fname: String): Path = createVivadoTclScript(fname, "pblocks.tcl")

    val pblocks = ext_pblock match {
      case None =>
        if (dimx * dimy > 160) {
          createPblocksTcl("pblocks_large.xdc")
        } else {
          createPblocksTcl("pblocks_small.xdc")
        }

      case Some(ext_file) =>
        ext_file.toPath()
    }

    val cpus = getSystemInfo() min 12

    val extraRuns = strategies match {
      case Nil =>
        ""

      case _ =>
        s"--vivado.impl.strategies \"${strategies.mkString(",")}\" " + (strategies
          .map { s =>
            s"--vivado.prop run.impl_${s}.STEPS.PLACE_DESIGN.TCL.PRE=${pblocks.toAbsolutePath()} "
          }
          .mkString(" "))
    }

    val command =
      s"v++ --link -g -t ${target} --platform ${platform} --save-temps " +
        s"--optimize 3 " + extraRuns +
        s"--vivado.prop run.impl_1.STEPS.PLACE_DESIGN.TCL.PRE=${pblocks.toAbsolutePath()} " +
        s"--vivado.synth.jobs ${cpus} --vivado.impl.jobs ${cpus} " +
        s"-o ${xclbin_path.toAbsolutePath.toString} " +
        s"${xo_path.toAbsolutePath.toString}"

    println(s"Executing:\n${command}")
    val success = Process(command = command, cwd = bin_dir.toFile())
      .!(ProcessLogger(println(_))) == 0
    if (!success) {
      println("v++ failed!")
      throw new Exception("Could not build the xclbin!")
    }
  }
}

object ManticoreKernelGenerator {

  object KernelGenerationSteps extends Enumeration {
    val Type                                   = Value
    val Slave, Master, Kernel, Xml, Xo, Xclbin = Value
  }

  sealed abstract class Device
  object U200 extends Device
  object U250 extends Device
  object U280 extends Device
  // Non-Vitis target: KCU105 evaluation board (Kintex UltraScale XCKU040). Used by the
  // plain-Vivado (no v++/xclbin) flow in KCU105Generator.
  object KCU105 extends Device

  case class Platform(name: String, part: String, device: Device)

  val platformDevice = Map(
    {
      val name = "kcu105"
      name -> Platform(name, "xcku040-ffva1156-2-e", KCU105)
    }, {
      val name = "xilinx_u200_gen3x16_xdma_1_202110_1"
      name -> Platform(name, "xcu200-fsgd2104-2-e", U200)
    }, {
      val name = "xilinx_u200_gen3x16_xdma_2_202110_1"
      name -> Platform(name, "xcu200-fsgd2104-2-e", U200)
    }, {
      val name = "xilinx_u250_gen3x16_xdma_3_1_202020_1"
      name -> Platform(name, "xcu250-figd2104-2l-e", U250)
    }, {
      val name = "xilinx_u250_gen3x16_xdma_4_1_202210_1"
      name -> Platform(name, "xcu250-figd2104-2l-e", U250)
    }, {
      val name = "xilinx_u280_gen3x16_xdma_1_202211_1"
      name -> Platform(name, "xcu280-fsvh2892-2L-e", U280)
    }
  )

  def apply(
      target_dir: String,
      platform: String = "xilinx_u200_gen3x16_xdma_2_202110_1",
      target: String = "hw_emu",
      dimx: Int = 8,
      dimy: Int = 8,
      enable_custom_alu: Boolean = true,
      freqMhz: Double = 200.0,
      n_hop: Int = 2,
      pblock: Option[File] = None,
      strategies: Seq[String] = Nil
  ) = {

    val out_dir = Paths.get(target_dir)

    val hdl_dir = Files.createDirectories(out_dir.resolve("hdl"))

    println("generating top module")
    val manticoreVlogOrig = new ChiselStage().emitVerilog(
      new ManticoreFlatKernel(
        DimX = dimx,
        DimY = dimy,
        enable_custom_alu = enable_custom_alu,
        debug_enable = false,
        freqMhz = freqMhz,
        n_hop = n_hop
      ),
      Array(
        "--target-dir",
        hdl_dir.toAbsolutePath().toString(),
        // MUST leave no-dedup for annotations to appear.
        // https://github.com/chipsalliance/firrtl/issues/2168
        "--no-dedup",
        "--emission-options=disableMemRandomization,disableRegisterRandomization"
      )
    )

    // Insert KEEP_HIERARCHY annotations on elements which are manually floorplanned.
    val manticoreVlogWithKeepHierarchy = manticoreVlogOrig
      .split("\n")
      .map { line =>
        val pattern = new Regex(
          """(\s*)(Processor|Switch|WrappedPipeWithStyle|BoolTree|Management|Programmer)(_\d+)?\s+\w+""",
          "indent"
        )
        pattern.findFirstMatchIn(line) match {
          case None => line
          case Some(value) =>
            val indent = value.group("indent")
            s"${indent}(* keep_hierarchy = \"yes\" *)${line}"
        }
      }
      .mkString("\n")

    // Overwrite the verilog file.
    Seq(
      // (manticoreVlogOrig, hdl_dir.resolve("ManticoreFlatKernel_orig.v")),
      (manticoreVlogWithKeepHierarchy, hdl_dir.resolve("ManticoreFlatKernel.v"))
    ).foreach { case (vlog, path) =>
      val writer = Files.newBufferedWriter(path)
      writer.write(vlog)
      writer.close()
    }

    val xml_path = {
      val p       = hdl_dir.resolve("kernel.xml")
      val printer = new PrintWriter(p.toFile)
      printer.print(new scala.xml.PrettyPrinter(32, 2).format(AxiSlave.kernelXml))
      printer.close()
      p
    }

    val cppHeaderPath = {
      val p       = hdl_dir.resolve("register.hpp")
      val printer = new PrintWriter(p.toFile)
      printer.print(AxiSlave.header)
      printer.close()
      p
    }

    val ip_path = out_dir.resolve("ip_location")
    GenerateIPs(
      ip_dir = ip_path,
      scripts_path = out_dir.resolve("scripts"),
      part_number = platformDevice(platform).part,
      freq = freqMhz.toString(),
      cacheline_width = CacheConfig.CacheLineBits
    )

    val xo_file = PackageKernel(
      verilog_path = hdl_dir,
      ip_path = ip_path,
      packaged_path = out_dir.resolve("packaged"),
      temp_kernel_package_path = out_dir.resolve("temp_packaged"),
      scripts_path = out_dir.resolve("scripts"),
      kernel_xml_path = xml_path,
      platform = platform,
      target = target,
      xo_dir = out_dir.resolve("bin")
    )
    println("Created Xilinx Object")

    BuildXclbin(
      bin_dir = out_dir.resolve("bin"),
      xo_path = xo_file,
      target = target,
      freqMhz = freqMhz,
      platform = platform,
      dimx = dimx,
      dimy = dimy,
      ext_pblock = pblock,
      strategies = strategies
    )

  }

}

/** Non-Vitis RTL generator for the KCU105 (XCKU040).
  *
  * Emits the same `ManticoreFlatKernel.v` as the Alveo flow (with the `keep_hierarchy`
  * annotations) and generates the support IPs (`clk_dist` clk_wiz, `axi4_clock_converter`,
  * `axi4lite_clock_converter`) for the part, then STOPS. No `package_xo`, no `v++`: the
  * kernel is dropped into a plain Vivado block design instead (see `vivado/kcu105/`).
  *
  * Run with `-Dmanticore.no_uram=true` so URAM-backed memories map to BRAM (the XCKU040
  * has no URAM).
  */
object KCU105Generator {

  // gmem size in 16-bit words (log2); must match ManticoreFlatBramKernel's
  // default and build_kcu105.tcl's mem_kib (17 -> 256 KiB)
  val kcu105GmemAddrBits = 17

  def apply(
      target_dir: String,
      dimx: Int = 2,
      dimy: Int = 2,
      enable_custom_alu: Boolean = false,
      freqMhz: Double = 100.0,
      n_hop: Int = 1,
      gen_ips: Boolean = true,
      platform: String = "kcu105"
  ): Unit = {

    val part    = ManticoreKernelGenerator.platformDevice(platform).part
    val out_dir = Paths.get(target_dir)
    val hdl_dir = Files.createDirectories(out_dir.resolve("hdl"))

    if (!MemStyleNoUramCheck.ok) {
      println(
        "WARNING: -Dmanticore.no_uram is not set; the XCKU040 has no URAM and synthesis " +
          "will fail. Re-run generation with -Dmanticore.no_uram=true."
      )
    }

    println(s"[kcu105] emitting ManticoreFlatBramKernel (${dimx}x${dimy}, ${freqMhz} MHz, part ${part})")
    val vlog = new ChiselStage().emitVerilog(
      new ManticoreFlatBramKernel(
        DimX = dimx,
        DimY = dimy,
        enable_custom_alu = enable_custom_alu,
        debug_enable = false,
        freqMhz = freqMhz,
        n_hop = n_hop
      ),
      Array(
        "--target-dir",
        hdl_dir.toAbsolutePath().toString(),
        "--no-dedup",
        "--emission-options=disableMemRandomization,disableRegisterRandomization"
      )
    )

    // X_INTERFACE_INFO so the block design sees the kernel's gmem pin group as
    // a BRAM-controller-compatible slave (axi_bram_ctrl/BRAM_PORTA connects to
    // it directly, keeping the JTAG image-load flow at AXI offset 0x0).
    val gmemIfaceRoles = Map(
      "gmem_clk"  -> "CLK",
      "gmem_rst"  -> "RST",
      "gmem_en"   -> "EN",
      "gmem_we"   -> "WE",
      "gmem_addr" -> "ADDR",
      "gmem_din"  -> "DIN",
      "gmem_dout" -> "DOUT"
    )
    def annotatePort(line: String): String = {
      val portDecl = new Regex("""^(\s*)(input|output)(\s+(?:\[[^\]]+\]\s*)?)(gmem_\w+)([,)]?\s*)$""")
      portDecl.findFirstMatchIn(line) match {
        case Some(m) if gmemIfaceRoles.contains(m.group(4)) =>
          val role = gmemIfaceRoles(m.group(4))
          // Full BRAM-slave property set: axi_bram_ctrl's post_propagate script
          // requires these from a connected custom RTL slave (MASTER_TYPE alone
          // makes it demand the rest, e.g. READ_WRITE_MODE).
          val gmemBytes = 1 << (kcu105GmemAddrBits + 1)
          val param =
            if (m.group(4) == "gmem_clk")
              s"""(* X_INTERFACE_PARAMETER = "MASTER_TYPE BRAM_CTRL, MEM_SIZE ${gmemBytes}, MEM_WIDTH 32, MEM_ECC NONE, READ_WRITE_MODE READ_WRITE, READ_LATENCY 2" *) """
            else ""
          s"""${m.group(1)}${param}(* X_INTERFACE_INFO = "xilinx.com:interface:bram:1.0 GMEM ${role}" *) ${line.trim}"""
        case _ => line
      }
    }

    // Same keep_hierarchy annotation as the Alveo flow, so hierarchical constraints work.
    val vlogWithKeep = vlog
      .split("\n")
      .map { line =>
        val pattern = new Regex(
          """(\s*)(Processor|Switch|WrappedPipeWithStyle|BoolTree|Management|Programmer)(_\d+)?\s+\w+""",
          "indent"
        )
        pattern.findFirstMatchIn(line) match {
          case None => annotatePort(line)
          case Some(value) =>
            val indent = value.group("indent")
            s"""${indent}(* keep_hierarchy = "yes" *)${line}"""
        }
      }
      .mkString("\n")

    val kernelV = hdl_dir.resolve("ManticoreFlatBramKernel.v")
    val w       = Files.newBufferedWriter(kernelV)
    w.write(vlogWithKeep)
    w.close()

    // false_path constraint for the reset synchronizer (same as the Alveo packaging).
    val fp = Files.newBufferedWriter(hdl_dir.resolve("false_path.xdc"))
    fp.write(
      """|set_false_path -to [get_pins clock_distribution/rst_sync1_reg/CLR]
         |set_false_path -to [get_pins clock_distribution/rst_sync2_reg/CLR]
         |set_false_path -to [get_pins clock_distribution/rst_sync3_reg/CLR]
         |""".stripMargin
    )
    fp.close()

    // Emit the register map header so the JTAG driver has the offsets.
    val hpp = new PrintWriter(hdl_dir.resolve("register.hpp").toFile)
    hpp.print(AxiSlave.header)
    hpp.close()

    println(s"[kcu105] wrote ${kernelV.toAbsolutePath()}")

    import scala.sys.process.{Process, ProcessLogger}
    val haveVivado = Process("which vivado").!(ProcessLogger(_ => ())) == 0
    if (gen_ips && haveVivado) {
      GenerateIPs(
        ip_dir = out_dir.resolve("ip_location"),
        scripts_path = out_dir.resolve("scripts"),
        part_number = part,
        freq = freqMhz.toString(),
        cacheline_width = CacheConfig.CacheLineBits
      )
      println(s"[kcu105] generated support IPs under ${out_dir.resolve("ip_location")}")
    } else if (gen_ips) {
      println("[kcu105] vivado not on PATH; skipping IP generation (emitted RTL only)")
    }
  }

  private object MemStyleNoUramCheck {
    val ok: Boolean =
      sys.props.get("manticore.no_uram").exists(v => v == "true" || v == "1")
  }
}

// object KernelTest extends App {
//   val dimx = 16
//   val dimy = 16
//   val out_dir =
//     Paths.get(
//       s"gen-dir/kernel/${dimx}x${dimy}_100MHz_to_200MHz_8KiB/hw"
//     )

//   ManticoreKernelGenerator(
//     target_dir = out_dir.toAbsolutePath().toString(),
//     dimx = dimx,
//     dimy = dimy,
//     target = "hw"
//   )
//   // val master_fp = AxiMasterGenerator(
//   //   name = "memory_gateway"
//   //   // out_dir = Some(hdl_dir),
//   // )
//   // new ChiselStage().emitVerilog(
//   //   new ManticoreKernel(
//   //     4,
//   //     4,
//   //     true,
//   //     master_fp.map(_.toAbsolutePath().toString())
//   //   ),
//   //   Array("-td", "gen-dir/sim/hdl")
//   // )

// }
