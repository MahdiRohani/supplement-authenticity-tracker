import { expect } from "chai";
import { ethers } from "hardhat";
import { SupplementRegistry } from "../typechain-types";

describe("SupplementRegistry", function () {
  const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");
  const DISTRIBUTOR_ROLE = ethers.id("DISTRIBUTOR_ROLE");
  const PHARMACY_ROLE = ethers.id("PHARMACY_ROLE");
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
    const [admin, manufacturer, distributor, pharmacy, outsider] =
      await ethers.getSigners();
    const registry = (await ethers.deployContract("SupplementRegistry", [
      admin.address,
    ])) as SupplementRegistry;
    await registry.waitForDeployment();
    await registry
      .connect(admin)
      .grantRole(MANUFACTURER_ROLE, manufacturer.address);
    await registry
      .connect(admin)
      .grantRole(DISTRIBUTOR_ROLE, distributor.address);
    await registry.connect(admin).grantRole(PHARMACY_ROLE, pharmacy.address);
    return { registry, admin, manufacturer, distributor, pharmacy, outsider };
  }

  async function registerUnit(registry: SupplementRegistry, manufacturer: any) {
    await registry
      .connect(manufacturer)
      .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH);
    return 1n;
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
    });
  });

  describe("transfer path", function () {
    it("moves manufacturer to distributor to pharmacy", async function () {
      const { registry, manufacturer, distributor, pharmacy } =
        await deployFixture();
      const productId = await registerUnit(registry, manufacturer);

      await expect(
        registry.connect(manufacturer).transferOwnership(productId, distributor.address)
      )
        .to.emit(registry, "OwnershipTransferred")
        .withArgs(productId, manufacturer.address, distributor.address);

      let product = await registry.getProduct(productId);
      expect(product.owner).to.equal(distributor.address);
      expect(product.status).to.equal(1);

      await expect(
        registry.connect(distributor).transferOwnership(productId, pharmacy.address)
      )
        .to.emit(registry, "OwnershipTransferred")
        .withArgs(productId, distributor.address, pharmacy.address);

      product = await registry.getProduct(productId);
      expect(product.owner).to.equal(pharmacy.address);
      expect(product.status).to.equal(2);
    });

    it("rejects unauthorized transfer targets and senders", async function () {
      const { registry, manufacturer, distributor, pharmacy, outsider } =
        await deployFixture();
      const productId = await registerUnit(registry, manufacturer);

      await expect(
        registry.connect(manufacturer).transferOwnership(productId, pharmacy.address)
      )
        .to.be.revertedWithCustomError(registry, "InvalidTransfer")
        .withArgs(productId, pharmacy.address, 0);

      await expect(
        registry.connect(outsider).transferOwnership(productId, distributor.address)
      )
        .to.be.revertedWithCustomError(registry, "NotProductOwner")
        .withArgs(productId, outsider.address);

      await registry
        .connect(manufacturer)
        .transferOwnership(productId, distributor.address);

      await expect(
        registry.connect(distributor).transferOwnership(productId, outsider.address)
      )
        .to.be.revertedWithCustomError(registry, "InvalidTransfer")
        .withArgs(productId, outsider.address, 1);
    });
  });

  describe("consume", function () {
    async function atPointOfSale(registry: SupplementRegistry, parties: any) {
      const productId = await registerUnit(registry, parties.manufacturer);
      await registry
        .connect(parties.manufacturer)
        .transferOwnership(productId, parties.distributor.address);
      await registry
        .connect(parties.distributor)
        .transferOwnership(productId, parties.pharmacy.address);
      return productId;
    }

    it("consumes when the secret matches at point of sale", async function () {
      const parties = await deployFixture();
      const productId = await atPointOfSale(parties.registry, parties);

      await expect(parties.registry.connect(parties.outsider).consume(productId, SECRET))
        .to.emit(parties.registry, "ProductConsumed")
        .withArgs(productId, parties.outsider.address);

      const product = await parties.registry.getProduct(productId);
      expect(product.status).to.equal(3);
    });

    it("rejects a wrong secret", async function () {
      const parties = await deployFixture();
      const productId = await atPointOfSale(parties.registry, parties);

      await expect(
        parties.registry.connect(parties.outsider).consume(productId, OTHER_SECRET)
      )
        .to.be.revertedWithCustomError(parties.registry, "InvalidSecret")
        .withArgs(productId);
    });

    it("rejects double consume", async function () {
      const parties = await deployFixture();
      const productId = await atPointOfSale(parties.registry, parties);

      await parties.registry.connect(parties.outsider).consume(productId, SECRET);

      await expect(
        parties.registry.connect(parties.outsider).consume(productId, SECRET)
      )
        .to.be.revertedWithCustomError(parties.registry, "ProductAlreadyConsumed")
        .withArgs(productId);
    });

    it("rejects consume before pharmacy receipt", async function () {
      const { registry, manufacturer, outsider } = await deployFixture();
      const productId = await registerUnit(registry, manufacturer);

      await expect(registry.connect(outsider).consume(productId, SECRET))
        .to.be.revertedWithCustomError(registry, "ProductNotConsumable")
        .withArgs(productId, 0);
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
      const { registry, admin, manufacturer } = await deployFixture();

      await registry.connect(admin).pause();

      await expect(
        registry
          .connect(manufacturer)
          .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH)
      ).to.be.revertedWithCustomError(registry, "EnforcedPause");

      await registry.connect(admin).unpause();
      await registry
        .connect(manufacturer)
        .registerUnit(SECRET_HASH, METADATA_CID, METADATA_HASH);
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
