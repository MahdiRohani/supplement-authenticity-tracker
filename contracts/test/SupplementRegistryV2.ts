import { expect } from "chai";
import { ethers } from "hardhat";
import { loadFixture, time } from "@nomicfoundation/hardhat-toolbox/network-helpers";
import { HardhatEthersSigner } from "@nomicfoundation/hardhat-ethers/signers";
import { TypedDataEncoder } from "ethers";
import { SupplementRegistryV2 } from "../typechain-types";
import {
  CONSUME_TYPES,
  UnitKey,
  BatchTree,
  buildBatchTree,
  consumeDomain,
  deterministicUnitKeys,
  proofFor,
  signConsume,
} from "../scripts/lib/v2";

const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");
const DISTRIBUTOR_ROLE = ethers.id("DISTRIBUTOR_ROLE");
const PHARMACY_ROLE = ethers.id("PHARMACY_ROLE");
const METADATA_CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi";
const METADATA_HASH = ethers.keccak256(
  ethers.toUtf8Bytes(JSON.stringify({ name: "Vitamin D3", batch: "B-001" }))
);

enum Seg {
  Created,
  Transferred,
  AtPointOfSale,
  Invalid,
}
enum Unit {
  Created,
  Transferred,
  AtPointOfSale,
  Consumed,
  Invalid,
}

interface Registered {
  keys: UnitKey[];
  tree: BatchTree;
  batchId: bigint;
  segmentId: bigint;
  physicalBatchId: string;
}

async function deployFixture() {
  const [
    admin,
    manufacturer,
    manufacturer2,
    distributor,
    pharmacy,
    pharmacy2,
    consumer,
    relayer,
    outsider,
  ] = await ethers.getSigners();
  const registry = (await ethers.deployContract("SupplementRegistryV2", [
    admin.address,
  ])) as unknown as SupplementRegistryV2;
  await registry.waitForDeployment();
  await registry.grantRole(MANUFACTURER_ROLE, manufacturer.address);
  await registry.grantRole(MANUFACTURER_ROLE, manufacturer2.address);
  await registry.grantRole(DISTRIBUTOR_ROLE, distributor.address);
  await registry.grantRole(PHARMACY_ROLE, pharmacy.address);
  await registry.grantRole(PHARMACY_ROLE, pharmacy2.address);
  const { chainId } = await ethers.provider.getNetwork();
  const domain = consumeDomain(chainId, await registry.getAddress());
  return {
    registry,
    domain,
    admin,
    manufacturer,
    manufacturer2,
    distributor,
    pharmacy,
    pharmacy2,
    consumer,
    relayer,
    outsider,
  };
}

type Fixture = Awaited<ReturnType<typeof deployFixture>>;

async function registerBatch(
  f: Fixture,
  size: number,
  label = "lot-1",
  by: HardhatEthersSigner = f.manufacturer
): Promise<Registered> {
  const keys = deterministicUnitKeys(size, label);
  const tree = buildBatchTree(keys);
  const physicalBatchId = ethers.id(label);
  await f.registry
    .connect(by)
    .registerBatch(tree.root, size, METADATA_CID, METADATA_HASH, physicalBatchId);
  return {
    keys,
    tree,
    batchId: await f.registry.batchCount(),
    segmentId: await f.registry.segmentCount(),
    physicalBatchId,
  };
}

async function atPharmacy(f: Fixture, size: number, label = "lot-1") {
  const batch = await registerBatch(f, size, label);
  await f.registry
    .connect(f.manufacturer)
    .transferSegment(batch.segmentId, f.distributor.address, size);
  await f.registry
    .connect(f.distributor)
    .transferSegment(batch.segmentId, f.pharmacy.address, size);
  return batch;
}

async function unitStatusOf(
  f: Fixture,
  batchId: bigint,
  index: number,
  segmentId: bigint
): Promise<[bigint, string]> {
  const [status, owner] = await f.registry.unitStatus(batchId, index, segmentId);
  return [status, owner];
}

