package manticore.machine

import chisel3.stage.ChiselStage
import manticore.machine.xrt.ManticoreFlatSimKernel
import manticore.machine.xrt.ManticoreKernelGenerator
import scopt.OParser

import java.io.File
import java.io.PrintWriter
import manticore.machine.xrt.ManticoreKernelGenerator.U200
import manticore.machine.xrt.ManticoreKernelGenerator.U250
import manticore.machine.xrt.ManticoreKernelGenerator.U280
import manticore.machine.xrt.U200FloorplanImpl
import manticore.machine.xrt.U250FloorplanImpl
import manticore.machine.xrt.U280FloorplanImpl

object Main {

  case class CliConfig(
      dimx: Int = 2,
      dimy: Int = 2,
      enable_custom_alu: Boolean = true,
      // multi-chip bittide: this chip is a dimx x dimy sub-array of a
      // torus_dimx x torus_dimy global torus (0 = single standalone chip, no seams).
      torus_dimx: Int = 0,
      torus_dimy: Int = 0,
      stall_wave: Boolean = false,
      target: String = "<INV>",
      output: File = new File("."),
      platform: String = "",
      list_platforms: Boolean = false,
      freq: Double = 200.0,
      n_hop: Int = 1,
      do_placement: Boolean = false,
      placement_alg: String = "",
      pblock: Option[File] = None,
      max_cores_per_pblock: Int = 5,
      strategies: Seq[String] = Seq.empty
      // strategies: Seq[String] = Seq("Performance_Explore", "Performance_NetDelay_high")
  )

  private val validStrategies = Seq(
    "Performance_Explore",
    "Congestion_SpreadLogic_Explore",
    "Performance_NetDelay_high",
    "Performance_NetDelay_low",
    "Performance_ExtraTimingOpt",
    "Congestion_SpreadLogic_high",
    "Congestion_SpreadLogic_medium",
    "Congestion_SpreadLogic_low"
  )

  def parseArgs(args: Seq[String]): CliConfig = {
    val project_version = getClass.getPackage.getImplementationVersion
    val parser = {
      val builder = OParser.builder[CliConfig]
      import builder._
      OParser.sequence(
        programName("mgen"),
        head("Manticore hardware generator", project_version),
        opt[File]('o', "output")
          .action { case (f, c) => c.copy(output = f) }
          .required()
          .text("Output directory"),
        opt[Int]('x', "dimx")
          .action { case (x, c) => c.copy(dimx = x) }
          .required()
          .text("X dimension size"),
        opt[Int]('y', "dimy")
          .action { case (y, c) => c.copy(dimy = y) }
          .required()
          .text("Y dimension size"),
        opt[Boolean]("enable_custom_alu")
          .action { case (b, c) => c.copy(enable_custom_alu = b) }
          .text("Enable custom ALUs in cores"),
        opt[String]('t', "target")
          .action { case (t, c) => c.copy(target = t) }
          // .required()
          .text("build target (hw, hw_emu, sim)"),
        opt[String]('p', "platform")
          .action { case (p, c) => c.copy(platform = p) }
          .text("build platform (use --list to see a list of available ones), only affects hw and hw_emu"),
        opt[Double]('f', "freq")
          .action { case (p, c) => c.copy(freq = p) }
          .text(
            "desired operating frequency, default = 200"
          ),
        opt[Seq[String]]('s', "strategies")
          .action { case (s, c) => c.copy(strategies = s) }
          .validate { s =>
            if (s.forall(validStrategies.contains)) {
              success
            } else {
              failure(s"Invalid strategy. Possible strategies are ${validStrategies.mkString(",")}")
            }
          },
        opt[Int]("n_hop")
          .action { case (p, c) => c.copy(n_hop = p) }
          .text("number of hops between cores"),
        opt[Int]("torus_dimx")
          .action { case (v, c) => c.copy(torus_dimx = v) }
          .text("global torus X dimension for a multi-chip bittide build (0 = single chip)"),
        opt[Int]("torus_dimy")
          .action { case (v, c) => c.copy(torus_dimy = v) }
          .text("global torus Y dimension for a multi-chip bittide build (0 = single chip)"),
        opt[Boolean]("stall_wave")
          .action { case (b, c) => c.copy(stall_wave = b) }
          .text("enable the distributed scheduled stall wave (multi-chip)"),
        opt[Unit]("list")
          .action { case (b, c) => c.copy(list_platforms = true) }
          .text("print a list of available platforms"),
        opt[File]('P', "pblock")
          .action { case (f, c) => c.copy(pblock = Some(f)) }
          .text("pblock constraint file (advanced)"),
        cmd("placement")
          .action { case (_, c) => c.copy(do_placement = true) }
          .text("generate a pblock constraint file")
          .children(
            opt[String]("algorithm")
              .action { case (alg, c) => c.copy(placement_alg = alg) }
              .text("placement algorithm to use")
              .required()
          ),
        checkConfig { c =>
          if (!c.do_placement) {
            c.target match {
              case _ @("hw" | "hw_emu" | "sim" | "kcu105" | "bittide") => success
              case t @ _                                               => failure(s"invalid target ${t}")
            }
          } else {
            success
          }
        },
        help('h', "help").text("print usage text and exit")
      )

    }

    OParser.parse(parser, args, CliConfig()) match {
      case Some(config: CliConfig) => config
      case _ =>
        sys.error("Failed parsing arguments")
    }
  }

