import { expect } from "chai";
import { ethers } from "hardhat";

describe("ProductTypes", function () {
  it("exposes ordered product lifecycle statuses", async function () {
    const harness = await ethers.deployContract("ProductTypesHarness");

    expect(await harness.statusCreated()).to.equal(0);
    expect(await harness.statusTransferred()).to.equal(1);
    expect(await harness.statusAtPointOfSale()).to.equal(2);
    expect(await harness.statusConsumed()).to.equal(3);
    expect(await harness.statusInvalid()).to.equal(4);
  });

  it("wraps and unwraps ProductId as uint256", async function () {
    const harness = await ethers.deployContract("ProductTypesHarness");
    const raw = 42n;

    const wrapped = await harness.wrapId(raw);
    expect(wrapped).to.equal(raw);
    expect(await harness.unwrapId(wrapped)).to.equal(raw);
  });
});
