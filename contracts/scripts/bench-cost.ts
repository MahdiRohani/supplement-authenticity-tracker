/**
 * Converts the measured gas of `bench-gas.ts` into fees on Ethereum L1 and two
 * rollups, using one dated snapshot of public fee and price data.
 *
 *   Ethereum L1   gasUsed x (baseFee + tip): median and p90 over ~24h of blocks
 *   Base (OP)     gasUsed x L2 gas price + L1 data fee from GasPriceOracle.getL1Fee(tx)
 *   Arbitrum One  (gasUsed + L1 component) x baseFee via NodeInterface.gasEstimateL1Component
 *
 * Rollup data fees depend on the transaction bytes, so every operation is
 * encoded with the same argument shapes the gas benchmark used (random 32-byte
 * values where the benchmark used hashes, keys and signatures, which do not
 * compress). Execution gas on the rollups is assumed equal to the measured L1
 * gas (same EVM pricing).
 *
 * The snapshot is written to bench/results/cost-prices.json. COST_OFFLINE=1
 * reuses it instead of querying the networks.
 */
import * as fs from "fs";
import * as path from "path";
import { ethers } from "hardhat";

const OUT_DIR = path.join(__dirname, "../bench/results");
const PRICES = path.join(OUT_DIR, "cost-prices.json");
const S0_CHUNK = 50;
const BITMAP_WORD = 256;
const CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi";

const RPC = {
  ethereum: process.env.COST_ETH_RPC ?? "https://ethereum-rpc.publicnode.com",
  base: process.env.COST_BASE_RPC ?? "https://mainnet.base.org",
  arbitrum: process.env.COST_ARB_RPC ?? "https://arb1.arbitrum.io/rpc",
};
const CHAIN_IDS = { ethereum: 1, base: 8453, arbitrum: 42161 };
const GAS_PRICE_ORACLE = "0x420000000000000000000000000000000000000F";
const NODE_INTERFACE = "0x00000000000000000000000000000000000000C8";
const PLACEHOLDER_TO = "0x5FbDB2315678afecb367f032d93F642f64180aa3";

const oracle = new ethers.Interface(["function getL1Fee(bytes) view returns (uint256)"]);
const nodeInterface = new ethers.Interface([
  "function gasEstimateL1Component(address to, bool contractCreation, bytes data) payable returns (uint64 gasEstimateForL1, uint256 baseFee, uint256 l1BaseFeeEstimate)",
]);

// ---------------------------------------------------------------- snapshot

interface Snapshot {
  takenAt: string;
  ethUsd: { median: number; sources: Record<string, number> };
  ethereum: {
    block: number;
    blocksSampled: number;
    medianGwei: number;
    p90Gwei: number;
    medianBaseFeeGwei: number;
    medianTipGwei: number;
  };
  base: { block: number; blocksSampled: number; l2GasPriceGwei: number };
  arbitrum: { block: number; baseFeeGwei: number };
  dataFeesWei: Record<string, { base: string; arbitrumL1Gas: string }>;
}

async function rpc(url: string, method: string, params: unknown[]): Promise<any> {
  for (let attempt = 1; ; attempt++) {
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
        signal: AbortSignal.timeout(30_000),
      });
      const body = (await response.json()) as { result?: unknown; error?: { message: string } };
      if (body.error) throw new Error(`${method}: ${body.error.message}`);
      return body.result;
    } catch (error) {
      if (attempt >= 4) throw error;
      await new Promise((resolve) => setTimeout(resolve, 1500 * attempt));
    }
  }
}

const gwei = (wei: bigint) => Number(wei) / 1e9;
const quantile = (values: number[], q: number) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.round(q * (sorted.length - 1))))];
};

/** Base fee + p50 tip per block over `blocks` recent blocks (1024 per call). */
async function feeSamples(url: string, blocks: number) {
  const head = Number(await rpc(url, "eth_blockNumber", []));
  const baseFees: number[] = [];
  const tips: number[] = [];
  for (let newest = head; newest > head - blocks; newest -= 1024) {
    const history = await rpc(url, "eth_feeHistory", ["0x400", ethers.toQuantity(newest), [50]]);
    const fees: string[] = history.baseFeePerGas.slice(0, -1);
    fees.forEach((fee, i) => {
      baseFees.push(gwei(BigInt(fee)));
      tips.push(gwei(BigInt(history.reward?.[i]?.[0] ?? "0x0")));
    });
  }
  return { head, baseFees, tips };
}

