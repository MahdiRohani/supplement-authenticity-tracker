import { ethers } from "hardhat";
import { SupplementRegistryV2 } from "../typechain-types";
import {
  buildBatchTree,
  consumeDomain,
  proofFor,
  randomUnitKeys,
  signConsume,
  UnitKey,
} from "./lib/v2";

const BATCH_SIZE = 10;
const TO_PHARMACY_A = 4;

enum Unit {
  Created,
  Transferred,
  AtPointOfSale,
  Consumed,
  Invalid,
}

async function main() {
  const [admin, manufacturer, distributor, pharmacyA, pharmacyB, consumer, relayer] =
    await ethers.getSigners();
  const registry = (await ethers.deployContract("SupplementRegistryV2", [
    admin.address,
  ])) as unknown as SupplementRegistryV2;
  await registry.waitForDeployment();

  await registry.grantRole(ethers.id("MANUFACTURER_ROLE"), manufacturer.address);
  await registry.grantRole(ethers.id("DISTRIBUTOR_ROLE"), distributor.address);
  await registry.grantRole(ethers.id("PHARMACY_ROLE"), pharmacyA.address);
  await registry.grantRole(ethers.id("PHARMACY_ROLE"), pharmacyB.address);

  const { chainId } = await ethers.provider.getNetwork();
  const domain = consumeDomain(chainId, await registry.getAddress());

  // Issuance: one keypair per unit, one Merkle root per batch.
  const keys = randomUnitKeys(BATCH_SIZE);
  const tree = buildBatchTree(keys);
  const registration = [
    tree.root,
    BATCH_SIZE,
    "bafybeigse2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2",
    ethers.id("e2e-metadata-v2"),
    ethers.id(`e2e-lot-${Date.now()}`),
  ] as const;
  const reg = registry.connect(manufacturer);
  const [batchId, segmentId] = await reg.registerBatch.staticCall(...registration);
  await reg.registerBatch(...registration);

  // Custody: whole batch to the distributor, then split across two pharmacies.
  await registry.connect(manufacturer).transferSegment(segmentId, distributor.address, BATCH_SIZE);
  const segmentA = await registry
    .connect(distributor)
    .transferSegment.staticCall(segmentId, pharmacyA.address, TO_PHARMACY_A);
  await registry.connect(distributor).transferSegment(segmentId, pharmacyA.address, TO_PHARMACY_A);
  await registry
    .connect(distributor)
    .transferSegment(segmentId, pharmacyB.address, BATCH_SIZE - TO_PHARMACY_A);
  const segmentB = segmentId;

  const consume = async (index: number, segment: bigint, signer: UnitKey = keys[index]) => {
    const deadline = BigInt((await ethers.provider.getBlock("latest"))!.timestamp + 600);
    const signature = await signConsume(signer.wallet, domain, {
      batchId,
      index,
      consumer: consumer.address,
      deadline,
    });
    return registry.connect(relayer).consume(
      {
        batchId,
        segmentId: segment,
        index,
        unitKey: keys[index].address,
        consumer: consumer.address,
        deadline,
      },
      proofFor(tree, index),
      signature
    );
  };
  const rejected = async (attempt: () => Promise<unknown>) => {
    try {
      await attempt();
      return false;
    } catch {
      return true;
    }
  };

  // Gasless consumption: the consumer only signs, the relayer pays.
  await (await consume(2, segmentA)).wait();
  const refillBlocked = await rejected(() => consume(2, segmentA));
  const forgedKeyBlocked = await rejected(() => consume(3, segmentA, keys[9]));
  await (await consume(7, segmentB)).wait();

  // Recall of the pharmacy B segment blocks its remaining units.
  await registry.connect(manufacturer).invalidateSegment(segmentB);
  const recallBlocked = await rejected(() => consume(8, segmentB));

  const statusOf = async (index: number, segment: bigint) =>
    Number((await registry.unitStatus(batchId, index, segment))[0]);
  const summary = {
    registry: await registry.getAddress(),
    batchId: batchId.toString(),
    size: BATCH_SIZE,
    segments: {
      pharmacyA: { id: segmentA.toString(), range: [0, TO_PHARMACY_A] },
      pharmacyB: { id: segmentB.toString(), range: [TO_PHARMACY_A, BATCH_SIZE] },
    },
    path: "Manufacturer -> Distributor -> {PharmacyA, PharmacyB} -> Consumed",
    consumedCount: Number((await registry.getBatch(batchId)).consumedCount),
    unit2: await statusOf(2, segmentA),
    unit3: await statusOf(3, segmentA),
    unit8: await statusOf(8, segmentB),
    antiRefillBlocked: refillBlocked,
    forgedKeyBlocked,
    recallBlocked,
  };
  console.log(JSON.stringify(summary, null, 2));

  if (
    summary.consumedCount !== 2 ||
    summary.unit2 !== Unit.Consumed ||
    summary.unit3 !== Unit.AtPointOfSale ||
    summary.unit8 !== Unit.Invalid ||
    !refillBlocked ||
    !forgedKeyBlocked ||
    !recallBlocked
  ) {
    throw new Error("v2 supply-chain e2e failed");
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
