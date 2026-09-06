import { expect } from "chai";
import { ethers } from "hardhat";
import { SupplementRegistry } from "../typechain-types";

describe("SupplementRegistry v0", function () {
  const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");
  const SECRET = ethers.id("scratch-secret-1");
  const SECRET_HASH = ethers.keccak256(
    ethers.solidityPacked(["bytes32"], [SECRET])
  );
  const OTHER_SECRET = ethers.id("scratch-secret-2");
  const OTHER_SECRET_HASH = ethers.keccak256(
    ethers.solidityPacked(["bytes32"], [OTHER_SECRET])
  );

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

  it("lets a manufacturer register a unit as Created", async function () {
    const { registry, manufacturer } = await deployFixture();

    const productId = await registry
      .connect(manufacturer)
      .registerUnit.staticCall(SECRET_HASH);

    await expect(registry.connect(manufacturer).registerUnit(SECRET_HASH))
      .to.emit(registry, "ProductRegistered")
      .withArgs(productId, manufacturer.address, 0);

    expect(productId).to.equal(1n);
    const product = await registry.getProduct(productId);
    expect(product.owner).to.equal(manufacturer.address);
    expect(product.status).to.equal(0);
  });

  it("lets a manufacturer register a batch of sequential ids", async function () {
    const { registry, manufacturer } = await deployFixture();
    const hashes = [SECRET_HASH, OTHER_SECRET_HASH, SECRET_HASH];

    const firstId = await registry
      .connect(manufacturer)
      .registerBatch.staticCall(hashes);

    await expect(registry.connect(manufacturer).registerBatch(hashes))
      .to.emit(registry, "ProductRegistered")
      .withArgs(1n, manufacturer.address, 0)
      .and.to.emit(registry, "ProductRegistered")
      .withArgs(2n, manufacturer.address, 0)
      .and.to.emit(registry, "ProductRegistered")
      .withArgs(3n, manufacturer.address, 0);

    expect(firstId).to.equal(1n);
    expect(await registry.nextProductId()).to.equal(3n);

    for (let id = 1n; id <= 3n; id++) {
      const product = await registry.getProduct(id);
      expect(product.owner).to.equal(manufacturer.address);
      expect(product.status).to.equal(0);
    }
  });

  it("consumes a product when the secret matches", async function () {
    const { registry, manufacturer, outsider } = await deployFixture();
    await registry.connect(manufacturer).registerUnit(SECRET_HASH);

    await expect(registry.connect(outsider).consume(1n, SECRET))
      .to.emit(registry, "ProductConsumed")
      .withArgs(1n, outsider.address);

    const product = await registry.getProduct(1n);
    expect(product.status).to.equal(3);
  });

  it("rejects a wrong secret and double consume", async function () {
    const { registry, manufacturer, outsider } = await deployFixture();
    await registry.connect(manufacturer).registerUnit(SECRET_HASH);

    await expect(registry.connect(outsider).consume(1n, OTHER_SECRET))
      .to.be.revertedWithCustomError(registry, "InvalidSecret")
      .withArgs(1n);

    await registry.connect(outsider).consume(1n, SECRET);

    await expect(registry.connect(outsider).consume(1n, SECRET))
      .to.be.revertedWithCustomError(registry, "ProductAlreadyConsumed")
      .withArgs(1n);
  });

  it("rejects empty secret hash on registration", async function () {
    const { registry, manufacturer } = await deployFixture();

    await expect(
      registry.connect(manufacturer).registerUnit(ethers.ZeroHash)
    ).to.be.revertedWithCustomError(registry, "InvalidSecretHash");
  });

  it("declares ownership, consume, and invalidate events in the ABI", async function () {
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

  it("rejects zero-size batches", async function () {
    const { registry, manufacturer } = await deployFixture();

    await expect(registry.connect(manufacturer).registerBatch([]))
      .to.be.revertedWithCustomError(registry, "InvalidBatchSize")
      .withArgs(0n);
  });

  it("rejects registration without MANUFACTURER_ROLE", async function () {
    const { registry, outsider } = await deployFixture();

    await expect(
      registry.connect(outsider).registerUnit(SECRET_HASH)
    ).to.be.revertedWithCustomError(
      registry,
      "AccessControlUnauthorizedAccount"
    );

    await expect(
      registry.connect(outsider).registerBatch([SECRET_HASH])
    ).to.be.revertedWithCustomError(
      registry,
      "AccessControlUnauthorizedAccount"
    );
  });

  it("rejects reading unknown products", async function () {
    const { registry } = await deployFixture();

    await expect(registry.getProduct(1n))
      .to.be.revertedWithCustomError(registry, "ProductDoesNotExist")
      .withArgs(1n);
  });
});
