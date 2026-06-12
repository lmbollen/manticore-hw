# =============================================================================
# build_kcu105.tcl — plain-Vivado (no Vitis/XRT) build of a Manticore instance
# for the KCU105 (XCKU040), with on-chip BRAM memory + JTAG-to-AXI control.
#
# Usage:
#   vivado -mode batch -source build_kcu105.tcl -tclargs <hdl_dir> <build_dir> \
#          [part] [freq_mhz] [stop_after] [mem_kib]
#
#   hdl_dir    : dir containing ManticoreFlatKernel.v + BRAMLike.v + AluDsp48.v +
#                ClockDistribution.v + false_path.xdc (output of `-t kcu105` gen)
#   build_dir  : output/project dir
#   part        : FPGA part (default xcku040-ffva1156-2-e; falls back to the
#                 installed xcku035-ffva1156-2-e stand-in if xcku040 is absent)
#   ap_clk_mhz  : AXI / shell clock fed to the kernel's ap_clk (default 200). The
#                 300 MHz board clock is divided down to this by the BD clk_wiz, and
#                 the kernel's internal clk_dist takes this as its input frequency.
#   compute_mhz : internal compute/control clock (default 100). Kept low for the
#                 trivial bring-up; the cores/cache/NoC need Performance strategies +
#                 floorplanning to approach the Alveo design's 475 MHz.
#   stop_after  : one of {bd, synth, bits} (default bits)
#   mem_kib     : on-chip BRAM size in KiB (default 256)
# =============================================================================

if {$argc < 2} {
  puts "ERROR: need at least <hdl_dir> <build_dir>"
  exit 1
}
set hdl_dir     [file normalize [lindex $argv 0]]
set build_dir   [file normalize [lindex $argv 1]]
set part        [expr {$argc > 2 ? [lindex $argv 2] : "xcku040-ffva1156-2-e"}]
set ap_clk_mhz  [expr {$argc > 3 ? [lindex $argv 3] : 200.0}]
set compute_mhz [expr {$argc > 4 ? [lindex $argv 4] : 100.0}]
set stop_after  [expr {$argc > 5 ? [lindex $argv 5] : "bits"}]
set mem_kib     [expr {$argc > 6 ? [lindex $argv 6] : 256}]

# Fall back to an installed stand-in part of the same family/package if the
# requested part's device support is not installed.
if {[llength [get_parts -quiet $part]] == 0} {
  puts "WARNING: part '$part' not installed."
  if {[llength [get_parts -quiet xcku035-ffva1156-2-e]] > 0} {
    set part "xcku035-ffva1156-2-e"
    puts "WARNING: falling back to stand-in part '$part' (same kintexu / ffva1156)."
  } else {
    puts "ERROR: no suitable kintexu/ffva1156 part installed. Install xcku040 support."
    exit 1
  }
}

set proj manticore_kcu105
puts "=== build_kcu105: part=$part ap_clk=${ap_clk_mhz}MHz compute=${compute_mhz}MHz mem=${mem_kib}KiB stop=$stop_after ==="

file mkdir $build_dir
create_project -force $proj $build_dir -part $part

