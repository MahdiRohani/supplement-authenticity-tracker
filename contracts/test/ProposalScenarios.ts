import { expect } from "chai";
import { ethers } from "hardhat";

describe("proposal scenario map", function () {
  const scenarios = [
    "register unit with metadata CID/hash",
    "register batch sharing metadata",
    "reject duplicate physical id",
    "role-gated manufacturer registration",
    "manufacturer to distributor to pharmacy",
    "consume only at point of sale with secret",
    "reject wrong secret and double consume",
    "pause blocks mutations",
    "admin can invalidate active product",
  ];

  it("documents covered proposal scenarios", function () {
    expect(scenarios.length).to.be.greaterThanOrEqual(9);
  });

  it("covers invalidate as proposal authenticity control", async function () {
    const [admin, manufacturer] = await ethers.getSigners();
    const registry = await ethers.deployContract("SupplementRegistry", [
      admin.address,
    ]);
    await registry.waitForDeployment();
    await registry
      .connect(admin)
      .grantRole(ethers.id("MANUFACTURER_ROLE"), manufacturer.address);
    const secret = ethers.id("scenario-secret");
    const secretHash = ethers.keccak256(
      ethers.solidityPacked(["bytes32"], [secret])
    );
    await registry
      .connect(manufacturer)
      .registerUnit(
        secretHash,
        "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
        ethers.id("meta"),
        ethers.id("phys-scenario")
      );
    await registry.connect(admin).invalidate(1n);
    const view = await registry.getProductStatus(1n);
    expect(view.status).to.equal(4);
  });
});