async function ethUsd() {
  const sources: Record<string, number> = {};
  const attempts: Array<[string, () => Promise<number>]> = [
    ["coinbase", async () => Number((await (await fetch("https://api.coinbase.com/v2/prices/ETH-USD/spot")).json()).data.amount)],
    ["coingecko", async () => Number((await (await fetch("https://api.coingecko.com/api/v3/simple/price?ids=ethereum&vs_currencies=usd")).json()).ethereum.usd)],
    ["kraken", async () => Number((await (await fetch("https://api.kraken.com/0/public/Ticker?pair=ETHUSD")).json()).result.XETHZUSD.c[0])],
  ];
  for (const [name, get] of attempts) {
    try {
      const value = await get();
      if (Number.isFinite(value) && value > 0) sources[name] = value;
    } catch {
      // one source down is fine; the median uses the rest
    }
  }
  if (Object.keys(sources).length === 0) throw new Error("no ETH/USD source reachable");
  return { median: quantile(Object.values(sources), 0.5), sources };
}

async function takeSnapshot(transactions: Map<string, Tx>): Promise<Snapshot> {
  console.log("querying Ethereum fee history (~24h)...");
  const eth = await feeSamples(RPC.ethereum, 7168);
  const totals = eth.baseFees.map((fee, i) => fee + eth.tips[i]);
  console.log("querying Base and Arbitrum...");
  const base = await feeSamples(RPC.base, 1024);
  const baseTotals = base.baseFees.map((fee, i) => fee + base.tips[i]);
  const arbHead = Number(await rpc(RPC.arbitrum, "eth_blockNumber", []));
  const arbBlock = await rpc(RPC.arbitrum, "eth_getBlockByNumber", ["latest", false]);

  const dataFeesWei: Snapshot["dataFeesWei"] = {};
  for (const [key, tx] of transactions) {
    const unsigned = ethers.Transaction.from({
      type: 2,
      chainId: CHAIN_IDS.base,
      nonce: 1000,
      gasLimit: tx.gas,
      maxFeePerGas: 1_000_000_000n,
      maxPriorityFeePerGas: 1_000_000n,
      to: PLACEHOLDER_TO,
      data: tx.data,
    }).unsignedSerialized;
    const baseFee = await rpc(RPC.base, "eth_call", [
      { to: GAS_PRICE_ORACLE, data: oracle.encodeFunctionData("getL1Fee", [unsigned]) },
      "latest",
    ]);
    const arb = nodeInterface.decodeFunctionResult(
      "gasEstimateL1Component",
      await rpc(RPC.arbitrum, "eth_call", [
        {
          to: NODE_INTERFACE,
          data: nodeInterface.encodeFunctionData("gasEstimateL1Component", [PLACEHOLDER_TO, false, tx.data]),
        },
        "latest",
      ])
    );
    dataFeesWei[key] = { base: BigInt(baseFee).toString(), arbitrumL1Gas: arb[0].toString() };
  }

  return {
    takenAt: new Date().toISOString(),
    ethUsd: await ethUsd(),
    ethereum: {
      block: eth.head,
      blocksSampled: totals.length,
      medianGwei: quantile(totals, 0.5),
      p90Gwei: quantile(totals, 0.9),
      medianBaseFeeGwei: quantile(eth.baseFees, 0.5),
      medianTipGwei: quantile(eth.tips, 0.5),
    },
    base: { block: base.head, blocksSampled: baseTotals.length, l2GasPriceGwei: quantile(baseTotals, 0.5) },
    arbitrum: { block: arbHead, baseFeeGwei: gwei(BigInt(arbBlock.baseFeePerGas)) },
    dataFeesWei,
  };
}

// ---------------------------------------------------------------- transactions

interface Tx {
  gas: bigint;
  data: string;
}

const rand32 = () => ethers.hexlify(ethers.randomBytes(32));
const randAddress = () => ethers.getAddress(ethers.hexlify(ethers.randomBytes(20)));

function readCsv(name: string) {
  const [header, ...lines] = fs.readFileSync(path.join(OUT_DIR, name), "utf8").trim().split("\n");
  const keys = header.split(",");
  return lines.map((line) => Object.fromEntries(line.split(",").map((value, i) => [keys[i], value])));
}

