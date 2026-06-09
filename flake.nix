{
  description = "Manticore hardware (Chisel) build toolchain";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # chiseltest 0.5.1 targets Verilator 4.x; Verilator 5's precompiled headers break its
    # build invocation (no --no-pch flag). Pin a Verilator 4.x from an older nixpkgs.
    nixpkgs-v4.url = "github:NixOS/nixpkgs/nixos-22.11";
  };

  outputs = { self, nixpkgs, nixpkgs-v4, flake-utils }: flake-utils.lib.eachDefaultSystem (
    system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };
        verilator4 = (import nixpkgs-v4 { inherit system; }).verilator;

        # Chisel 3.5.1 / firrtl is happiest on a JDK <= 11. Use the prebuilt Eclipse
        # Temurin binary rather than nixpkgs' source-built OpenJDK: the latter (11.0.32
        # "adhoc..source") SIGSEGVs inside libjvm.so on this host (crash in coursier's
        # dep-fetch threads). The Adoptium binary is stable here.
        jdk = pkgs.temurin-bin-11;
        sbt = pkgs.sbt.override { jre = jdk; };
      in {
        devShells.default = pkgs.mkShell {
          # NOTE: Vivado is intentionally NOT managed by nix. Source it externally,
          # exactly as the build scripts expect:
          #   . /opt/tools/Xilinx/Vivado/2022.1/settings64.sh
          buildInputs = [
            # Chisel / Scala build toolchain
            sbt
            jdk
            pkgs.scala_2_13

            # pblock extraction / helper scripts under src/main/scala/.../xrt
            pkgs.python3

            # build.sh helpers
            pkgs.jq
            pkgs.which
            pkgs.gnumake

            # local RTL simulation (chiseltest / verilator backend) — Verilator 4.x for
            # chiseltest 0.5.1 compatibility (see nixpkgs-v4 input).
            verilator4

            # sbt/coursier need these to fetch dependencies over https
            pkgs.git
            pkgs.cacert

            # non-interactive bash for the shellHook
            pkgs.bashInteractive
          ];

          # com.google.ortools:ortools-java ships a bundled libortools/libjniortools
          # .so that dlopen()s the C++ runtime. It needs libstdc++ at *runtime* (the
          # `placement` command), but we must NOT put this on LD_LIBRARY_PATH globally:
          # doing so makes the JVM itself load a mismatched libstdc++/libgcc and crash
          # (SIGSEGV in libjvm.so). Expose the path under a separate var and let the
          # placement invocation opt in via LD_LIBRARY_PATH only when it runs ortools.
          shellHook = ''
            export LC_ALL="C.UTF-8";
            export JAVA_HOME="${jdk}";
            export ORTOOLS_NATIVE_LIBS="${pkgs.stdenv.cc.cc.lib}/lib:${pkgs.zlib}/lib";
          '';
        };
      }
  );
}
