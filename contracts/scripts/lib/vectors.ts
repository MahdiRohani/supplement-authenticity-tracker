import { TypedDataEncoder, id } from "ethers";
import {
  CONSUME_TYPES,
  LEAF_ENCODING,
  buildBatchTree,
  consumeDomain,
  deterministicUnitKeys,
  proofFor,
  signConsume,
} from "./v2";

/** Hardhat's second deployment address, where deploy.ts places v2 locally. */
export const VECTOR_VERIFYING_CONTRACT = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512";
export const VECTOR_CHAIN_ID = 31337n;
const BATCH_SIZES = [1, 2, 3, 5, 8];
const CONSUMER = "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc";

/**
 * Cross-language fixtures for the v2 protocol: Merkle leaves/roots/proofs and
 * EIP-712 consume authorizations. The Go backend and the Android app test
 * their implementations against this file.
 */
export async function buildVectors() {
  const domain = consumeDomain(VECTOR_CHAIN_ID, VECTOR_VERIFYING_CONTRACT);
  const batches = BATCH_SIZES.map((size) => {
    const seed = `vector-batch-${size}`;
    const keys = deterministicUnitKeys(size, seed);
    const tree = buildBatchTree(keys);
    return {
      seed,
      size,
      root: tree.root,
      units: keys.map((key) => ({
        index: key.index,
        privateKey: key.wallet.privateKey,
        address: key.address,
        leaf: tree.leafHash([key.index, key.address]),
        proof: proofFor(tree, key.index),
      })),
    };
  });

  const batch = batches[batches.length - 1];
  const authorizations = await Promise.all(
    [0, 3, 7].map(async (index, i) => {
      const unit = batch.units[index];
      const auth = {
        batchId: BigInt(i + 1),
        index,
        consumer: CONSUMER,
        deadline: 1_900_000_000n + BigInt(i),
      };
      const key = deterministicUnitKeys(batch.size, batch.seed)[index];
      return {
        batchSeed: batch.seed,
        batchId: auth.batchId.toString(),
        index,
        consumer: auth.consumer,
        deadline: auth.deadline.toString(),
        unitKey: unit.address,
        structHash: TypedDataEncoder.from(CONSUME_TYPES).hash(auth),
        digest: TypedDataEncoder.hash(domain, CONSUME_TYPES, auth),
        signature: await signConsume(key.wallet, domain, auth),
      };
    })
  );

  return {
    description:
      "SupplementRegistryV2 reference vectors (Merkle unit leaves and EIP-712 ConsumeAuthorization). Test keys only.",
    protocolVersion: "2.0.0",
    leafEncoding: LEAF_ENCODING,
    leafHash: "keccak256(bytes.concat(keccak256(abi.encode(uint32 index, address unitKey))))",
    unitKeyDerivation: "privateKey = keccak256(utf8(`${seed}:${index}`))",
    domain: {
      name: domain.name,
      version: domain.version,
      chainId: domain.chainId.toString(),
      verifyingContract: domain.verifyingContract,
    },
    typeHash: id(
      "ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)"
    ),
    domainSeparator: TypedDataEncoder.hashDomain(domain),
    batches,
    authorizations,
  };
}