async function buildTransactions() {
  const v1 = (await ethers.getContractFactory("SupplementRegistry")).interface;
  const v2 = (await ethers.getContractFactory("SupplementRegistryV2")).interface;
  const registration = readCsv("gas-registration.csv");
  const operations = readCsv("gas-operations.csv");
  const op = (scheme: string, n: number, operation: string) => {
    const row = operations.find((r) => r.scheme === scheme && Number(r.n) === n && r.operation === operation);
    if (!row) throw new Error(`missing ${scheme} n=${n} ${operation} in gas-operations.csv`);
    return { gas: BigInt(row.gas), proofLength: Number(row.proof_length) };
  };
  const sizes = [...new Set(registration.map((r) => Number(r.n)))].sort((a, b) => a - b);
  const signature = ethers.hexlify(ethers.randomBytes(65));
  const consumeData = (proofLength: number) =>
    v2.encodeFunctionData("consume", [
      {
        batchId: 7,
        segmentId: 9,
        index: 3,
        unitKey: randAddress(),
        consumer: randAddress(),
        deadline: 1_790_000_000,
      },
      Array.from({ length: proofLength }, rand32),
      signature,
    ]);

  const txs = new Map<string, Tx>();
  // S0: one registerBatch per chunk of up to 50 units; per-unit transfers and consume.
  for (const n of sizes) {
    const row = registration.find((r) => r.scheme === "S0" && Number(r.n) === n)!;
    const chunk = Math.min(n, S0_CHUNK);
    const perTx = BigInt(Math.round(Number(row.total_gas) / Number(row.tx_count)));
    txs.set(`S0:register:${chunk}`, {
      gas: perTx,
      data: v1.encodeFunctionData("registerBatch", [
        Array.from({ length: chunk }, rand32),
        CID,
        rand32(),
        Array.from({ length: chunk }, rand32),
      ]),
    });
  }
  txs.set("S0:hop1", { gas: op("S0", 1, "transfer_hop1").gas, data: v1.encodeFunctionData("transferOwnership", [12345, randAddress()]) });
  txs.set("S0:hop2", { gas: op("S0", 1, "transfer_hop2").gas, data: v1.encodeFunctionData("transferOwnership", [12345, randAddress()]) });
  txs.set("S0:consume", { gas: op("S0", 1, "consume").gas, data: v1.encodeFunctionData("consume", [12345, rand32()]) });

  // S1/S2 share the v2 calls; registration calldata does not depend on n.
  const registerData = v2.encodeFunctionData("registerBatch", [rand32(), 1000, CID, rand32(), rand32()]);
  const s1Reg = registration.find((r) => r.scheme === "S1" && Number(r.n) === 1)!;
  txs.set("S1:register", { gas: BigInt(Math.round(Number(s1Reg.gas_per_unit))), data: registerData });
  txs.set("S1:hop1", { gas: op("S1", 1, "transfer_hop1").gas, data: v2.encodeFunctionData("transferSegment", [9, randAddress(), 1]) });
  txs.set("S1:hop2", { gas: op("S1", 1, "transfer_hop2").gas, data: v2.encodeFunctionData("transferSegment", [9, randAddress(), 1]) });
  txs.set("S1:consume", { gas: op("S1", 1, "consume").gas, data: consumeData(0) });
  for (const n of sizes) {
    const reg = registration.find((r) => r.scheme === "S2" && Number(r.n) === n)!;
    txs.set(`S2:${n}:register`, { gas: BigInt(reg.total_gas), data: registerData });
    txs.set(`S2:${n}:hop1`, { gas: op("S2", n, "transfer_hop1").gas, data: v2.encodeFunctionData("transferSegment", [9, randAddress(), n]) });
    txs.set(`S2:${n}:hop2`, { gas: op("S2", n, "transfer_hop2").gas, data: v2.encodeFunctionData("transferSegment", [9, randAddress(), n]) });
    if (n >= 2) {
      txs.set(`S2:${n}:split`, { gas: op("S2", n, "transfer_split").gas, data: v2.encodeFunctionData("transferSegment", [9, randAddress(), Math.floor(n / 2)]) });
    }
    const cold = op("S2", n, "consume_cold");
    txs.set(`S2:${n}:consume_cold`, { gas: cold.gas, data: consumeData(cold.proofLength) });
    const warm = n >= 2 ? op("S2", n, "consume_warm") : cold;
    txs.set(`S2:${n}:consume_warm`, { gas: warm.gas, data: consumeData(warm.proofLength) });
  }
  return { txs, sizes, registration };
}

// ---------------------------------------------------------------- fees

type Network = "ethereum-median" | "ethereum-p90" | "base" | "arbitrum";
const NETWORKS: Network[] = ["ethereum-median", "ethereum-p90", "base", "arbitrum"];

