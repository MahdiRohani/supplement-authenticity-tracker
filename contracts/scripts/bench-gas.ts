/**
 * Gas benchmark for the evaluation chapter / paper.
 *
 *   S0  SupplementRegistry (v1): one storage record and secret hash per unit,
 *       per-unit transfers, plaintext-secret consume.
 *   S1  SupplementRegistryV2 with single-unit batches: per-unit key and
 *       signature-based consume, but no batching.
 *   S2  SupplementRegistryV2 with Merkle batches of n units.
 *
 * Every number is the `gasUsed` of a mined transaction on the in-process
 * Hardhat network after a warm-up, so first-write costs of global counters
 * are excluded. Rows whose transaction count would be impractical to replay
 * are extrapolated linearly from the largest measured size and flagged.
 */
import * as fs from "fs";
import * as path from "path";
import hre, { ethers } from "hardhat";
import { HardhatEthersSigner } from "@nomicfoundation/hardhat-ethers/signers";
import { SupplementRegistry, SupplementRegistryV2 } from "../typechain-types";
import {
  buildBatchTree,
  consumeDomain,
  deterministicUnitKeys,
  proofFor,
  signConsume,
  UnitKey,
} from "./lib/v2";

const SIZES = (process.env.BENCH_SIZES ?? "1,10,100,1000,10000,100000")
  .split(",")
  .map((value) => Number(value.trim()));
// Keeps each v1 registerBatch under the 2^24 per-transaction gas cap (EIP-7825).
const S0_CHUNK = 50;
const S0_MEASURE_MAX = 10_000;
const S1_MEASURE_MAX = 1_000;
const SAMPLE = 20;
const BITMAP_WORD = 256;
const CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi";
const METADATA_HASH = ethers.id("bench-metadata");
const OUT_DIR = path.join(__dirname, "../bench/results");

type Method = "measured" | "extrapolated";

interface Parties {
  admin: HardhatEthersSigner;
  manufacturer: HardhatEthersSigner;
  distributor: HardhatEthersSigner;
  pharmacyA: HardhatEthersSigner;
  pharmacyB: HardhatEthersSigner;
  consumer: HardhatEthersSigner;
  relayer: HardhatEthersSigner;
}

interface PerUnitOps {
  hop1: number;
  hop2: number;
  consume: number;
}

let uniqueCounter = 0;
const uniqueId = () => ethers.id(`bench-${++uniqueCounter}`);
const mean = (values: bigint[]) =>
  Number(values.reduce((sum, value) => sum + value, 0n)) / values.length;

async function gasOf(pending: Promise<{ wait(): Promise<{ gasUsed: bigint } | null> }>) {
  const receipt = await (await pending).wait();
  return receipt!.gasUsed;
}

async function grantRoles(
  registry: SupplementRegistry | SupplementRegistryV2,
  p: Parties
) {
  await registry.grantRole(ethers.id("MANUFACTURER_ROLE"), p.manufacturer.address);
  await registry.grantRole(ethers.id("DISTRIBUTOR_ROLE"), p.distributor.address);
  await registry.grantRole(ethers.id("PHARMACY_ROLE"), p.pharmacyA.address);
  await registry.grantRole(ethers.id("PHARMACY_ROLE"), p.pharmacyB.address);
}

// ---------------------------------------------------------------- S0 (v1)

function randomSecrets(count: number) {
  return Array.from({ length: count }, () => {
    const secret = ethers.hexlify(ethers.randomBytes(32));
    return { secret, secretHash: ethers.keccak256(secret) };
  });
}

async function s0Register(v1: SupplementRegistry, p: Parties, n: number) {
  const secrets = randomSecrets(n);
  let total = 0n;
  let txs = 0;
  for (let offset = 0; offset < n; offset += S0_CHUNK) {
    const chunk = secrets.slice(offset, offset + S0_CHUNK);
    total += await gasOf(
      v1.connect(p.manufacturer).registerBatch(
        chunk.map((s) => s.secretHash),
        CID,
        METADATA_HASH,
        chunk.map(() => uniqueId())
      )
    );
    txs++;
  }
  return { total, txs, secrets };
}

