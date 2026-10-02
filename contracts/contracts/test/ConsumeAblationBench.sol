// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import {BitMaps} from "@openzeppelin/contracts/utils/structs/BitMaps.sol";

/// @notice Benchmark only, never deployed. Isolates the gas cost of
/// signature-based consumption against hash-reveal consumption of a
/// Merkle-batched lot. Both paths share the Merkle check, bitmap write,
/// counter and event of `SupplementRegistryV2.consume`; custody checks are
/// omitted because they are identical for both and cancel in the difference.
contract ConsumeAblationBench is EIP712 {
    using BitMaps for BitMaps.BitMap;

    bytes32 private constant CONSUME_AUTHORIZATION_TYPEHASH =
        keccak256(
            "ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)"
        );

    mapping(uint256 => bytes32) private _roots;
    mapping(uint256 => uint32) private _consumedCount;
    mapping(uint256 => BitMaps.BitMap) private _consumed;
    uint256 private _batchCount;

    event UnitConsumed(uint256 indexed batchId, uint32 index, address consumer);

    error InvalidMerkleProof();
    error InvalidSignature();
    error AlreadyConsumed();
    error Expired();

    constructor() EIP712("SupplementRegistry", "2") {}

    function register(bytes32 root) external returns (uint256 batchId) {
        batchId = ++_batchCount;
        _roots[batchId] = root;
    }

    /// Per-unit key, signature bound to the consumer (as in V2).
    function consumeSigned(
        uint256 batchId,
        uint32 index,
        address unitKey,
        address consumer,
        uint256 deadline,
        bytes32[] calldata proof,
        bytes calldata signature
    ) external {
        // slither-disable-next-line timestamp
        if (block.timestamp > deadline) revert Expired();
        _checkUnused(batchId, index);
        bytes32 leaf = keccak256(bytes.concat(keccak256(abi.encode(index, unitKey))));
        if (!MerkleProof.verifyCalldata(proof, _roots[batchId], leaf)) revert InvalidMerkleProof();
        bytes32 digest = _hashTypedDataV4(
            keccak256(abi.encode(CONSUME_AUTHORIZATION_TYPEHASH, batchId, index, consumer, deadline))
        );
        // slither-disable-next-line unused-return
        (address signer, ECDSA.RecoverError err, ) = ECDSA.tryRecover(digest, signature);
        if (err != ECDSA.RecoverError.NoError || signer != unitKey) revert InvalidSignature();
        _markConsumed(batchId, index, consumer);
    }

    /// Per-unit secret revealed in plaintext; the leaf commits to its hash.
    function consumeReveal(
        uint256 batchId,
        uint32 index,
        bytes32 secret,
        address consumer,
        bytes32[] calldata proof
    ) external {
        _checkUnused(batchId, index);
        bytes32 leaf = keccak256(
            bytes.concat(keccak256(abi.encode(index, keccak256(abi.encode(secret)))))
        );
        if (!MerkleProof.verifyCalldata(proof, _roots[batchId], leaf)) revert InvalidMerkleProof();
        _markConsumed(batchId, index, consumer);
    }

    function _checkUnused(uint256 batchId, uint32 index) private view {
        if (_consumed[batchId].get(index)) revert AlreadyConsumed();
    }

    function _markConsumed(uint256 batchId, uint32 index, address consumer) private {
        _consumed[batchId].set(index);
        _consumedCount[batchId] += 1;
        emit UnitConsumed(batchId, index, consumer);
    }
}