  def listPlatforms(): Unit = {
    Console.println("Available platforms: \n")
    ManticoreKernelGenerator.platformDevice.foreach { case (p, _) =>
      println(s"\t${p}")
    }
  }

  def main(args: Array[String]): Unit = {

    val cfg = parseArgs(args.toSeq)

    if (cfg.list_platforms) {
      listPlatforms()
      sys.exit(0)
    }
    if (cfg.do_placement) {
      val platform = ManticoreKernelGenerator.platformDevice.get(cfg.platform) match {
        case None        => sys.error(s"Platform not specified!")
        case Some(value) => value
      }

      val constraints = platform.device match {
        case U200 =>
          val validChoices = Set(
            U200FloorplanImpl.Auto.getName(),
            U200FloorplanImpl.HighwaySwitch.getName(),
            U200FloorplanImpl.RigidIslandSwitchRigidCores.getName(),
            U200FloorplanImpl.LooseIslandSwitchRigidCores.getName(),
            U200FloorplanImpl.LooseIslandSwitchLooseCores.getName(),
            U200FloorplanImpl.LooseIslandSwitchRigidCoresCenterOutward.getName(),
            U200FloorplanImpl.LooseIslandSwitchLooseCoresCenterOutward.getName()
          )

          if (cfg.placement_alg == U200FloorplanImpl.Auto.getName()) {
            U200FloorplanImpl.Auto.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else if (cfg.placement_alg == U200FloorplanImpl.HighwaySwitch.getName()) {
            U200FloorplanImpl.HighwaySwitch.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else if (cfg.placement_alg == U200FloorplanImpl.RigidIslandSwitchRigidCores.getName()) {
            U200FloorplanImpl.RigidIslandSwitchRigidCores.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else if (cfg.placement_alg == U200FloorplanImpl.LooseIslandSwitchRigidCores.getName()) {
            U200FloorplanImpl.LooseIslandSwitchRigidCores.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else if (cfg.placement_alg == U200FloorplanImpl.LooseIslandSwitchLooseCores.getName()) {
            U200FloorplanImpl.LooseIslandSwitchLooseCores.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else if (cfg.placement_alg == U200FloorplanImpl.LooseIslandSwitchRigidCoresCenterOutward.getName()) {
            U200FloorplanImpl.LooseIslandSwitchRigidCoresCenterOutward.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else if (cfg.placement_alg == U200FloorplanImpl.LooseIslandSwitchLooseCoresCenterOutward.getName()) {
            U200FloorplanImpl.LooseIslandSwitchLooseCoresCenterOutward.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else {
            sys.error(s"Invalid placement algorithm! Valid choices are ${validChoices.mkString(", ")}")
          }

        case U250 =>
          val validChoices = Set(
            U250FloorplanImpl.LooseSlr2Slr3.getName()
          )
          if (cfg.placement_alg == U250FloorplanImpl.LooseSlr2Slr3.getName()) {
            U250FloorplanImpl.LooseSlr2Slr3.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else {
            sys.error(s"Invalid placement algorithm! Valid choices are ${validChoices.mkString(", ")}")
          }

        case U280 =>
          val validChoices = Set(
            U280FloorplanImpl.LooseNonShell.getName()
          )
          if (cfg.placement_alg == U280FloorplanImpl.LooseNonShell.getName()) {
            U280FloorplanImpl.LooseNonShell.toTcl(cfg.dimx, cfg.dimy, cfg.enable_custom_alu)
          } else {
            sys.error(s"Invalid placement algorithm! Valid choices are ${validChoices.mkString(", ")}")
          }
      }

      val writer = new PrintWriter(cfg.output)
      writer.write(constraints)
      writer.close()
    } else {

      cfg.target match {

        case t @ ("hw" | "hw_emu") =>
          if (cfg.platform.isEmpty) {
            sys.error("the selected target requires a platform.")
          }

          ManticoreKernelGenerator.platformDevice.get(cfg.platform) match {
            case None => sys.error(s"Unknown platform ${cfg.platform}")
            case _    => // ok
          }
          Console.println(
            s"Starting code generation for ${cfg.platform}.${cfg.target}"
          )
          ManticoreKernelGenerator(
            target_dir = cfg.output.toPath.toAbsolutePath.toString,
            platform = cfg.platform,
            dimx = cfg.dimx,
            dimy = cfg.dimy,
            target = t,
            enable_custom_alu = cfg.enable_custom_alu,
            freqMhz = cfg.freq,
            n_hop = cfg.n_hop,
            pblock = cfg.pblock,
            strategies = cfg.strategies
          )

        case "kcu105" =>
          // Non-Vitis RTL flow for the KCU105 (XCKU040): emit ManticoreFlatKernel.v and
          // generate the support IPs for the part; no .xo / no v++ / no xclbin.
          Console.println(
            s"Starting KCU105 RTL generation (${cfg.dimx}x${cfg.dimy} @ ${cfg.freq} MHz)"
          )
          manticore.machine.xrt.KCU105Generator(
            target_dir = cfg.output.toPath.toAbsolutePath.toString,
            dimx = cfg.dimx,
            dimy = cfg.dimy,
            enable_custom_alu = cfg.enable_custom_alu,
            freqMhz = cfg.freq,
            n_hop = cfg.n_hop
          )

        case "bittide" =>
          // Per-chip top for the bittide demo rig (wrapped by a Clash inst
          // blackbox inside a WireDemo UserCore). Flat pins, no MMCM, gmem on
          // an internal fixed-latency BRAM. -Dmanticore.no_uram=true required
          // for the KCU105 rig (xcku040 has no URAM).
          Console.println(
            s"Starting bittide chip RTL generation (${cfg.dimx}x${cfg.dimy})"
          )
          new ChiselStage().emitVerilog(
            new manticore.machine.xrt.ManticoreBittideChip(
              DimX = cfg.dimx,
              DimY = cfg.dimy,
              enable_custom_alu = cfg.enable_custom_alu,
              debug_enable = false,
              n_hop = cfg.n_hop,
              torusDimX = cfg.torus_dimx,
              torusDimY = cfg.torus_dimy,
              stallWave = cfg.stall_wave
            ),
            Array(
              "--target-dir",
              cfg.output.toPath.toString,
              "--no-dedup",
              "--emission-options=disableMemRandomization,disableRegisterRandomization"
            )
          )

        case "sim" =>
          new ChiselStage().emitVerilog(
            new ManticoreFlatSimKernel(
              DimX = cfg.dimx,
              DimY = cfg.dimy,
              debug_enable = true,
              enable_custom_alu = cfg.enable_custom_alu
            ),
            Array("--target-dir", cfg.output.toPath.toString)
          )
        case _ =>
          sys.error("Unknown platform!")

      }
    }

  }

}
