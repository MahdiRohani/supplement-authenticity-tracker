import { expect } from "chai";
import fc from "fast-check";
import { ethers } from "hardhat";
import { SupplementRegistry } from "../typechain-types";

describe("property consume/transfer", function () {
  const MANUFACTURER_ROLE = ethers.id("MANUFACTURER_ROLE");
  const DISTRIBUTOR_ROLE = ethers.id("DISTRIBUTOR_ROLE");
  const PHARMACY_ROLE = ethers.id("PHARMACY_ROLE");
  const METADATA_CID = "bafybeigpropertytestcid0000000000000000000000000001";
  const METADATA_HASH = ethers.id("property-metadata");

  async function deploy() {
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

  it("rejects random wrong secrets at point of sale", async function () {
    await fc.assert(
      fc.asyncProperty(
        fc.uint8Array({ minLength: 32, maxLength: 32 }),
        async (bytes) => {
          const parties = await deploy();
          const secret = ethers.id(`secret-${Buffer.from(bytes).toString("hex").slice(0, 8)}`);
          const secretHash = ethers.keccak256(
            ethers.solidityPacked(["bytes32"], [secret])
          );
          const physicalId = ethers.hexlify(bytes);
          await parties.registry
            .connect(parties.manufacturer)
            .registerUnit(secretHash, METADATA_CID, METADATA_HASH, physicalId);
          await parties.registry
            .connect(parties.manufacturer)
            .transferOwnership(1n, parties.distributor.address);
          await parties.registry
            .connect(parties.distributor)
            .transferOwnership(1n, parties.pharmacy.address);

          const wrong = ethers.hexlify(bytes);
          if (wrong.toLowerCase() === secret.toLowerCase()) {
            return true;
          }
          await expect(
            parties.registry.connect(parties.outsider).consume(1n, wrong)
          ).to.be.revertedWithCustomError(parties.registry, "InvalidSecret");
          return true;
        }
      ),
      { numRuns: 8 }
    );
  });

  it("rejects transfer to zero or self for created products", async function () {
    await fc.assert(
      fc.asyncProperty(fc.constantFrom("zero", "self"), async (kind) => {
        const parties = await deploy();
        const secret = ethers.id(`t-${kind}`);
        const secretHash = ethers.keccak256(
          ethers.solidityPacked(["bytes32"], [secret])
        );
        const physicalId = ethers.id(`tp-${kind}-${Date.now()}-${Math.random()}`);
        await parties.registry
          .connect(parties.manufacturer)
          .registerUnit(secretHash, METADATA_CID, METADATA_HASH, physicalId);
        const to =
          kind === "zero" ? ethers.ZeroAddress : parties.manufacturer.address;
        await expect(
          parties.registry
            .connect(parties.manufacturer)
            .transferOwnership(1n, to)
        ).to.be.revertedWithCustomError(parties.registry, "InvalidTransfer");
        return true;
      }),
      { numRuns: 4 }
    );
  });
});