async function consumeCall(
  f: Fixture,
  batch: Registered,
  index: number,
  options: {
    segmentId?: bigint;
    consumer?: string;
    deadline?: bigint;
    signer?: UnitKey;
    unitKey?: string;
    batchId?: bigint;
    domain?: ReturnType<typeof consumeDomain>;
  } = {}
) {
  const batchId = options.batchId ?? batch.batchId;
  const consumer = options.consumer ?? f.consumer.address;
  const deadline = options.deadline ?? BigInt((await time.latest()) + 3600);
  const signer = options.signer ?? batch.keys[index];
  const signature = await signConsume(signer.wallet, options.domain ?? f.domain, {
    batchId,
    index,
    consumer,
    deadline,
  });
  const request = {
    batchId,
    segmentId: options.segmentId ?? batch.segmentId,
    index,
    unitKey: options.unitKey ?? batch.keys[index].address,
    consumer,
    deadline,
  };
  return { request, proof: proofFor(batch.tree, index), signature };
}

describe("SupplementRegistryV2", function () {
  describe("deployment", function () {
    it("exposes protocol constants, roles and the EIP-712 domain", async function () {
      const f = await loadFixture(deployFixture);
      expect(await f.registry.UPGRADEABLE()).to.equal(false);
      expect(await f.registry.PROTOCOL_VERSION()).to.equal("2.0.0");
      expect(await f.registry.MAX_BATCH_SIZE()).to.equal(1 << 20);
      expect(await f.registry.hasRole(ethers.ZeroHash, f.admin.address)).to.equal(true);
      expect(await f.registry.hasRole(MANUFACTURER_ROLE, f.admin.address)).to.equal(true);

      const domain = await f.registry.eip712Domain();
      expect(domain.name).to.equal("SupplementRegistry");
      expect(domain.version).to.equal("2");
      expect(domain.chainId).to.equal(f.domain.chainId);
      expect(domain.verifyingContract).to.equal(f.domain.verifyingContract);

      expect(await f.registry.CONSUME_AUTHORIZATION_TYPEHASH()).to.equal(
        ethers.id(
          "ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)"
        )
      );
    });

    it("declares the v2 lifecycle events in the ABI", async function () {
      const f = await loadFixture(deployFixture);
      const events = f.registry.interface.fragments
        .filter((fragment) => fragment.type === "event")
        .map((fragment) => (fragment as { name: string }).name);
      expect(events).to.include.members([
        "BatchRegistered",
        "SegmentTransferred",
        "UnitConsumed",
        "BatchInvalidated",
        "SegmentInvalidated",
      ]);
    });
  });

  describe("registerBatch", function () {
    it("commits a batch with one root and opens a manufacturer segment", async function () {
      const f = await loadFixture(deployFixture);
      const keys = deterministicUnitKeys(8, "reg");
      const tree = buildBatchTree(keys);
      const physical = ethers.id("reg");

      const [batchId, segmentId] = await f.registry
        .connect(f.manufacturer)
        .registerBatch.staticCall(tree.root, 8, METADATA_CID, METADATA_HASH, physical);
      expect(batchId).to.equal(1n);
      expect(segmentId).to.equal(1n);

      await expect(
        f.registry
          .connect(f.manufacturer)
          .registerBatch(tree.root, 8, METADATA_CID, METADATA_HASH, physical)
      )
        .to.emit(f.registry, "BatchRegistered")
        .withArgs(1n, 1n, f.manufacturer.address, 8, tree.root, physical, METADATA_CID, METADATA_HASH);

      const batch = await f.registry.getBatch(1n);
      expect(batch.merkleRoot).to.equal(tree.root);
      expect(batch.size).to.equal(8);
      expect(batch.manufacturer).to.equal(f.manufacturer.address);
      expect(batch.consumedCount).to.equal(0);
      expect(batch.invalid).to.equal(false);
      expect(batch.metadataCid).to.equal(METADATA_CID);
      expect(batch.metadataHash).to.equal(METADATA_HASH);
      expect(batch.physicalBatchId).to.equal(physical);

      const segment = await f.registry.getSegment(1n);
      expect(segment.batchId).to.equal(1n);
      expect(segment.owner).to.equal(f.manufacturer.address);
      expect(segment.start).to.equal(0);
      expect(segment.end).to.equal(8);
      expect(segment.status).to.equal(Seg.Created);

      expect(await f.registry.batchIdByPhysicalId(physical)).to.equal(1n);
      expect(await f.registry.batchCount()).to.equal(1n);
      expect(await f.registry.segmentCount()).to.equal(1n);
      expect(await unitStatusOf(f, 1n, 7, 1n)).to.deep.equal([
        BigInt(Unit.Created),
        f.manufacturer.address,
      ]);
    });

    it("uses the same gas regardless of batch size", async function () {
      const f = await loadFixture(deployFixture);
      const gasFor = async (size: number, label: string) => {
        const tree = buildBatchTree(deterministicUnitKeys(Math.min(size, 4), label));
        const tx = await f.registry
          .connect(f.manufacturer)
          .registerBatch(tree.root, size, METADATA_CID, METADATA_HASH, ethers.id(label));
        return (await tx.wait())!.gasUsed;
      };
      await gasFor(1, "g-warmup");
      const small = await gasFor(2, "g-small");
      const large = await gasFor(100_000, "g-large");
      const diff = small > large ? small - large : large - small;
      expect(diff).to.be.lessThan(100n);
    });

    it("validates every registration field", async function () {
      const f = await loadFixture(deployFixture);
      const root = buildBatchTree(deterministicUnitKeys(2, "v")).root;
      const physical = ethers.id("v");
      const reg = f.registry.connect(f.manufacturer);

      await expect(
        reg.registerBatch(ethers.ZeroHash, 2, METADATA_CID, METADATA_HASH, physical)
      ).to.be.revertedWithCustomError(f.registry, "InvalidMerkleRoot");
      await expect(reg.registerBatch(root, 0, METADATA_CID, METADATA_HASH, physical))
        .to.be.revertedWithCustomError(f.registry, "InvalidBatchSize")
        .withArgs(0);
      await expect(
        reg.registerBatch(root, (1 << 20) + 1, METADATA_CID, METADATA_HASH, physical)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidBatchSize")
        .withArgs((1 << 20) + 1);
      await expect(
        reg.registerBatch(root, 2, "", METADATA_HASH, physical)
      ).to.be.revertedWithCustomError(f.registry, "InvalidMetadataCid");
      await expect(
        reg.registerBatch(root, 2, METADATA_CID, ethers.ZeroHash, physical)
      ).to.be.revertedWithCustomError(f.registry, "InvalidMetadataHash");
      await expect(
        reg.registerBatch(root, 2, METADATA_CID, METADATA_HASH, ethers.ZeroHash)
      ).to.be.revertedWithCustomError(f.registry, "InvalidPhysicalBatchId");

      await reg.registerBatch(root, 2, METADATA_CID, METADATA_HASH, physical);
      await expect(reg.registerBatch(root, 2, METADATA_CID, METADATA_HASH, physical))
        .to.be.revertedWithCustomError(f.registry, "PhysicalBatchIdAlreadyRegistered")
        .withArgs(physical);
    });

    it("requires MANUFACTURER_ROLE", async function () {
      const f = await loadFixture(deployFixture);
      const root = buildBatchTree(deterministicUnitKeys(1, "r")).root;
      await expect(
        f.registry
          .connect(f.outsider)
          .registerBatch(root, 1, METADATA_CID, METADATA_HASH, ethers.id("r"))
      ).to.be.revertedWithCustomError(f.registry, "AccessControlUnauthorizedAccount");
    });
  });

  describe("transferSegment", function () {
    it("moves a whole segment manufacturer -> distributor -> pharmacy keeping its id", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await registerBatch(f, 5);

      await expect(
        f.registry
          .connect(f.manufacturer)
          .transferSegment(batch.segmentId, f.distributor.address, 5)
      )
        .to.emit(f.registry, "SegmentTransferred")
        .withArgs(1n, 1n, 1n, f.manufacturer.address, f.distributor.address, 0, 5, Seg.Transferred);

      await expect(
        f.registry.connect(f.distributor).transferSegment(1n, f.pharmacy.address, 5)
      )
        .to.emit(f.registry, "SegmentTransferred")
        .withArgs(1n, 1n, 1n, f.distributor.address, f.pharmacy.address, 0, 5, Seg.AtPointOfSale);

      const segment = await f.registry.getSegment(1n);
      expect(segment.owner).to.equal(f.pharmacy.address);
      expect(segment.status).to.equal(Seg.AtPointOfSale);
      expect(await f.registry.segmentCount()).to.equal(1n);
    });

    it("splits a partial transfer into a new segment and keeps the remainder", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await registerBatch(f, 10);

      const moved = await f.registry
        .connect(f.manufacturer)
        .transferSegment.staticCall(batch.segmentId, f.distributor.address, 4);
      expect(moved).to.equal(2n);

      await expect(
        f.registry.connect(f.manufacturer).transferSegment(1n, f.distributor.address, 4)
      )
        .to.emit(f.registry, "SegmentTransferred")
        .withArgs(1n, 1n, 2n, f.manufacturer.address, f.distributor.address, 0, 4, Seg.Transferred);

      const remainder = await f.registry.getSegment(1n);
      expect([remainder.start, remainder.end, remainder.owner, remainder.status]).to.deep.equal([
        4n,
        10n,
        f.manufacturer.address,
        BigInt(Seg.Created),
      ]);
      const split = await f.registry.getSegment(2n);
      expect([split.start, split.end, split.owner, split.status]).to.deep.equal([
        0n,
        4n,
        f.distributor.address,
        BigInt(Seg.Transferred),
      ]);

      // Distributor fans the 4 units out to two pharmacies.
      await f.registry.connect(f.distributor).transferSegment(2n, f.pharmacy.address, 1);
      await f.registry.connect(f.distributor).transferSegment(2n, f.pharmacy2.address, 3);
      const a = await f.registry.getSegment(3n);
      const b = await f.registry.getSegment(2n);
      expect([a.start, a.end, a.owner]).to.deep.equal([0n, 1n, f.pharmacy.address]);
      expect([b.start, b.end, b.owner]).to.deep.equal([1n, 4n, f.pharmacy2.address]);
      expect(await unitStatusOf(f, 1n, 0, 3n)).to.deep.equal([
        BigInt(Unit.AtPointOfSale),
        f.pharmacy.address,
      ]);
      expect(await unitStatusOf(f, 1n, 5, 1n)).to.deep.equal([
        BigInt(Unit.Created),
        f.manufacturer.address,
      ]);
    });

    it("enforces the role path and rejects bad recipients", async function () {
      const f = await loadFixture(deployFixture);
      await registerBatch(f, 3);
      const asManufacturer = f.registry.connect(f.manufacturer);

      await expect(asManufacturer.transferSegment(1n, f.pharmacy.address, 3))
        .to.be.revertedWithCustomError(f.registry, "InvalidTransfer")
        .withArgs(1n, f.pharmacy.address, Seg.Created);
      await expect(asManufacturer.transferSegment(1n, ethers.ZeroAddress, 3))
        .to.be.revertedWithCustomError(f.registry, "InvalidTransfer")
        .withArgs(1n, ethers.ZeroAddress, Seg.Created);
      await expect(asManufacturer.transferSegment(1n, f.manufacturer.address, 3))
        .to.be.revertedWithCustomError(f.registry, "InvalidTransfer")
        .withArgs(1n, f.manufacturer.address, Seg.Created);
      await expect(
        f.registry.connect(f.outsider).transferSegment(1n, f.distributor.address, 3)
      )
        .to.be.revertedWithCustomError(f.registry, "NotSegmentOwner")
        .withArgs(1n, f.outsider.address);

      await asManufacturer.transferSegment(1n, f.distributor.address, 3);
      await expect(
        f.registry.connect(f.distributor).transferSegment(1n, f.outsider.address, 3)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidTransfer")
        .withArgs(1n, f.outsider.address, Seg.Transferred);

      await f.registry.connect(f.distributor).transferSegment(1n, f.pharmacy.address, 3);
      await expect(
        f.registry.connect(f.pharmacy).transferSegment(1n, f.pharmacy2.address, 3)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidTransfer")
        .withArgs(1n, f.pharmacy2.address, Seg.AtPointOfSale);
    });

    it("rejects zero or oversized counts and unknown segments", async function () {
      const f = await loadFixture(deployFixture);
      await registerBatch(f, 3);
      const asManufacturer = f.registry.connect(f.manufacturer);

      await expect(asManufacturer.transferSegment(1n, f.distributor.address, 0))
        .to.be.revertedWithCustomError(f.registry, "InvalidTransferCount")
        .withArgs(1n, 0);
      await expect(asManufacturer.transferSegment(1n, f.distributor.address, 4))
        .to.be.revertedWithCustomError(f.registry, "InvalidTransferCount")
        .withArgs(1n, 4);
      await expect(asManufacturer.transferSegment(9n, f.distributor.address, 1))
        .to.be.revertedWithCustomError(f.registry, "SegmentDoesNotExist")
        .withArgs(9n);
    });

    it("blocks transfers of recalled batches and segments", async function () {
      const f = await loadFixture(deployFixture);
      await registerBatch(f, 4, "a");
      await registerBatch(f, 4, "b");

      await f.registry.connect(f.admin).invalidateBatch(1n);
      await expect(
        f.registry.connect(f.manufacturer).transferSegment(1n, f.distributor.address, 4)
      )
        .to.be.revertedWithCustomError(f.registry, "BatchIsInvalid")
        .withArgs(1n);

      await f.registry.connect(f.admin).invalidateSegment(2n);
      await expect(
        f.registry.connect(f.manufacturer).transferSegment(2n, f.distributor.address, 4)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidTransfer")
        .withArgs(2n, f.distributor.address, Seg.Invalid);
    });
  });

  describe("consume", function () {
    it("consumes a unit through any submitter with the unit key's signature", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 8);
      const { request, proof, signature } = await consumeCall(f, batch, 5);

      await expect(f.registry.connect(f.relayer).consume(request, proof, signature))
        .to.emit(f.registry, "UnitConsumed")
        .withArgs(1n, 5, 1n, batch.keys[5].address, f.consumer.address, f.relayer.address);

      expect(await f.registry.isConsumed(1n, 5)).to.equal(true);
      expect(await f.registry.isConsumed(1n, 4)).to.equal(false);
      expect((await f.registry.getBatch(1n)).consumedCount).to.equal(1);
      expect(await unitStatusOf(f, 1n, 5, 1n)).to.deep.equal([
        BigInt(Unit.Consumed),
        f.pharmacy.address,
      ]);
      expect(await unitStatusOf(f, 1n, 4, 1n)).to.deep.equal([
        BigInt(Unit.AtPointOfSale),
        f.pharmacy.address,
      ]);
    });

    it("supports single-unit batches with an empty proof", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 1, "single");
      const call = await consumeCall(f, batch, 0);
      expect(call.proof).to.deep.equal([]);
      await expect(
        f.registry.connect(f.consumer).consume(call.request, call.proof, call.signature)
      ).to.emit(f.registry, "UnitConsumed");
    });

    it("rejects a second consumption (refill)", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 4);
      const call = await consumeCall(f, batch, 2);
      await f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature);

      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "UnitAlreadyConsumed")
        .withArgs(1n, 2);
    });

    it("keeps the signed consumer when a copied transaction front-runs", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 4);
      const call = await consumeCall(f, batch, 1);

      // An attacker replaying the pending calldata only pays the gas; the
      // recorded consumer is still the one bound by the signature.
      await expect(
        f.registry.connect(f.outsider).consume(call.request, call.proof, call.signature)
      )
        .to.emit(f.registry, "UnitConsumed")
        .withArgs(1n, 1, 1n, batch.keys[1].address, f.consumer.address, f.outsider.address);

      const redirected = { ...call.request, consumer: f.outsider.address };
      await expect(
        f.registry.connect(f.outsider).consume(redirected, call.proof, call.signature)
      ).to.be.revertedWithCustomError(f.registry, "UnitAlreadyConsumed");
    });

    it("rejects a signature rebound to another consumer", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 4);
      const call = await consumeCall(f, batch, 1);
      const redirected = { ...call.request, consumer: f.outsider.address };

      await expect(
        f.registry.connect(f.outsider).consume(redirected, call.proof, call.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidUnitSignature")
        .withArgs(1n, 1);
    });

    it("rejects a signature from a different unit key", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 4);
      const call = await consumeCall(f, batch, 1, { signer: batch.keys[2] });

      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidUnitSignature")
        .withArgs(1n, 1);
    });

    it("rejects a malformed signature", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 2);
      const call = await consumeCall(f, batch, 0);

      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, "0x1234")
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidUnitSignature")
        .withArgs(1n, 0);
    });

    it("rejects a unit key that is not in the batch tree", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 4);
      const forged = deterministicUnitKeys(1, "forged")[0];
      const call = await consumeCall(f, batch, 1, {
        signer: forged,
        unitKey: forged.address,
      });

      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidMerkleProof")
        .withArgs(1n, 1);
    });

    it("rejects a proof for another index", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 4);
      const call = await consumeCall(f, batch, 1);

      await expect(
        f.registry
          .connect(f.relayer)
          .consume(call.request, proofFor(batch.tree, 3), call.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidMerkleProof")
        .withArgs(1n, 1);
    });

    it("rejects a unit of one batch presented against another batch", async function () {
      const f = await loadFixture(deployFixture);
      const first = await atPharmacy(f, 4, "first");
      await atPharmacy(f, 4, "second");
      const call = await consumeCall(f, first, 1, { batchId: 2n, segmentId: 2n });

      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "InvalidMerkleProof")
        .withArgs(2n, 1);
    });

    it("rejects signatures made for another deployment", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 2);
      const foreign = consumeDomain(f.domain.chainId, f.outsider.address);
      const call = await consumeCall(f, batch, 0, { domain: foreign });

      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      ).to.be.revertedWithCustomError(f.registry, "InvalidUnitSignature");
    });

    it("rejects expired authorizations and a zero consumer", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 2);
      const expired = BigInt((await time.latest()) - 1);
      const late = await consumeCall(f, batch, 0, { deadline: expired });
      await expect(
        f.registry.connect(f.relayer).consume(late.request, late.proof, late.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "AuthorizationExpired")
        .withArgs(expired);

      const anonymous = await consumeCall(f, batch, 0, { consumer: ethers.ZeroAddress });
      await expect(
        f.registry
          .connect(f.relayer)
          .consume(anonymous.request, anonymous.proof, anonymous.signature)
      ).to.be.revertedWithCustomError(f.registry, "InvalidConsumer");
    });

    it("only consumes units held at a point of sale", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await registerBatch(f, 4);
      const created = await consumeCall(f, batch, 0);
      await expect(
        f.registry.connect(f.relayer).consume(created.request, created.proof, created.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "SegmentNotConsumable")
        .withArgs(1n, Seg.Created);

      await f.registry.connect(f.manufacturer).transferSegment(1n, f.distributor.address, 4);
      await expect(
        f.registry.connect(f.relayer).consume(created.request, created.proof, created.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "SegmentNotConsumable")
        .withArgs(1n, Seg.Transferred);
    });

    it("requires the index to lie inside the given segment of the same batch", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await registerBatch(f, 6, "split");
      await registerBatch(f, 2, "other");
      await f.registry.connect(f.manufacturer).transferSegment(1n, f.distributor.address, 6);
      await f.registry.connect(f.distributor).transferSegment(1n, f.pharmacy.address, 2);

      const outside = await consumeCall(f, batch, 4, { segmentId: 3n });
      await expect(
        f.registry.connect(f.relayer).consume(outside.request, outside.proof, outside.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "IndexOutOfSegment")
        .withArgs(3n, 4);

      const mismatch = await consumeCall(f, batch, 0, { segmentId: 2n });
      await expect(
        f.registry.connect(f.relayer).consume(mismatch.request, mismatch.proof, mismatch.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "SegmentBatchMismatch")
        .withArgs(2n, 1n);

      const unknownSegment = await consumeCall(f, batch, 0, { segmentId: 99n });
      await expect(
        f.registry
          .connect(f.relayer)
          .consume(unknownSegment.request, unknownSegment.proof, unknownSegment.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "SegmentDoesNotExist")
        .withArgs(99n);

      const unknownBatch = await consumeCall(f, batch, 0, { batchId: 42n, segmentId: 3n });
      await expect(
        f.registry
          .connect(f.relayer)
          .consume(unknownBatch.request, unknownBatch.proof, unknownBatch.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "BatchDoesNotExist")
        .withArgs(42n);

      const inside = await consumeCall(f, batch, 1, { segmentId: 3n });
      await expect(
        f.registry.connect(f.relayer).consume(inside.request, inside.proof, inside.signature)
      ).to.emit(f.registry, "UnitConsumed");

      await f.registry.connect(f.distributor).transferSegment(1n, f.pharmacy2.address, 4);
      const below = await consumeCall(f, batch, 0, { segmentId: 1n });
      await expect(
        f.registry.connect(f.relayer).consume(below.request, below.proof, below.signature)
      )
        .to.be.revertedWithCustomError(f.registry, "IndexOutOfSegment")
        .withArgs(1n, 0);
    });

    it("blocks consumption of recalled batches and segments", async function () {
      const f = await loadFixture(deployFixture);
      const first = await atPharmacy(f, 2, "r1");
      const second = await atPharmacy(f, 2, "r2");

      await f.registry.connect(f.manufacturer).invalidateBatch(first.batchId);
      const a = await consumeCall(f, first, 0);
      await expect(f.registry.connect(f.relayer).consume(a.request, a.proof, a.signature))
        .to.be.revertedWithCustomError(f.registry, "BatchIsInvalid")
        .withArgs(first.batchId);

      await f.registry.connect(f.admin).invalidateSegment(second.segmentId);
      const b = await consumeCall(f, second, 0);
      await expect(f.registry.connect(f.relayer).consume(b.request, b.proof, b.signature))
        .to.be.revertedWithCustomError(f.registry, "SegmentNotConsumable")
        .withArgs(second.segmentId, Seg.Invalid);
    });
  });

  describe("recall", function () {
    it("lets the admin or the batch manufacturer invalidate a batch", async function () {
      const f = await loadFixture(deployFixture);
      await registerBatch(f, 2, "m1");
      await registerBatch(f, 2, "m2");

      await expect(f.registry.connect(f.manufacturer).invalidateBatch(1n))
        .to.emit(f.registry, "BatchInvalidated")
        .withArgs(1n, f.manufacturer.address);
      await expect(f.registry.connect(f.admin).invalidateBatch(2n))
        .to.emit(f.registry, "BatchInvalidated")
        .withArgs(2n, f.admin.address);
      expect((await f.registry.getBatch(1n)).invalid).to.equal(true);
      expect(await unitStatusOf(f, 1n, 0, 1n)).to.deep.equal([
        BigInt(Unit.Invalid),
        f.manufacturer.address,
      ]);

      await expect(f.registry.connect(f.admin).invalidateBatch(1n))
        .to.be.revertedWithCustomError(f.registry, "BatchIsInvalid")
        .withArgs(1n);
    });

    it("rejects recalls from other manufacturers and outsiders", async function () {
      const f = await loadFixture(deployFixture);
      await registerBatch(f, 2);

      await expect(f.registry.connect(f.manufacturer2).invalidateBatch(1n))
        .to.be.revertedWithCustomError(f.registry, "NotAuthorizedToInvalidate")
        .withArgs(f.manufacturer2.address);
      await expect(f.registry.connect(f.outsider).invalidateSegment(1n))
        .to.be.revertedWithCustomError(f.registry, "NotAuthorizedToInvalidate")
        .withArgs(f.outsider.address);
      await expect(f.registry.connect(f.admin).invalidateBatch(7n))
        .to.be.revertedWithCustomError(f.registry, "BatchDoesNotExist")
        .withArgs(7n);
    });

    it("invalidates one segment and marks its units invalid", async function () {
      const f = await loadFixture(deployFixture);
      await registerBatch(f, 6);
      await f.registry.connect(f.manufacturer).transferSegment(1n, f.distributor.address, 2);

      await expect(f.registry.connect(f.manufacturer).invalidateSegment(2n))
        .to.emit(f.registry, "SegmentInvalidated")
        .withArgs(2n, 1n, f.manufacturer.address);
      expect(await unitStatusOf(f, 1n, 1, 2n)).to.deep.equal([
        BigInt(Unit.Invalid),
        f.distributor.address,
      ]);
      expect(await unitStatusOf(f, 1n, 3, 1n)).to.deep.equal([
        BigInt(Unit.Created),
        f.manufacturer.address,
      ]);

      await expect(f.registry.connect(f.admin).invalidateSegment(2n))
        .to.be.revertedWithCustomError(f.registry, "SegmentNotInvalidatable")
        .withArgs(2n, Seg.Invalid);
    });

    it("reports recall over consumption", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 2);
      const call = await consumeCall(f, batch, 0);
      await f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature);
      await f.registry.connect(f.admin).invalidateBatch(1n);

      expect(await f.registry.isConsumed(1n, 0)).to.equal(true);
      expect((await f.registry.unitStatus(1n, 0, 1n))[0]).to.equal(BigInt(Unit.Invalid));
    });
  });

  describe("pausable", function () {
    it("pauses every mutating entry point", async function () {
      const f = await loadFixture(deployFixture);
      const batch = await atPharmacy(f, 2);
      const call = await consumeCall(f, batch, 0);
      await f.registry.connect(f.admin).pause();
      const root = buildBatchTree(deterministicUnitKeys(1, "p")).root;

      await expect(
        f.registry
          .connect(f.manufacturer)
          .registerBatch(root, 1, METADATA_CID, METADATA_HASH, ethers.id("p"))
      ).to.be.revertedWithCustomError(f.registry, "EnforcedPause");
      await expect(
        f.registry.connect(f.pharmacy).transferSegment(1n, f.pharmacy2.address, 1)
      ).to.be.revertedWithCustomError(f.registry, "EnforcedPause");
      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      ).to.be.revertedWithCustomError(f.registry, "EnforcedPause");
      await expect(
        f.registry.connect(f.admin).invalidateBatch(1n)
      ).to.be.revertedWithCustomError(f.registry, "EnforcedPause");
      await expect(
        f.registry.connect(f.admin).invalidateSegment(1n)
      ).to.be.revertedWithCustomError(f.registry, "EnforcedPause");

      await f.registry.connect(f.admin).unpause();
      await expect(
        f.registry.connect(f.relayer).consume(call.request, call.proof, call.signature)
      ).to.emit(f.registry, "UnitConsumed");
    });

    it("restricts pause and unpause to the admin", async function () {
      const f = await loadFixture(deployFixture);
      await expect(f.registry.connect(f.manufacturer).pause()).to.be.revertedWithCustomError(
        f.registry,
        "AccessControlUnauthorizedAccount"
      );
      await f.registry.connect(f.admin).pause();
      await expect(f.registry.connect(f.manufacturer).unpause()).to.be.revertedWithCustomError(
        f.registry,
        "AccessControlUnauthorizedAccount"
      );
    });
  });

  describe("views", function () {
    it("rejects unknown batches, segments and indexes", async function () {
      const f = await loadFixture(deployFixture);
      await expect(f.registry.getBatch(1n))
        .to.be.revertedWithCustomError(f.registry, "BatchDoesNotExist")
        .withArgs(1n);
      await expect(f.registry.getSegment(1n))
        .to.be.revertedWithCustomError(f.registry, "SegmentDoesNotExist")
        .withArgs(1n);

      await registerBatch(f, 4, "x");
      await registerBatch(f, 4, "y");
      await f.registry.connect(f.manufacturer).transferSegment(1n, f.distributor.address, 2);

      await expect(f.registry.unitStatus(1n, 4, 1n))
        .to.be.revertedWithCustomError(f.registry, "IndexOutOfRange")
        .withArgs(1n, 4);
      await expect(f.registry.unitStatus(1n, 0, 2n))
        .to.be.revertedWithCustomError(f.registry, "SegmentBatchMismatch")
        .withArgs(2n, 1n);
      await expect(f.registry.unitStatus(1n, 0, 1n))
        .to.be.revertedWithCustomError(f.registry, "IndexOutOfSegment")
        .withArgs(1n, 0);
      await expect(f.registry.unitStatus(1n, 3, 3n))
        .to.be.revertedWithCustomError(f.registry, "IndexOutOfSegment")
        .withArgs(3n, 3);
      await expect(f.registry.unitStatus(1n, 0, 9n))
        .to.be.revertedWithCustomError(f.registry, "SegmentDoesNotExist")
        .withArgs(9n);
      expect((await f.registry.unitStatus(1n, 1, 3n))[0]).to.equal(BigInt(Unit.Transferred));
    });

    it("matches off-chain leaf and EIP-712 digest encodings", async function () {
      const f = await loadFixture(deployFixture);
      const keys = deterministicUnitKeys(5, "enc");
      const tree = buildBatchTree(keys);
      for (const key of keys) {
        expect(await f.registry.unitLeaf(key.index, key.address)).to.equal(
          tree.leafHash([key.index, key.address])
        );
      }

      const auth = {
        batchId: 3n,
        index: 4,
        consumer: f.consumer.address,
        deadline: 1_900_000_000n,
      };
      expect(
        await f.registry.hashConsumeAuthorization(auth.batchId, auth.index, auth.consumer, auth.deadline)
      ).to.equal(TypedDataEncoder.hash(f.domain, CONSUME_TYPES, auth));
    });
  });
});
