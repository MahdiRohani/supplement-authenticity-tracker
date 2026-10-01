import { expect } from "chai";
import * as fs from "fs";
import * as path from "path";
import { ethers } from "hardhat";
import { StandardMerkleTree } from "@openzeppelin/merkle-tree";
import { SupplementRegistryV2 } from "../typechain-types";
import { buildVectors } from "../scripts/lib/vectors";
import { LEAF_ENCODING } from "../scripts/lib/v2";

const VECTORS_PATH = path.join(
  __dirname,
  "../../packages/abis/test-vectors/supplement-registry-v2.json"
);

describe("v2 reference vectors", function () {
  it("match the committed file (run `npm run vectors` after protocol changes)", async function () {
    const committed = JSON.parse(fs.readFileSync(VECTORS_PATH, "utf8"));
    expect(committed).to.deep.equal(JSON.parse(JSON.stringify(await buildVectors())));
  });

  it("agree with the contract's leaf encoding and verify as Merkle proofs", async function () {
    const [admin] = await ethers.getSigners();
    const registry = (await ethers.deployContract("SupplementRegistryV2", [
      admin.address,
    ])) as unknown as SupplementRegistryV2;
    const vectors = JSON.parse(fs.readFileSync(VECTORS_PATH, "utf8"));

    for (const batch of vectors.batches) {
      for (const unit of batch.units) {
        expect(await registry.unitLeaf(unit.index, unit.address)).to.equal(unit.leaf);
        expect(
          StandardMerkleTree.verify(batch.root, LEAF_ENCODING, [unit.index, unit.address], unit.proof)
        ).to.equal(true);
        expect(new ethers.Wallet(unit.privateKey).address).to.equal(unit.address);
      }
    }
    for (const auth of vectors.authorizations) {
      expect(ethers.recoverAddress(auth.digest, auth.signature)).to.equal(auth.unitKey);
    }
  });
});