# ---------------------------------------------------------------------------
# 1. HDL sources (kernel + blackbox bodies + false-path constraint)
# ---------------------------------------------------------------------------
# take every emitted Verilog file: the kernel + ALL blackbox bodies (BRAMLike,
# AluDsp48, ClockDistribution, and — on enable_custom_alu builds — Wrapped32x16RAM)
add_files -norecurse [glob $hdl_dir/*.v]
add_files -fileset constrs_1 -norecurse $hdl_dir/false_path.xdc
set_property xpm_libraries {XPM_CDC XPM_MEMORY XPM_FIFO} [current_project]

# ---------------------------------------------------------------------------
# 2. Support IPs referenced by the kernel (clk_dist clk_wiz + AXI clock crossers)
#    Mirrors src/main/resources/hls/gen_ip.tcl, parameterized for this part.
# ---------------------------------------------------------------------------
set ap_clk_hz  [expr {round($ap_clk_mhz * 1000000)}]
set compute_hz [expr {round($compute_mhz * 1000000)}]
set cacheline_width 256

# clk_dist: the kernel's internal MMCM. Input = ap_clk, output = compute/control clock.
create_ip -name clk_wiz -vendor xilinx.com -library ip -version 6.0 -module_name clk_dist
set_property -dict [list \
  CONFIG.OPTIMIZE_CLOCKING_STRUCTURE_EN {false} \
  CONFIG.USE_PHASE_ALIGNMENT {false} \
  CONFIG.PRIM_SOURCE {No_buffer} \
  CONFIG.USE_RESET {false} \
  CONFIG.PRIM_IN_FREQ $ap_clk_mhz \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $compute_mhz \
  CONFIG.CLKOUT1_DRIVES {No_buffer} \
] [get_ips clk_dist]

# AXI clock crossers between the compute domain (s_axi side) and ap_clk (m_axi side).
create_ip -name axi_clock_converter -vendor xilinx.com -library ip -version 2.1 -module_name axi4lite_clock_converter
set_property -dict [list \
  CONFIG.PROTOCOL {AXI4LITE} CONFIG.ADDR_WIDTH {12} CONFIG.SYNCHRONIZATION_STAGES {3} \
  CONFIG.DATA_WIDTH {32} CONFIG.ID_WIDTH {0} CONFIG.AWUSER_WIDTH {0} CONFIG.ARUSER_WIDTH {0} \
  CONFIG.RUSER_WIDTH {0} CONFIG.WUSER_WIDTH {0} CONFIG.BUSER_WIDTH {0} CONFIG.ACLK_ASYNC {1} \
  CONFIG.SI_CLK.FREQ_HZ $compute_hz CONFIG.MI_CLK.FREQ_HZ $ap_clk_hz \
] [get_ips axi4lite_clock_converter]

create_ip -name axi_clock_converter -vendor xilinx.com -library ip -version 2.1 -module_name axi4_clock_converter
set_property -dict [list \
  CONFIG.PROTOCOL {AXI4} CONFIG.ADDR_WIDTH {64} CONFIG.SYNCHRONIZATION_STAGES {3} \
  CONFIG.DATA_WIDTH $cacheline_width CONFIG.ID_WIDTH {0} CONFIG.AWUSER_WIDTH {0} \
  CONFIG.ARUSER_WIDTH {0} CONFIG.RUSER_WIDTH {0} CONFIG.WUSER_WIDTH {0} CONFIG.BUSER_WIDTH {0} \
  CONFIG.ACLK_ASYNC {1} \
  CONFIG.SI_CLK.FREQ_HZ $compute_hz CONFIG.MI_CLK.FREQ_HZ $ap_clk_hz \
] [get_ips axi4_clock_converter]

generate_target all [get_ips]

# ---------------------------------------------------------------------------
# 3. Block design: clock/reset + JTAG-to-AXI + SmartConnect + BRAM + kernel
# ---------------------------------------------------------------------------
set bd system
create_bd_design $bd

# --- clocking: 300 MHz differential board clock -> ap_clk (+ locked) ---
set clkw [create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0]
set_property -dict [list \
  CONFIG.PRIM_SOURCE {Differential_clock_capable_pin} \
  CONFIG.PRIM_IN_FREQ {300.000} \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $ap_clk_mhz \
  CONFIG.USE_LOCKED {true} \
  CONFIG.USE_RESET {false} \
] $clkw

# --- processor system reset. proc_sys_reset's ext_reset_in is active-low and its
#     polarity param is read-only here, so invert the active-high CPU_RESET button. ---
set psr [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0]
set rstinv [create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 reset_inv]
set_property -dict [list CONFIG.C_SIZE {1} CONFIG.C_OPERATION {not}] $rstinv

# --- JTAG-to-AXI master (the host replacement) ---
set jtag [create_bd_cell -type ip -vlnv xilinx.com:ip:jtag_axi:1.2 jtag_axi_0]
set_property -dict [list CONFIG.PROTOCOL {2} CONFIG.M_AXI_DATA_WIDTH {32} CONFIG.M_AXI_ADDR_WIDTH {32}] $jtag

# --- SmartConnect: 2 masters (kernel m_axi, jtag) x 2 slaves (BRAM, s_axi_control) ---
set sc [create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 smartconnect_0]
set_property -dict [list CONFIG.NUM_SI {2} CONFIG.NUM_MI {2} CONFIG.NUM_CLKS {1}] $sc

# --- AXI BRAM controller (the device "DRAM"); block automation creates + sizes the
#     block memory so its widths/depth match the controller correctly. ---
set bramc [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_bram_ctrl:4.1 axi_bram_ctrl_0]
set_property -dict [list CONFIG.DATA_WIDTH {256} CONFIG.SINGLE_PORT_BRAM {1} CONFIG.ECC_TYPE {0}] $bramc
apply_bd_automation -rule xilinx.com:bd_rule:bram_cntlr -config {BRAM "Auto"} \
  [get_bd_intf_pins axi_bram_ctrl_0/BRAM_PORTA]

# --- the Manticore kernel (RTL module reference) ---
set krnl [create_bd_cell -type module -reference ManticoreFlatKernel kernel_0]
# Associate ap_clk with the kernel's AXI interfaces (single clock domain in this BD).
catch { set_property CONFIG.ASSOCIATED_BUSIF {m_axi_bank_0:s_axi_control} [get_bd_pins kernel_0/ap_clk] }

# --- connections: clocks & resets ---
connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] \
  [get_bd_pins proc_sys_reset_0/slowest_sync_clk] \
  [get_bd_pins kernel_0/ap_clk] \
  [get_bd_pins jtag_axi_0/aclk] \
  [get_bd_pins smartconnect_0/aclk] \
  [get_bd_pins axi_bram_ctrl_0/s_axi_aclk]
connect_bd_net [get_bd_pins clk_wiz_0/locked] [get_bd_pins proc_sys_reset_0/dcm_locked]
connect_bd_net [get_bd_pins reset_inv/Res] [get_bd_pins proc_sys_reset_0/ext_reset_in]
connect_bd_net [get_bd_pins proc_sys_reset_0/peripheral_aresetn] \
  [get_bd_pins kernel_0/ap_rst_n] \
  [get_bd_pins jtag_axi_0/aresetn] \
  [get_bd_pins smartconnect_0/aresetn] \
  [get_bd_pins axi_bram_ctrl_0/s_axi_aresetn]

# --- connections: AXI interfaces ---
connect_bd_intf_net [get_bd_intf_pins kernel_0/m_axi_bank_0] [get_bd_intf_pins smartconnect_0/S00_AXI]
connect_bd_intf_net [get_bd_intf_pins jtag_axi_0/M_AXI]      [get_bd_intf_pins smartconnect_0/S01_AXI]
connect_bd_intf_net [get_bd_intf_pins smartconnect_0/M00_AXI] [get_bd_intf_pins axi_bram_ctrl_0/S_AXI]
connect_bd_intf_net [get_bd_intf_pins smartconnect_0/M01_AXI] [get_bd_intf_pins kernel_0/s_axi_control]

# --- external ports: differential clock + reset button ---
set clk_p [create_bd_port -dir I -from 0 -to 0 sysclk_300_p]
set clk_n [create_bd_port -dir I -from 0 -to 0 sysclk_300_n]
connect_bd_net [get_bd_ports sysclk_300_p] [get_bd_pins clk_wiz_0/clk_in1_p]
connect_bd_net [get_bd_ports sysclk_300_n] [get_bd_pins clk_wiz_0/clk_in1_n]
set rstport [create_bd_port -dir I cpu_reset]
connect_bd_net [get_bd_ports cpu_reset] [get_bd_pins reset_inv/Op1]

# --- address map ---
set mem_bytes [expr {$mem_kib * 1024}]
assign_bd_address -offset 0x00000000 -range $mem_bytes \
  [get_bd_addr_segs {axi_bram_ctrl_0/S_AXI/Mem0}]
assign_bd_address -offset 0x00100000 -range 0x00001000 \
  [get_bd_addr_segs {kernel_0/s_axi_control/reg0}]
# kernel master must not reach s_axi_control; jtag must not be required to. Prune the
# stray kernel->s_axi_control segment if it was auto-created.
catch { exclude_bd_addr_seg -target_address_space [get_bd_addr_spaces kernel_0/m_axi_bank_0] [get_bd_addr_segs kernel_0/s_axi_control/reg0] }

regenerate_bd_layout
validate_bd_design
save_bd_design

# --- HDL wrapper, set as top ---
set wrapper [make_wrapper -files [get_files $bd.bd] -top -force]
add_files -norecurse $wrapper
set_property top system_wrapper [current_fileset]
update_compile_order -fileset sources_1

# ---------------------------------------------------------------------------
# 4. Top-level pin/timing constraints (KCU105, ffva1156 ballout)
# ---------------------------------------------------------------------------
set xdc [file join $build_dir pins.xdc]
set fh [open $xdc w]
puts $fh {set_property -dict {PACKAGE_PIN AK17 IOSTANDARD LVDS} [get_ports sysclk_300_p]}
puts $fh {set_property -dict {PACKAGE_PIN AK16 IOSTANDARD LVDS} [get_ports sysclk_300_n]}
puts $fh {create_clock -name sysclk_300 -period 3.333 [get_ports sysclk_300_p]}
puts $fh {set_property -dict {PACKAGE_PIN AN8 IOSTANDARD LVCMOS18} [get_ports cpu_reset]}
puts $fh {set_property CFGBVS GND [current_design]}
puts $fh {set_property CONFIG_VOLTAGE 1.8 [current_design]}
close $fh
add_files -fileset constrs_1 -norecurse $xdc

if {$stop_after eq "bd"} { puts "=== stop_after=bd: BD validated, exiting ==="; exit 0 }

# ---------------------------------------------------------------------------
# 5. Synthesis
# ---------------------------------------------------------------------------
launch_runs synth_1 -jobs 8
wait_on_run synth_1
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
  puts "ERROR: synthesis failed"; exit 1
}
puts "=== synthesis complete ==="
if {$stop_after eq "synth"} { exit 0 }

# ---------------------------------------------------------------------------
# 6. Implementation + bitstream
# ---------------------------------------------------------------------------
# Post-route phys_opt with hold-fixing: the BRAM-cascade paths inside the per-core
# scratchpads close setup with margin but can come out of route_design with ps-level
# hold violations (e.g. -46ps on CASDINB pins). AggressiveExplore includes hold fixing.
set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true [get_runs impl_1]
set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE AggressiveExplore [get_runs impl_1]
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
  puts "ERROR: implementation/bitstream failed"; exit 1
}
set bit [glob -nocomplain $build_dir/$proj.runs/impl_1/*.bit]
puts "=== BITSTREAM: $bit ==="
exit 0
