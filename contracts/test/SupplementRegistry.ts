import { expect } from "chai";
import { ethers } from "hardhat";
import { SupplementRegistry } from "../typechain-types";

describe("SupplementRegistry v0", function () {
  const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");

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
      .registerUnit.staticCall();

    await expect(registry.connect(manufacturer).registerUnit())
      .to.emit(registry, "ProductRegistered")
      .withArgs(productId, manufacturer.address, 0);

    expect(productId).to.equal(1n);
    const product = await registry.getProduct(productId);
    expect(product.owner).to.equal(manufacturer.address);
    expect(product.status).to.equal(0);
  });

  it("lets a manufacturer register a batch of sequential ids", async function () {
    const { registry, manufacturer } = await deployFixture();

    const firstId = await registry
      .connect(manufacturer)
      .registerBatch.staticCall(3n);

    await expect(registry.connect(manufacturer).registerBatch(3n))
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

    await expect(registry.connect(manufacturer).registerBatch(0n))
      .to.be.revertedWithCustomError(registry, "InvalidBatchSize")
      .withArgs(0n);
  });

  it("rejects registration without MANUFACTURER_ROLE", async function () {
    const { registry, outsider } = await deployFixture();

    await expect(
      registry.connect(outsider).registerUnit()
    ).to.be.revertedWithCustomError(
      registry,
      "AccessControlUnauthorizedAccount"
    );

    await expect(
      registry.connect(outsider).registerBatch(2n)
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
