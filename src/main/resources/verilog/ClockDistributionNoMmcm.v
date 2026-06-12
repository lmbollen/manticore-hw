// Clock distribution for the bittide integration: NO MMCM. The chip runs
// directly on the externally supplied (bittide, 125 MHz) clock:
//   - control_clock = root_clock, passed through un-rebuffered so the
//     surrounding (Clash) logic and this chip share one clock net;
//   - compute_clock = BUFGCE(root_clock, compute_clock_en) — the gated child
//     clock for the core array (vcycle-boundary stalls; see the multi-chip
//     stall design).
// Same interface as ClockDistribution.v.
module ClockDistributionNoMmcm (
                           input wire root_rst_n,
                           output wire sync_rst_n,
                           input wire root_clock,
                           output wire compute_clock,
                           output wire control_clock,
                           input wire compute_clock_en,
                           output wire locked);
`ifdef VERILATOR

// simulation purpose only
assign control_clock = root_clock;
reg output_clk;
always @(negedge root_clock) begin
  output_clk = 1'b0;
end
always @(posedge root_clock) begin
  if (compute_clock_en)
    output_clk = 1'b1;
  else
    output_clk = 1'b0;
end
assign compute_clock = output_clk;
assign locked        = 1'b1;

`else // VERILATOR

assign control_clock = root_clock;
assign locked        = 1'b1;

(* DONT_TOUCH = "yes" *)
BUFGCE #(
  .CE_TYPE("SYNC"),
  .IS_CE_INVERTED(1'b0),
  .IS_I_INVERTED(1'b0)
) comp_buf (
  .O(compute_clock),
  .I(root_clock),
  .CE(compute_clock_en)
);

`endif

  reg rst_sync1, rst_sync2, rst_sync3;
  wire reset_n_trigger;

  assign reset_n_trigger = root_rst_n & locked;

  always @ (posedge control_clock or negedge reset_n_trigger) begin
    if (!reset_n_trigger) begin
      rst_sync1 <= 1'b0;
      rst_sync2 <= 1'b0;
      rst_sync3 <= 1'b0;
    end else begin
      rst_sync1 <= 1'b1;
      rst_sync2 <= rst_sync1;
      rst_sync3 <= rst_sync2;
    end
  end

  assign sync_rst_n = rst_sync3;

endmodule