function feeEth(network: Network, key: string, tx: Tx, s: Snapshot) {
  const gas = Number(tx.gas);
  switch (network) {
    case "ethereum-median":
      return (gas * s.ethereum.medianGwei) / 1e9;
    case "ethereum-p90":
      return (gas * s.ethereum.p90Gwei) / 1e9;
    case "base":
      return (gas * s.base.l2GasPriceGwei) / 1e9 + Number(BigInt(s.dataFeesWei[key].base)) / 1e18;
    case "arbitrum":
      return ((gas + Number(s.dataFeesWei[key].arbitrumL1Gas)) * s.arbitrum.baseFeeGwei) / 1e9;
  }
}

function writeCsv(name: string, header: string[], rows: Array<Array<string | number>>) {
  fs.writeFileSync(path.join(OUT_DIR, name), `${header.join(",")}\n${rows.map((r) => r.join(",")).join("\n")}\n`);
}

const usd = (value: number) => (value >= 0.01 ? value.toFixed(4) : value.toPrecision(3));

async function main() {
  const { txs, sizes, registration } = await buildTransactions();
  let snapshot: Snapshot;
  if (process.env.COST_OFFLINE === "1") {
    snapshot = JSON.parse(fs.readFileSync(PRICES, "utf8"));
    const missing = [...txs.keys()].filter((key) => !snapshot.dataFeesWei[key]);
    if (missing.length) throw new Error(`snapshot lacks data fees for ${missing.join(", ")}; rerun without COST_OFFLINE`);
  } else {
    snapshot = await takeSnapshot(txs);
    fs.writeFileSync(PRICES, JSON.stringify(snapshot, null, 2) + "\n");
  }
  const ethUsdPrice = snapshot.ethUsd.median;
  const cost = (network: Network, key: string) => feeEth(network, key, txs.get(key)!, snapshot) * ethUsdPrice;

  const operationRows: Array<Array<string | number>> = [];
  for (const [key, tx] of txs) {
    const [scheme, ...rest] = key.split(":");
    const n = scheme === "S2" ? Number(rest[0]) : scheme === "S0" && rest[0] === "register" ? Number(rest[1]) : 1;
    const operation = scheme === "S2" ? rest[1] : rest[0];
    for (const network of NETWORKS) {
      operationRows.push([network, scheme, n, operation, tx.gas.toString(), ethers.dataLength(tx.data), usd(cost(network, key))]);
    }
  }

  const lifecycleRows: Array<Array<string | number>> = [];
  for (const n of sizes) {
    const s0Txs = Number(registration.find((r) => r.scheme === "S0" && Number(r.n) === n)!.tx_count);
    const coldCount = Math.floor(n / BITMAP_WORD) + (n % BITMAP_WORD > 0 ? 1 : 0);
    for (const network of NETWORKS) {
      const c = (key: string) => cost(network, key);
      const s0 = (s0Txs * c(`S0:register:${Math.min(n, S0_CHUNK)}`)) / n + c("S0:hop1") + c("S0:hop2") + c("S0:consume");
      const s1 = c("S1:register") + c("S1:hop1") + c("S1:hop2") + c("S1:consume");
      const s2 =
        (c(`S2:${n}:register`) + c(`S2:${n}:hop1`) + c(`S2:${n}:hop2`)) / n +
        (coldCount * c(`S2:${n}:consume_cold`) + (n - coldCount) * c(`S2:${n}:consume_warm`)) / n;
      for (const [scheme, perUnit] of [["S0", s0], ["S1", s1], ["S2", s2]] as const) {
        lifecycleRows.push([network, scheme, n, usd(perUnit), usd(perUnit * n)]);
      }
    }
  }

  writeCsv(
    "cost-operations.csv",
    ["network", "scheme", "n", "operation", "gas", "calldata_bytes", "usd"],
    operationRows
  );
  writeCsv("cost-lifecycle.csv", ["network", "scheme", "n", "usd_per_unit", "usd_total"], lifecycleRows);

  const e = snapshot.ethereum;
  console.log(
    `snapshot ${snapshot.takenAt}: ETH $${ethUsdPrice.toFixed(2)}; L1 median ${e.medianGwei.toFixed(3)} gwei, p90 ${e.p90Gwei.toFixed(3)} gwei ` +
      `(${e.blocksSampled} blocks to #${e.block}); Base L2 ${snapshot.base.l2GasPriceGwei.toFixed(4)} gwei; Arbitrum ${snapshot.arbitrum.baseFeeGwei.toFixed(4)} gwei`
  );
  for (const network of NETWORKS) {
    const row = (scheme: string) => lifecycleRows.find((r) => r[0] === network && r[1] === scheme && r[2] === 1000)!;
    console.log(`${network.padEnd(16)} lifecycle per unit (n=1000): S0 $${row("S0")[3]}  S1 $${row("S1")[3]}  S2 $${row("S2")[3]}`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
