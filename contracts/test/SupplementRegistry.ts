import { expect } from "chai";
import { ethers } from "hardhat";
import { SupplementRegistry } from "../typechain-types";

describe("SupplementRegistry", function () {
  const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");
  const SECRET = ethers.id("scratch-secret-1");
  const SECRET_HASH = ethers.keccak256(
    ethers.solidityPacked(["bytes32"], [SECRET])
  );
  const OTHER_SECRET = ethers.id("scratch-secret-2");
  const OTHER_SECRET_HASH = ethers.keccak256(
    ethers.solidityPacked(["bytes32"], [OTHER_SECRET])
  );
  const METADATA_CID = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi";
  const METADATA_JSON = JSON.stringify({
    name: "Vitamin D3",
    batch: "B-001",
  });
  const METADATA_HASH = ethers.keccak256(ethers.toUtf8Bytes(METADATA_JSON));

  async function deployFixture() {
    const [admin, manufacturer, outsider] = await ethers.getSigners();
    const registry = (await ethers.deployContract("SupplementRegistry", [
      admin.address,
    ])) as SupplementRegistry;
    await registry.waitForDeployment();
    await registry
      .connect(admin)
      .grantRole(MANUFACTURER_ROLE, manufacturer.address);
    return { registry, admin, manufacturer, outsider };
  }

  describe("registration happy path", function () {
    it("registers a unit with metadata pointers", async function () {
      const { registry, manufacturer } = await deployFixture();

      const productId = await registry
        .connect(manufacturer)
        .registerUnit.staticCall(SECRET_HASH, METADATA_CID, METADATA_HASH);

      await expect(
        registry
          .connect(manufacturer)
          .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH)
      )
        .to.emit(registry, "ProductRegistered")
        .withArgs(
          productId,
          manufacturer.address,
          0,
          METADATA_CID,
          METADATA_HASH
        );

      expect(productId).to.equal(1n);
      const product = await registry.getProduct(productId);
      expect(product.owner).to.equal(manufacturer.address);
      expect(product.status).to.equal(0);
      expect(product.metadataCid).to.equal(METADATA_CID);
      expect(product.metadataHash).to.equal(METADATA_HASH);
    });

    it("registers a batch sharing one metadata cid", async function () {
      const { registry, manufacturer } = await deployFixture();
      const hashes = [SECRET_HASH, OTHER_SECRET_HASH, SECRET_HASH];

      const firstId = await registry
        .connect(manufacturer)
        .registerBatch.staticCall(hashes, METADATA_CID, METADATA_HASH);

      await expect(
        registry
          .connect(manufacturer)
          .registerBatch(hashes, METADATA_CID, METADATA_HASH)
      )
        .to.emit(registry, "ProductRegistered")
        .withArgs(1n, manufacturer.address, 0, METADATA_CID, METADATA_HASH)
        .and.to.emit(registry, "ProductRegistered")
        .withArgs(2n, manufacturer.address, 0, METADATA_CID, METADATA_HASH)
        .and.to.emit(registry, "ProductRegistered")
        .withArgs(3n, manufacturer.address, 0, METADATA_CID, METADATA_HASH);

      expect(firstId).to.equal(1n);
      expect(await registry.nextProductId()).to.equal(3n);

      for (let id = 1n; id <= 3n; id++) {
        const product = await registry.getProduct(id);
        expect(product.owner).to.equal(manufacturer.address);
        expect(product.status).to.equal(0);
        expect(product.metadataCid).to.equal(METADATA_CID);
        expect(product.metadataHash).to.equal(METADATA_HASH);
      }
    });
  });

  describe("role checks", function () {
    it("rejects registration without MANUFACTURER_ROLE", async function () {
      const { registry, outsider } = await deployFixture();

      await expect(
        registry
          .connect(outsider)
          .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH)
      ).to.be.revertedWithCustomError(
        registry,
        "AccessControlUnauthorizedAccount"
      );

      await expect(
        registry
          .connect(outsider)
          .registerBatch([SECRET_HASH], METADATA_CID, METADATA_HASH)
      ).to.be.revertedWithCustomError(
        registry,
        "AccessControlUnauthorizedAccount"
      );
    });
  });

  describe("consume", function () {
    it("consumes when the secret matches", async function () {
      const { registry, manufacturer, outsider } = await deployFixture();
      await registry
        .connect(manufacturer)
        .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH);

      await expect(registry.connect(outsider).consume(1n, SECRET))
        .to.emit(registry, "ProductConsumed")
        .withArgs(1n, outsider.address);

      const product = await registry.getProduct(1n);
      expect(product.status).to.equal(3);
    });

    it("rejects a wrong secret", async function () {
      const { registry, manufacturer, outsider } = await deployFixture();
      await registry
        .connect(manufacturer)
        .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH);

      await expect(registry.connect(outsider).consume(1n, OTHER_SECRET))
        .to.be.revertedWithCustomError(registry, "InvalidSecret")
        .withArgs(1n);
    });

    it("rejects double consume", async function () {
      const { registry, manufacturer, outsider } = await deployFixture();
      await registry
        .connect(manufacturer)
        .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH);

      await registry.connect(outsider).consume(1n, SECRET);

      await expect(registry.connect(outsider).consume(1n, SECRET))
        .to.be.revertedWithCustomError(registry, "ProductAlreadyConsumed")
        .withArgs(1n);
    });
  });

  describe("validation", function () {
    it("rejects empty metadata fields on registration", async function () {
      const { registry, manufacturer } = await deployFixture();

      await expect(
        registry
          .connect(manufacturer)
          .registerUnit(SECRET_HASH, "", METADATA_HASH)
      ).to.be.revertedWithCustomError(registry, "InvalidMetadataCid");

      await expect(
        registry
          .connect(manufacturer)
          .registerUnit(SECRET_HASH, METADATA_CID, ethers.ZeroHash)
      ).to.be.revertedWithCustomError(registry, "InvalidMetadataHash");

      await expect(
        registry
          .connect(manufacturer)
          .registerUnit(ethers.ZeroHash, METADATA_CID, METADATA_HASH)
      ).to.be.revertedWithCustomError(registry, "InvalidSecretHash");
    });

    it("rejects zero-size batches", async function () {
      const { registry, manufacturer } = await deployFixture();

      await expect(
        registry
          .connect(manufacturer)
          .registerBatch([], METADATA_CID, METADATA_HASH)
      )
        .to.be.revertedWithCustomError(registry, "InvalidBatchSize")
        .withArgs(0n);
    });

    it("rejects reading unknown products", async function () {
      const { registry } = await deployFixture();

      await expect(registry.getProduct(1n))
        .to.be.revertedWithCustomError(registry, "ProductDoesNotExist")
        .withArgs(1n);
    });

    it("declares lifecycle events in the ABI", async function () {
      const { registry } = await deployFixture();
      const fragmentNames = registry.interface.fragments
        .filter((fragment) => fragment.type === "event")
        .map((fragment) => fragment.name);

      expect(fragmentNames).to.include.members([
        "ProductRegistered",
        "OwnershipTransferred",
        "ProductConsumed",
        "ProductInvalidated",
      ]);
    });
  });

  describe("pausable", function () {
    it("lets admin pause and unpause mutating calls", async function () {
      const { registry, admin, manufacturer, outsider } = await deployFixture();

      await registry.connect(admin).pause();

      await expect(
        registry
          .connect(manufacturer)
          .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH)
      ).to.be.revertedWithCustomError(registry, "EnforcedPause");

      await expect(
        registry
          .connect(manufacturer)
          .registerBatch([SECRET_HASH], METADATA_CID, METADATA_HASH)
      ).to.be.revertedWithCustomError(registry, "EnforcedPause");

      await registry.connect(admin).unpause();
      await registry
        .connect(manufacturer)
        .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH);

      await registry.connect(admin).pause();
      await expect(
        registry.connect(outsider).consume(1n, SECRET)
      ).to.be.revertedWithCustomError(registry, "EnforcedPause");

      await registry.connect(admin).unpause();
      await registry.connect(outsider).consume(1n, SECRET);
      expect((await registry.getProduct(1n)).status).to.equal(3);
    });

    it("rejects pause from non-admin", async function () {
      const { registry, manufacturer } = await deployFixture();

      await expect(
        registry.connect(manufacturer).pause()
      ).to.be.revertedWithCustomError(
        registry,
        "AccessControlUnauthorizedAccount"
      );
    });
  });
});