async function s0PerUnitOps(v1: SupplementRegistry, p: Parties): Promise<PerUnitOps> {
  const before = await v1.nextProductId();
  const { secrets } = await s0Register(v1, p, SAMPLE);
  const ids = secrets.map((_, i) => before + BigInt(i + 1));
  const hop1: bigint[] = [];
  const hop2: bigint[] = [];
  const consume: bigint[] = [];
  for (const id of ids) {
    hop1.push(await gasOf(v1.connect(p.manufacturer).transferOwnership(id, p.distributor.address)));
  }
  for (const id of ids) {
    hop2.push(await gasOf(v1.connect(p.distributor).transferOwnership(id, p.pharmacyA.address)));
  }
  for (const [i, id] of ids.entries()) {
    consume.push(await gasOf(v1.connect(p.consumer).consume(id, secrets[i].secret)));
  }
  return { hop1: mean(hop1), hop2: mean(hop2), consume: mean(consume) };
}

// ---------------------------------------------------------------- S1 / S2 (v2)

async function v2Register(v2: SupplementRegistryV2, p: Parties, keys: UnitKey[]) {
  const tree = buildBatchTree(keys);
  const gas = await gasOf(
    v2.connect(p.manufacturer).registerBatch(tree.root, keys.length, CID, METADATA_HASH, uniqueId())
  );
  return { gas, tree, batchId: await v2.batchCount(), segmentId: await v2.segmentCount() };
}

async function v2Consume(
  v2: SupplementRegistryV2,
  p: Parties,
  domain: ReturnType<typeof consumeDomain>,
  batch: { tree: ReturnType<typeof buildBatchTree>; batchId: bigint },
  keys: UnitKey[],
  index: number,
  segmentId: bigint
) {
  const deadline = BigInt((await ethers.provider.getBlock("latest"))!.timestamp + 3600);
  const auth = { batchId: batch.batchId, index, consumer: p.consumer.address, deadline };
  const proof = proofFor(batch.tree, index);
  const gas = await gasOf(
    v2.connect(p.relayer).consume(
      { ...auth, segmentId, unitKey: keys[index].address },
      proof,
      await signConsume(keys[index].wallet, domain, auth)
    )
  );
  return { gas, proofLength: proof.length };
}

async function s1PerUnitOps(
  v2: SupplementRegistryV2,
  p: Parties,
  domain: ReturnType<typeof consumeDomain>
): Promise<PerUnitOps> {
  const hop1: bigint[] = [];
  const hop2: bigint[] = [];
  const consume: bigint[] = [];
  for (let i = 0; i < SAMPLE; i++) {
    const keys = deterministicUnitKeys(1, `s1-ops-${i}`);
    const batch = await v2Register(v2, p, keys);
    hop1.push(
      await gasOf(v2.connect(p.manufacturer).transferSegment(batch.segmentId, p.distributor.address, 1))
    );
    hop2.push(
      await gasOf(v2.connect(p.distributor).transferSegment(batch.segmentId, p.pharmacyA.address, 1))
    );
    consume.push((await v2Consume(v2, p, domain, batch, keys, 0, batch.segmentId)).gas);
  }
  return { hop1: mean(hop1), hop2: mean(hop2), consume: mean(consume) };
}

/** Mean consume gas when every unit of an n-unit lot is consumed. */
function s2LotConsumeMean(n: number, cold: number, warm: number) {
  const fullWords = Math.floor(n / BITMAP_WORD);
  const rest = n % BITMAP_WORD;
  const coldCount = fullWords + (rest > 0 ? 1 : 0);
  return (coldCount * cold + (n - coldCount) * warm) / n;
}

// ---------------------------------------------------------------- output

function writeCsv(name: string, header: string[], rows: Array<Array<string | number>>) {
  const body = rows.map((row) => row.join(",")).join("\n");
  fs.writeFileSync(path.join(OUT_DIR, name), `${header.join(",")}\n${body}\n`);
}

