// True-dual-port, dual-clock, byte-write-enable block RAM (read-first), the
// global memory of a Manticore chip (see GmemBramBackend). Port A is the
// host-facing 32-bit port (driven by an AXI BRAM controller on KCU105 or a
// host bus adapter elsewhere); port B serves the core/bootloader through the
// fixed-latency gmem backend. Standard Vivado byte-WE TDP inference template;
// 1-cycle read latency on both ports, dout holds while en is low.
module TrueDualPortBram #(
    parameter ADDR_WIDTH = 16, // word (32-bit) address bits
    parameter DATA_WIDTH = 32,
    parameter NUM_BYTES  = 4,
    // extra port-A output register stage (maps onto the BRAM primitive's
    // DOA_REG): read latency 2 on port A. Used by the host-facing port at
    // 200 MHz, where the raw BRAM clock-to-out + routing across a large
    // array misses timing.
    parameter OUT_REG_A  = 0
) (
    input  wire                    clka,
    input  wire                    ena,
    input  wire [NUM_BYTES-1:0]    wea,
    input  wire [ADDR_WIDTH-1:0]   addra,
    input  wire [DATA_WIDTH-1:0]   dina,
    output wire [DATA_WIDTH-1:0]   douta,

    input  wire                    clkb,
    input  wire                    enb,
    input  wire [NUM_BYTES-1:0]    web,
    input  wire [ADDR_WIDTH-1:0]   addrb,
    input  wire [DATA_WIDTH-1:0]   dinb,
    output reg  [DATA_WIDTH-1:0]   doutb
);

  (* ram_style = "block" *) reg [DATA_WIDTH-1:0] mem[(1 << ADDR_WIDTH)-1:0];

  reg [DATA_WIDTH-1:0] douta_int;
  integer ia;
  always @(posedge clka) begin
    if (ena) begin
      douta_int <= mem[addra];
      for (ia = 0; ia < NUM_BYTES; ia = ia + 1)
        if (wea[ia]) mem[addra][ia*8+:8] <= dina[ia*8+:8];
    end
  end

  generate
    if (OUT_REG_A) begin : g_outreg_a
      reg [DATA_WIDTH-1:0] douta_r;
      always @(posedge clka) douta_r <= douta_int;
      assign douta = douta_r;
    end else begin : g_noreg_a
      assign douta = douta_int;
    end
  endgenerate

  integer ib;
  always @(posedge clkb) begin
    if (enb) begin
      doutb <= mem[addrb];
      for (ib = 0; ib < NUM_BYTES; ib = ib + 1)
        if (web[ib]) mem[addrb][ib*8+:8] <= dinb[ib*8+:8];
    end
  end

endmodule
