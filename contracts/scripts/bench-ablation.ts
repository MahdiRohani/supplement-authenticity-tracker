/**
 * Ablation for the paper's RQ1: batching with hash-reveal consumption versus
 * batching with signature-based consumption (the proposed scheme).
 *
 * Both variants run in ConsumeAblationBench, which shares the Merkle check,
 * bitmap write, counter and event of SupplementRegistryV2.consume, so the
 * difference between them is the cost of the signature alone. Writes
 * bench/results/gas-ablation.csv.
 */
import * as fs from "fs";
import * as path from "path";
import { StandardMerkleTree } from "@openzeppelin/merkle-tree";
import { ethers } from "hardhat";
import { ConsumeAblationBench } from "../typechain-types";
import { buildBatchTree, consumeDomain, deterministicUnitKeys, proofFor, signConsume } from "./lib/v2";

const SIZES = (process.env.BENCH_SIZES ?? "1000,100000").split(",").map((v) => Number(v.trim()));
const OUT_DIR = path.join(__dirname, "../bench/results");

async function gasOf(pending: Promise<{ wait(): Promise<{ gasUsed: bigint } | null> }>) {
  return (await (await pending).wait())!.gasUsed;
}

async function main() {
  const [, consumer, relayer] = await ethers.getSigners();
  const bench = (await ethers.deployContract("ConsumeAblationBench")) as unknown as ConsumeAblationBench;
  const { chainId } = await ethers.provider.getNetwork();
  const domain = consumeDomain(chainId, await bench.getAddress());
  const rows: Array<Array<string | number>> = [];

  // Warm-up so first writes to the batch counter are not attributed to a size.
  await bench.register(ethers.ZeroHash);

  for (const n of SIZES) {
    console.log(`n=${n}`);
    const keys = deterministicUnitKeys(n, `ablation-${n}`);
    const signedTree = buildBatchTree(keys);
    await bench.register(signedTree.root);
    const signedBatch = (await bench.register.staticCall(ethers.ZeroHash)) - 1n;

    const secrets = Array.from({ length: n }, (_, i) => ethers.id(`ablation-secret-${n}-${i}`));
    const revealTree = StandardMerkleTree.of(
      secrets.map((s, i) => [i, ethers.keccak256(s)] as [number, string]),
      ["uint32", "bytes32"]
    );
    await bench.register(revealTree.root);
    const revealBatch = signedBatch + 1n;

    for (const [label, index] of [["cold", 0], ["warm", 1]] as const) {
      const deadline = BigInt((await ethers.provider.getBlock("latest"))!.timestamp + 3600);
      const auth = { batchId: signedBatch, index, consumer: consumer.address, deadline };
      const signedProof = proofFor(signedTree, index);
      const signed = await gasOf(
        bench
          .connect(relayer)
          .consumeSigned(
            signedBatch,
            index,
            keys[index].address,
            consumer.address,
            deadline,
            signedProof,
            await signConsume(keys[index].wallet, domain, auth)
          )
      );
      const revealProof = revealTree.getProof(index);
      const reveal = await gasOf(
        bench.connect(relayer).consumeReveal(revealBatch, index, secrets[index], consumer.address, revealProof)
      );
      rows.push([n, label, signedProof.length, signed.toString(), reveal.toString(), (signed - reveal).toString()]);
      console.log(`  ${label}: signed=${signed} reveal=${reveal} diff=${signed - reveal}`);
    }
  }

  fs.mkdirSync(OUT_DIR, { recursive: true });
  const header = "n,bitmap_word,proof_length,consume_signed,consume_reveal,signature_overhead";
  fs.writeFileSync(
    path.join(OUT_DIR, "gas-ablation.csv"),
    `${header}\n${rows.map((r) => r.join(",")).join("\n")}\n`
  );
  console.log(`results in ${path.relative(process.cwd(), OUT_DIR)}/gas-ablation.csv`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
