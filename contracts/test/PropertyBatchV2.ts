import { expect } from "chai";
import fc from "fast-check";
import { ethers } from "hardhat";
import { time } from "@nomicfoundation/hardhat-toolbox/network-helpers";
import { SupplementRegistryV2 } from "../typechain-types";
import {
  buildBatchTree,
  consumeDomain,
  deterministicUnitKeys,
  proofFor,
  signConsume,
} from "../scripts/lib/v2";

const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");
const DISTRIBUTOR_ROLE = ethers.id("DISTRIBUTOR_ROLE");
const PHARMACY_ROLE = ethers.id("PHARMACY_ROLE");
const METADATA_CID = "bafybeigpropertytestcid0000000000000000000000000002";
const METADATA_HASH = ethers.id("property-metadata-v2");

describe("property batch/segment/consume (v2)", function () {
  async function deploy(size: number, label: string) {
    const [admin, manufacturer, distributor, pharmacyA, pharmacyB, consumer, relayer] =
      await ethers.getSigners();
    const registry = (await ethers.deployContract("SupplementRegistryV2", [
      admin.address,
    ])) as unknown as SupplementRegistryV2;
    await registry.grantRole(MANUFACTURER_ROLE, manufacturer.address);
    await registry.grantRole(DISTRIBUTOR_ROLE, distributor.address);
    await registry.grantRole(PHARMACY_ROLE, pharmacyA.address);
    await registry.grantRole(PHARMACY_ROLE, pharmacyB.address);
    const keys = deterministicUnitKeys(size, label);
    const tree = buildBatchTree(keys);
    await registry
      .connect(manufacturer)
      .registerBatch(tree.root, size, METADATA_CID, METADATA_HASH, ethers.id(label));
    const { chainId } = await ethers.provider.getNetwork();
    const domain = consumeDomain(chainId, await registry.getAddress());
    return {
      registry,
      keys,
      tree,
      domain,
      manufacturer,
      distributor,
      pharmacyA,
      pharmacyB,
      consumer,
      relayer,
    };
  }

  async function expectPartition(registry: SupplementRegistryV2, size: number) {
    const count = Number(await registry.segmentCount());
    const ranges: Array<[number, number]> = [];
    for (let id = 1; id <= count; id++) {
      const segment = await registry.getSegment(id);
      ranges.push([Number(segment.start), Number(segment.end)]);
    }
    ranges.sort((a, b) => a[0] - b[0]);
    let cursor = 0;
    for (const [start, end] of ranges) {
      expect(start).to.equal(cursor);
      expect(end).to.be.greaterThan(start);
      cursor = end;
    }
    expect(cursor).to.equal(size);
  }

  it("keeps segments a partition of [0, size) under random splits", async function () {
    await fc.assert(
      fc.asyncProperty(
        fc.integer({ min: 2, max: 32 }),
        fc.array(fc.integer({ min: 1, max: 32 }), { minLength: 1, maxLength: 6 }),
        fc.array(fc.tuple(fc.integer({ min: 1, max: 32 }), fc.boolean()), {
          minLength: 1,
          maxLength: 6,
        }),
        async (size, manufacturerCuts, distributorCuts) => {
          const p = await deploy(size, `split-${size}-${manufacturerCuts.join("-")}`);

          let remaining = size;
          const distributorSegments: bigint[] = [];
          for (const cut of manufacturerCuts) {
            if (remaining === 0) break;
            const count = Math.min(cut, remaining);
            const moved = await p.registry
              .connect(p.manufacturer)
              .transferSegment.staticCall(1n, p.distributor.address, count);
            await p.registry.connect(p.manufacturer).transferSegment(1n, p.distributor.address, count);
            distributorSegments.push(moved);
            remaining -= count;
            await expectPartition(p.registry, size);
          }

          for (const [cut, toA] of distributorCuts) {
            const segmentId = distributorSegments[cut % distributorSegments.length];
            const segment = await p.registry.getSegment(segmentId);
            if (segment.owner !== p.distributor.address) continue;
            const length = Number(segment.end - segment.start);
            const count = Math.min(cut, length);
            const pharmacy = toA ? p.pharmacyA : p.pharmacyB;
            await p.registry.connect(p.distributor).transferSegment(segmentId, pharmacy.address, count);
            await expectPartition(p.registry, size);
          }
          return true;
        }
      ),
      { numRuns: 15 }
    );
  });

  it("consumes any subset in any order exactly once", async function () {
    await fc.assert(
      fc.asyncProperty(
        fc.integer({ min: 1, max: 24 }).chain((size) =>
          fc.tuple(
            fc.constant(size),
            fc.shuffledSubarray(
              Array.from({ length: size }, (_, i) => i),
              { minLength: 1 }
            )
          )
        ),
        async ([size, order]) => {
          const p = await deploy(size, `consume-${size}-${order.join("-")}`);
          await p.registry.connect(p.manufacturer).transferSegment(1n, p.distributor.address, size);
          await p.registry.connect(p.distributor).transferSegment(1n, p.pharmacyA.address, size);
          const deadline = BigInt((await time.latest()) + 3600);

          const calls = await Promise.all(
            order.map(async (index) => ({
              request: {
                batchId: 1n,
                segmentId: 1n,
                index,
                unitKey: p.keys[index].address,
                consumer: p.consumer.address,
                deadline,
              },
              proof: proofFor(p.tree, index),
              signature: await signConsume(p.keys[index].wallet, p.domain, {
                batchId: 1n,
                index,
                consumer: p.consumer.address,
                deadline,
              }),
            }))
          );
          for (const call of calls) {
            await p.registry.connect(p.relayer).consume(call.request, call.proof, call.signature);
          }

          const consumed = new Set(order);
          expect((await p.registry.getBatch(1n)).consumedCount).to.equal(order.length);
          for (let index = 0; index < size; index++) {
            expect(await p.registry.isConsumed(1n, index)).to.equal(consumed.has(index));
          }
          const replay = calls[calls.length - 1];
          await expect(
            p.registry.connect(p.relayer).consume(replay.request, replay.proof, replay.signature)
          ).to.be.revertedWithCustomError(p.registry, "UnitAlreadyConsumed");
          return true;
        }
      ),
      { numRuns: 12 }
    );
  });

  it("rejects any tampered proof element", async function () {
    await fc.assert(
      fc.asyncProperty(
        fc.integer({ min: 2, max: 64 }).chain((size) =>
          fc.tuple(
            fc.constant(size),
            fc.integer({ min: 0, max: size - 1 }),
            fc.nat(),
            fc.integer({ min: 0, max: 31 }),
            fc.integer({ min: 1, max: 255 })
          )
        ),
        async ([size, index, element, byteIndex, mask]) => {
          const p = await deploy(size, `tamper-${size}-${index}`);
          await p.registry.connect(p.manufacturer).transferSegment(1n, p.distributor.address, size);
          await p.registry.connect(p.distributor).transferSegment(1n, p.pharmacyA.address, size);
          const deadline = BigInt((await time.latest()) + 3600);

          const proof = proofFor(p.tree, index);
          const bytes = ethers.getBytes(proof[element % proof.length]);
          bytes[byteIndex] ^= mask;
          proof[element % proof.length] = ethers.hexlify(bytes);

          const signature = await signConsume(p.keys[index].wallet, p.domain, {
            batchId: 1n,
            index,
            consumer: p.consumer.address,
            deadline,
          });
          await expect(
            p.registry.connect(p.relayer).consume(
              {
                batchId: 1n,
                segmentId: 1n,
                index,
                unitKey: p.keys[index].address,
                consumer: p.consumer.address,
                deadline,
              },
              proof,
              signature
            )
          ).to.be.revertedWithCustomError(p.registry, "InvalidMerkleProof");
          return true;
        }
      ),
      { numRuns: 12 }
    );
  });
});
