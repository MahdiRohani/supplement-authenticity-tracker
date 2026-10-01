import { StandardMerkleTree } from "@openzeppelin/merkle-tree";
import { BaseWallet, SigningKey, Wallet, keccak256, toUtf8Bytes } from "ethers";

/** Leaf value types; must match `SupplementRegistryV2.unitLeaf`. */
export const LEAF_ENCODING = ["uint32", "address"];

export const CONSUME_TYPES = {
  ConsumeAuthorization: [
    { name: "batchId", type: "uint256" },
    { name: "index", type: "uint32" },
    { name: "consumer", type: "address" },
    { name: "deadline", type: "uint256" },
  ],
};

export interface UnitKey {
  index: number;
  wallet: BaseWallet;
  address: string;
}

export interface ConsumeAuthorization {
  batchId: bigint;
  index: number;
  consumer: string;
  deadline: bigint;
}

export type BatchTree = StandardMerkleTree<[number, string]>;

/** Reproducible keys for tests and published vectors; never for real labels. */
export function deterministicUnitKeys(size: number, seed: string): UnitKey[] {
  return Array.from({ length: size }, (_, index) => {
    const wallet = new BaseWallet(
      new SigningKey(keccak256(toUtf8Bytes(`${seed}:${index}`)))
    );
    return { index, wallet, address: wallet.address };
  });
}

export function randomUnitKeys(size: number): UnitKey[] {
  return Array.from({ length: size }, (_, index) => {
    const wallet = Wallet.createRandom();
    return { index, wallet, address: wallet.address };
  });
}

export function buildBatchTree(keys: UnitKey[]): BatchTree {
  return StandardMerkleTree.of(
    keys.map((key) => [key.index, key.address] as [number, string]),
    LEAF_ENCODING
  );
}

/** Proof for the unit at `index`; keys must be passed to the tree in index order. */
export function proofFor(tree: BatchTree, index: number): string[] {
  return tree.getProof(index);
}

export function consumeDomain(chainId: bigint | number, verifyingContract: string) {
  return {
    name: "SupplementRegistry",
    version: "2",
    chainId: BigInt(chainId),
    verifyingContract,
  };
}

export function signConsume(
  wallet: BaseWallet,
  domain: ReturnType<typeof consumeDomain>,
  auth: ConsumeAuthorization
): Promise<string> {
  return wallet.signTypedData(domain, CONSUME_TYPES, auth);
}
