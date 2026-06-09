# =============================================================================
# run_kcu105.tcl — drive a Manticore KCU105 bitstream over JTAG-to-AXI (no XRT).
#
# Replaces the XRT host: programs the device, loads the program image into the
# on-chip BRAM, writes the control registers, starts the device, polls for
# completion, and reads results back.
#
# Usage (hardware required):
#   vivado -mode batch -source run_kcu105.tcl -tclargs <bitfile> <image_hex> \
#          [instr_base_words] [result_byte_addr]
#
#   bitfile          : the .bit to program
#   image_hex        : text file, one 32-bit hex word per line (little-endian
#                      packing of the 16-bit memory image; see gen_image.py)
#   instr_base_words : 16-bit-word offset of the boot stream within the image
#                      (-> GlobalMemoryInstructionBase). Default 0.
#   result_byte_addr : byte address in device memory to read back. Default 0.
#
# NOTE: the BD maps s_axi_control at 0x0010_0000 and the BRAM at 0x0 (= the
# value written to DramBank0Base). Register offsets are from AxiSlave.scala.
# =============================================================================

set bitfile          [lindex $argv 0]
set image_hex        [lindex $argv 1]
set instr_base_words [expr {$argc > 2 ? [lindex $argv 2] : 0}]
set result_byte_addr [expr {$argc > 3 ? [lindex $argv 3] : 0}]

set MEM_BASE  0x00000000
set CTRL_BASE 0x00100000

# control register offsets (AxiSlave.addressOf, user regs from 0x10) ----------
set REG_CONTROL     0x00
set REG_EXCEPTION   0x10
set REG_DEVICEINFO  0x24
set REG_CYCLECOUNT  0x2c
set REG_VCYCLECOUNT 0x38
set REG_SCHEDULE    0x6c
set REG_INSTRBASE   0x84
set REG_DRAMBASE    0x90

# --- connect + program ------------------------------------------------------
open_hw_manager
connect_hw_server
open_hw_target
set dev [lindex [get_hw_devices] 0]
current_hw_device $dev
refresh_hw_device -update_hw_probes false $dev
set_property PROGRAM.FILE $bitfile $dev
program_hw_devices $dev
refresh_hw_device $dev
set axi [lindex [get_hw_axis] 0]

# --- AXI helpers (single-beat 32-bit) ---------------------------------------
proc fmt32 {v} { return [format %08x [expr {$v & 0xffffffff}]] }

proc axi_w32 {addr val} {
  global axi
  catch { delete_hw_axi_txn [get_hw_axi_txns t_wr] }
  create_hw_axi_txn t_wr $axi -address [fmt32 $addr] -data [fmt32 $val] -type write
  run_hw_axi [get_hw_axi_txns t_wr]
}
proc axi_r32 {addr} {
  global axi
  catch { delete_hw_axi_txn [get_hw_axi_txns t_rd] }
  create_hw_axi_txn t_rd $axi -address [fmt32 $addr] -type read
  run_hw_axi [get_hw_axi_txns t_rd]
  return [expr 0x[get_property DATA [get_hw_axi_txns t_rd]]]
}
proc reg_w {off val}  { global CTRL_BASE; axi_w32 [expr {$CTRL_BASE + $off}] $val }
proc reg_r {off}      { global CTRL_BASE; return [axi_r32 [expr {$CTRL_BASE + $off}]] }
proc reg_w64 {off val} { reg_w $off [expr {$val & 0xffffffff}]; reg_w [expr {$off+4}] [expr {($val >> 32) & 0xffffffff}] }

# --- sanity: read grid geometry --------------------------------------------
set info [reg_r $REG_DEVICEINFO]
puts [format "DeviceInfo = 0x%08x  (DimX=%d DimY=%d)" $info [expr {($info>>26)&0x3f}] [expr {($info>>20)&0x3f}]]

# --- load image into BRAM ---------------------------------------------------
puts "Loading image $image_hex into BRAM..."
set fh [open $image_hex r]
set i 0
foreach line [split [read $fh] "\n"] {
  set line [string trim $line]
  if {$line eq ""} continue
  axi_w32 [expr {$MEM_BASE + $i*4}] [expr 0x$line]
  incr i
}
close $fh
puts "Wrote $i 32-bit words."

# --- program the control registers and start --------------------------------
reg_w   $REG_CONTROL    0x0
reg_w64 $REG_DRAMBASE   0x0
reg_w64 $REG_INSTRBASE  $instr_base_words
# ScheduleConfig: start command, bit63 = timeout-enable, low bits = timeout cycles
reg_w64 $REG_SCHEDULE   [expr {(1 << 63) | 1000000}]
reg_w   $REG_CONTROL    0x1   ;# ap_start

# --- poll ap_idle (Control bit2) --------------------------------------------
set done 0
for {set p 0} {$p < 2000} {incr p} {
  set ctrl [reg_r $REG_CONTROL]
  if {(($ctrl >> 2) & 1) == 1} { set done 1; break }
  after 5
}
puts [format "ap_done=%d ap_idle=%d after %d polls" [expr {$done}] [expr {($ctrl>>2)&1}] $p]

# --- read results -----------------------------------------------------------
puts [format "ExceptionId       = 0x%08x" [reg_r $REG_EXCEPTION]]
puts [format "CycleCount        = %d"     [expr {[reg_r $REG_CYCLECOUNT] | ([reg_r [expr {$REG_CYCLECOUNT+4}]] << 32)}]]
puts [format "VirtualCycleCount = %d"     [expr {[reg_r $REG_VCYCLECOUNT] | ([reg_r [expr {$REG_VCYCLECOUNT+4}]] << 32)}]]
puts [format "mem\[0x%x\]         = 0x%08x" $result_byte_addr [axi_r32 [expr {$MEM_BASE + $result_byte_addr}]]]
puts "Done."