async function main() {
  const [admin, manufacturer, distributor, pharmacyA, pharmacyB, consumer, relayer] =
    await ethers.getSigners();
  const p: Parties = { admin, manufacturer, distributor, pharmacyA, pharmacyB, consumer, relayer };
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const v1 = (await ethers.deployContract("SupplementRegistry", [
    admin.address,
  ])) as unknown as SupplementRegistry;
  const v2 = (await ethers.deployContract("SupplementRegistryV2", [
    admin.address,
  ])) as unknown as SupplementRegistryV2;
  await grantRoles(v1, p);
  await grantRoles(v2, p);
  const { chainId } = await ethers.provider.getNetwork();
  const domain = consumeDomain(chainId, await v2.getAddress());

  console.log("warm-up and per-unit operation samples...");
  await s0Register(v1, p, 1);
  await s0PerUnitOps(v1, p);
  await s1PerUnitOps(v2, p, domain);
  const s0Ops = await s0PerUnitOps(v1, p);
  const s1Ops = await s1PerUnitOps(v2, p, domain);

  const registrationRows: Array<Array<string | number>> = [];
  const operationRows: Array<Array<string | number>> = [];
  const lifecycleRows: Array<Array<string | number>> = [];
  const offchainRows: Array<Array<string | number>> = [];

  for (const scheme of ["S0", "S1"] as const) {
    const ops = scheme === "S0" ? s0Ops : s1Ops;
    operationRows.push([scheme, 1, "transfer_hop1", ops.hop1.toFixed(0), 0]);
    operationRows.push([scheme, 1, "transfer_hop2", ops.hop2.toFixed(0), 0]);
    operationRows.push([scheme, 1, "consume", ops.consume.toFixed(0), 0]);
  }

  let s0PerUnitReg = 0;
  let s1PerUnitReg = 0;
  for (const n of SIZES) {
    console.log(`n=${n}`);

    // S0 registration
    let s0Total: number;
    let s0Txs = Math.ceil(n / S0_CHUNK);
    let s0Method: Method = "measured";
    if (n <= S0_MEASURE_MAX) {
      const result = await s0Register(v1, p, n);
      s0Total = Number(result.total);
      s0Txs = result.txs;
      s0PerUnitReg = s0Total / n;
    } else {
      s0Total = s0PerUnitReg * n;
      s0Method = "extrapolated";
    }
    registrationRows.push(["S0", n, s0Total.toFixed(0), (s0Total / n).toFixed(1), s0Txs, s0Method]);

    // S1 registration
    let s1Total: number;
    let s1Method: Method = "measured";
    if (n <= S1_MEASURE_MAX) {
      let total = 0n;
      for (let i = 0; i < n; i++) {
        total += (await v2Register(v2, p, deterministicUnitKeys(1, `s1-${n}-${i}`))).gas;
      }
      s1Total = Number(total);
      s1PerUnitReg = s1Total / n;
    } else {
      s1Total = s1PerUnitReg * n;
      s1Method = "extrapolated";
    }
    registrationRows.push(["S1", n, s1Total.toFixed(0), (s1Total / n).toFixed(1), n, s1Method]);

    // S2: key generation, tree, registration, custody and consumption
    let started = performance.now();
    const keys = deterministicUnitKeys(n, `s2-${n}`);
    const keygenMs = performance.now() - started;
    started = performance.now();
    buildBatchTree(keys);
    const treeMs = performance.now() - started;

    const batch = await v2Register(v2, p, keys);
    registrationRows.push(["S2", n, batch.gas.toString(), (Number(batch.gas) / n).toFixed(3), 1, "measured"]);

    const hop1 = await gasOf(
      v2.connect(manufacturer).transferSegment(batch.segmentId, distributor.address, n)
    );
    operationRows.push(["S2", n, "transfer_hop1", hop1.toString(), 0]);

    let hop2Whole: bigint;
    const half = Math.floor(n / 2);
    let pharmacySegmentFor: (index: number) => bigint;
    if (n >= 2) {
      const splitId = await v2
        .connect(distributor)
        .transferSegment.staticCall(batch.segmentId, pharmacyA.address, half);
      const split = await gasOf(
        v2.connect(distributor).transferSegment(batch.segmentId, pharmacyA.address, half)
      );
      hop2Whole = await gasOf(
        v2.connect(distributor).transferSegment(batch.segmentId, pharmacyB.address, n - half)
      );
      operationRows.push(["S2", n, "transfer_split", split.toString(), 0]);
      pharmacySegmentFor = (index) => (index < half ? splitId : batch.segmentId);
    } else {
      hop2Whole = await gasOf(
        v2.connect(distributor).transferSegment(batch.segmentId, pharmacyA.address, n)
      );
      pharmacySegmentFor = () => batch.segmentId;
    }
    operationRows.push(["S2", n, "transfer_hop2", hop2Whole.toString(), 0]);

    const cold = await v2Consume(v2, p, domain, batch, keys, 0, pharmacySegmentFor(0));
    operationRows.push(["S2", n, "consume_cold", cold.gas.toString(), cold.proofLength]);
    let warmGas = Number(cold.gas);
    if (n >= 2) {
      const warm = await v2Consume(v2, p, domain, batch, keys, 1, pharmacySegmentFor(1));
      warmGas = Number(warm.gas);
      operationRows.push(["S2", n, "consume_warm", warm.gas.toString(), warm.proofLength]);
    }
    offchainRows.push([
      n,
      keygenMs.toFixed(1),
      treeMs.toFixed(1),
      cold.proofLength,
      cold.proofLength * 32,
    ]);

    // Lifecycle: register, two custody hops for the whole lot, consume every unit.
    const s0Unit = s0Total / n + s0Ops.hop1 + s0Ops.hop2 + s0Ops.consume;
    const s1Unit = s1Total / n + s1Ops.hop1 + s1Ops.hop2 + s1Ops.consume;
    const s2Unit =
      (Number(batch.gas) + Number(hop1) + Number(hop2Whole)) / n +
      s2LotConsumeMean(n, Number(cold.gas), warmGas);
    lifecycleRows.push(["S0", n, s0Unit.toFixed(1), (s0Unit * n).toFixed(0), s0Txs + 3 * n]);
    lifecycleRows.push(["S1", n, s1Unit.toFixed(1), (s1Unit * n).toFixed(0), 4 * n]);
    lifecycleRows.push(["S2", n, s2Unit.toFixed(1), (s2Unit * n).toFixed(0), 3 + n]);
  }

  writeCsv(
    "gas-registration.csv",
    ["scheme", "n", "total_gas", "gas_per_unit", "tx_count", "method"],
    registrationRows
  );
  writeCsv("gas-operations.csv", ["scheme", "n", "operation", "gas", "proof_length"], operationRows);
  writeCsv(
    "gas-lifecycle.csv",
    ["scheme", "n", "gas_per_unit", "total_gas", "tx_count"],
    lifecycleRows
  );
  writeCsv("offchain.csv", ["n", "keygen_ms", "tree_ms", "proof_length", "proof_bytes"], offchainRows);

  const solidity = hre.config.solidity.compilers[0];
  const environment = {
    generatedAt: new Date().toISOString(),
    network: hre.network.name,
    blockGasLimit: hre.network.config.blockGasLimit ?? null,
    hardhat: require("hardhat/package.json").version,
    node: process.version,
    solc: solidity.version,
    optimizer: solidity.settings?.optimizer ?? null,
    evmVersion: solidity.settings?.evmVersion ?? "default",
    sizes: SIZES,
    s0Chunk: S0_CHUNK,
    sample: SAMPLE,
    metadataCidLength: CID.length,
    notes: [
      "Gas values are receipts' gasUsed after warm-up transactions.",
      `S0 is measured up to n=${S0_MEASURE_MAX} (chunks of ${S0_CHUNK}); S1 up to n=${S1_MEASURE_MAX}; larger sizes are extrapolated linearly.`,
      "S0/S1 per-unit transfer and consume costs are means over a sample of units.",
      "S2 consume_cold writes a fresh 256-unit bitmap word; consume_warm reuses one.",
      "Lifecycle = registration + two whole-lot custody hops + consumption of every unit.",
    ],
  };
  fs.writeFileSync(path.join(OUT_DIR, "environment.json"), JSON.stringify(environment, null, 2) + "\n");
  console.log(`results in ${path.relative(process.cwd(), OUT_DIR)}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
