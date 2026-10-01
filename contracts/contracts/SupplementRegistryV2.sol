// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import {BitMaps} from "@openzeppelin/contracts/utils/structs/BitMaps.sol";
import {ProductStatus} from "./domain/ProductTypes.sol";
import {Batch, ConsumeRequest, Segment, SegmentStatus} from "./domain/BatchTypes.sol";

/// @notice Batch-level registration with unit-level, signature-based consumption.
///
/// A batch of `size` units is committed with one Merkle root over
/// `(index, unitKey)` leaves, where `unitKey` is the address of a one-time
/// keypair whose private key is printed under the unit's scratch-off layer.
/// Custody moves as contiguous index ranges (segments) that can be split on
/// transfer. A unit is consumed by presenting an EIP-712 signature from its
/// unit key that binds the consumer address, so the secret never appears
/// on-chain and a copied transaction cannot redirect the consumption.
contract SupplementRegistryV2 is AccessControl, Pausable, EIP712 {
    using BitMaps for BitMaps.BitMap;

    bool public constant UPGRADEABLE = false;
    string public constant PROTOCOL_VERSION = "2.0.0";
    uint32 public constant MAX_BATCH_SIZE = 1 << 20;

    bytes32 public constant MANUFACTURER_ROLE = keccak256("MANUFACTURER_ROLE");
    bytes32 public constant DISTRIBUTOR_ROLE = keccak256("DISTRIBUTOR_ROLE");
    bytes32 public constant PHARMACY_ROLE = keccak256("PHARMACY_ROLE");

    bytes32 public constant CONSUME_AUTHORIZATION_TYPEHASH =
        keccak256(
            "ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)"
        );

    uint256 private _batchCount;
    uint256 private _segmentCount;
    mapping(uint256 => Batch) private _batches;
    mapping(uint256 => Segment) private _segments;
    mapping(bytes32 => uint256) private _physicalBatchIdToBatch;
    mapping(uint256 => BitMaps.BitMap) private _consumed;

    event BatchRegistered(
        uint256 indexed batchId,
        uint256 indexed segmentId,
        address indexed manufacturer,
        uint32 size,
        bytes32 merkleRoot,
        bytes32 physicalBatchId,
        string metadataCid,
        bytes32 metadataHash
    );
    /// `toSegmentId == fromSegmentId` when the whole segment moved; otherwise
    /// the moved range was split off into a new segment.
    event SegmentTransferred(
        uint256 indexed batchId,
        uint256 indexed fromSegmentId,
        uint256 indexed toSegmentId,
        address from,
        address to,
        uint32 start,
        uint32 end,
        SegmentStatus status
    );
    event UnitConsumed(
        uint256 indexed batchId,
        uint32 indexed index,
        uint256 indexed segmentId,
        address unitKey,
        address consumer,
        address submitter
    );
    event BatchInvalidated(uint256 indexed batchId, address indexed actor);
    event SegmentInvalidated(
        uint256 indexed segmentId,
        uint256 indexed batchId,
        address indexed actor
    );

    error InvalidMerkleRoot();
    error InvalidBatchSize(uint256 size);
    error InvalidMetadataCid();
    error InvalidMetadataHash();
    error InvalidPhysicalBatchId();
    error PhysicalBatchIdAlreadyRegistered(bytes32 physicalBatchId);
    error BatchDoesNotExist(uint256 batchId);
    error SegmentDoesNotExist(uint256 segmentId);
    error BatchIsInvalid(uint256 batchId);
    error NotSegmentOwner(uint256 segmentId, address account);
    error InvalidTransfer(uint256 segmentId, address to, SegmentStatus status);
    error InvalidTransferCount(uint256 segmentId, uint32 count);
    error SegmentBatchMismatch(uint256 segmentId, uint256 batchId);
    error SegmentNotConsumable(uint256 segmentId, SegmentStatus status);
    error IndexOutOfRange(uint256 batchId, uint32 index);
    error IndexOutOfSegment(uint256 segmentId, uint32 index);
    error UnitAlreadyConsumed(uint256 batchId, uint32 index);
    error InvalidMerkleProof(uint256 batchId, uint32 index);
    error InvalidUnitSignature(uint256 batchId, uint32 index);
    error AuthorizationExpired(uint256 deadline);
    error InvalidConsumer();
    error NotAuthorizedToInvalidate(address account);
    error SegmentNotInvalidatable(uint256 segmentId, SegmentStatus status);

    constructor(address admin) EIP712("SupplementRegistry", "2") {
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(MANUFACTURER_ROLE, admin);
    }

    function pause() external onlyRole(DEFAULT_ADMIN_ROLE) {
        _pause();
    }

    function unpause() external onlyRole(DEFAULT_ADMIN_ROLE) {
        _unpause();
    }

    /// @notice Registers `size` units with one storage write set, independent
    /// of `size`. The caller keeps the whole batch as segment `segmentId`.
    function registerBatch(
        bytes32 merkleRoot,
        uint32 size,
        string calldata metadataCid,
        bytes32 metadataHash,
        bytes32 physicalBatchId
    )
        external
        onlyRole(MANUFACTURER_ROLE)
        whenNotPaused
        returns (uint256 batchId, uint256 segmentId)
    {
        if (merkleRoot == bytes32(0)) {
            revert InvalidMerkleRoot();
        }
        if (size == 0 || size > MAX_BATCH_SIZE) {
            revert InvalidBatchSize(size);
        }
        if (bytes(metadataCid).length == 0) {
            revert InvalidMetadataCid();
        }
        if (metadataHash == bytes32(0)) {
            revert InvalidMetadataHash();
        }
        if (physicalBatchId == bytes32(0)) {
            revert InvalidPhysicalBatchId();
        }
        if (_physicalBatchIdToBatch[physicalBatchId] != 0) {
            revert PhysicalBatchIdAlreadyRegistered(physicalBatchId);
        }

        batchId = ++_batchCount;
        segmentId = ++_segmentCount;
        _batches[batchId] = Batch({
            merkleRoot: merkleRoot,
            metadataHash: metadataHash,
            physicalBatchId: physicalBatchId,
            manufacturer: msg.sender,
            size: size,
            consumedCount: 0,
            invalid: false,
            metadataCid: metadataCid
        });
        _segments[segmentId] = Segment({
            batchId: batchId,
            owner: msg.sender,
            start: 0,
            end: size,
            status: SegmentStatus.Created
        });
        _physicalBatchIdToBatch[physicalBatchId] = batchId;

        emit BatchRegistered(
            batchId,
            segmentId,
            msg.sender,
            size,
            merkleRoot,
            physicalBatchId,
            metadataCid,
            metadataHash
        );
    }

    /// @notice Moves the first `count` units of a segment to `to`. Moving the
    /// whole segment keeps its id; a partial move splits off a new segment
    /// and the sender keeps the remainder under the original id.
    function transferSegment(
        uint256 segmentId,
        address to,
        uint32 count
    ) external whenNotPaused returns (uint256 movedSegmentId) {
        Segment storage segment = _existingSegment(segmentId);
        if (segment.owner != msg.sender) {
            revert NotSegmentOwner(segmentId, msg.sender);
        }
        if (_batches[segment.batchId].invalid) {
            revert BatchIsInvalid(segment.batchId);
        }
        if (to == address(0) || to == msg.sender) {
            revert InvalidTransfer(segmentId, to, segment.status);
        }

        SegmentStatus next = _nextCustodyStatus(segmentId, to, segment.status);
        uint32 start = segment.start;
        uint32 length = segment.end - start;
        if (count == 0 || count > length) {
            revert InvalidTransferCount(segmentId, count);
        }

        uint256 batchId = segment.batchId;
        if (count == length) {
            segment.owner = to;
            segment.status = next;
            movedSegmentId = segmentId;
        } else {
            movedSegmentId = ++_segmentCount;
            _segments[movedSegmentId] = Segment({
                batchId: batchId,
                owner: to,
                start: start,
                end: start + count,
                status: next
            });
            segment.start = start + count;
        }

        emit SegmentTransferred(
            batchId,
            segmentId,
            movedSegmentId,
            msg.sender,
            to,
            start,
            start + count,
            next
        );
    }

    /// @notice Consumes one unit. Anyone may submit (e.g. a gas-paying
    /// relayer); authority comes from the unit key's signature over
    /// `(batchId, index, consumer, deadline)`.
    function consume(
        ConsumeRequest calldata request,
        bytes32[] calldata proof,
        bytes calldata signature
    ) external whenNotPaused {
        // slither-disable-next-line timestamp
        if (block.timestamp > request.deadline) {
            revert AuthorizationExpired(request.deadline);
        }
        if (request.consumer == address(0)) {
            revert InvalidConsumer();
        }

        Batch storage batch = _consumableUnit(request);
        if (
            !MerkleProof.verifyCalldata(
                proof,
                batch.merkleRoot,
                unitLeaf(request.index, request.unitKey)
            )
        ) {
            revert InvalidMerkleProof(request.batchId, request.index);
        }
        _requireUnitSignature(request, signature);

        _consumed[request.batchId].set(request.index);
        batch.consumedCount += 1;

        emit UnitConsumed(
            request.batchId,
            request.index,
            request.segmentId,
            request.unitKey,
            request.consumer,
            msg.sender
        );
    }

    /// @notice Recall: the admin or the batch's manufacturer can invalidate it.
    function invalidateBatch(uint256 batchId) external whenNotPaused {
        Batch storage batch = _existingBatch(batchId);
        _requireInvalidator(batch.manufacturer);
        if (batch.invalid) {
            revert BatchIsInvalid(batchId);
        }
        batch.invalid = true;
        emit BatchInvalidated(batchId, msg.sender);
    }

    function invalidateSegment(uint256 segmentId) external whenNotPaused {
        Segment storage segment = _existingSegment(segmentId);
        _requireInvalidator(_batches[segment.batchId].manufacturer);
        if (segment.status == SegmentStatus.Invalid) {
            revert SegmentNotInvalidatable(segmentId, segment.status);
        }
        segment.status = SegmentStatus.Invalid;
        emit SegmentInvalidated(segmentId, segment.batchId, msg.sender);
    }

    function getBatch(uint256 batchId) external view returns (Batch memory) {
        return _existingBatch(batchId);
    }

    function getSegment(
        uint256 segmentId
    ) external view returns (Segment memory) {
        return _existingSegment(segmentId);
    }

    /// @notice Lifecycle status of one unit. `segmentId` is the segment that
    /// currently holds `index` (tracked off-chain by the indexer); a recall of
    /// the batch or segment takes precedence over consumption.
    function unitStatus(
        uint256 batchId,
        uint32 index,
        uint256 segmentId
    ) external view returns (ProductStatus status, address owner) {
        Batch storage batch = _existingBatch(batchId);
        if (index >= batch.size) {
            revert IndexOutOfRange(batchId, index);
        }
        Segment storage segment = _existingSegment(segmentId);
        if (segment.batchId != batchId) {
            revert SegmentBatchMismatch(segmentId, batchId);
        }
        if (index < segment.start || index >= segment.end) {
            revert IndexOutOfSegment(segmentId, index);
        }

        owner = segment.owner;
        if (batch.invalid || segment.status == SegmentStatus.Invalid) {
            status = ProductStatus.Invalid;
        } else if (_consumed[batchId].get(index)) {
            status = ProductStatus.Consumed;
        } else if (segment.status == SegmentStatus.Created) {
            status = ProductStatus.Created;
        } else if (segment.status == SegmentStatus.Transferred) {
            status = ProductStatus.Transferred;
        } else {
            status = ProductStatus.AtPointOfSale;
        }
    }

    function isConsumed(
        uint256 batchId,
        uint32 index
    ) external view returns (bool) {
        return _consumed[batchId].get(index);
    }

    function batchCount() external view returns (uint256) {
        return _batchCount;
    }

    function segmentCount() external view returns (uint256) {
        return _segmentCount;
    }

    function batchIdByPhysicalId(
        bytes32 physicalBatchId
    ) external view returns (uint256) {
        return _physicalBatchIdToBatch[physicalBatchId];
    }

    /// @notice Leaf encoding shared with @openzeppelin/merkle-tree
    /// `StandardMerkleTree.of(values, ["uint32", "address"])`.
    function unitLeaf(
        uint32 index,
        address unitKey
    ) public pure returns (bytes32) {
        return keccak256(bytes.concat(keccak256(abi.encode(index, unitKey))));
    }

    function hashConsumeAuthorization(
        uint256 batchId,
        uint32 index,
        address consumer,
        uint256 deadline
    ) public view returns (bytes32) {
        return
            _hashTypedDataV4(
                keccak256(
                    abi.encode(
                        CONSUME_AUTHORIZATION_TYPEHASH,
                        batchId,
                        index,
                        consumer,
                        deadline
                    )
                )
            );
    }

    function _existingBatch(
        uint256 batchId
    ) private view returns (Batch storage batch) {
        batch = _batches[batchId];
        if (batch.size == 0) {
            revert BatchDoesNotExist(batchId);
        }
    }

    function _existingSegment(
        uint256 segmentId
    ) private view returns (Segment storage segment) {
        segment = _segments[segmentId];
        if (segment.batchId == 0) {
            revert SegmentDoesNotExist(segmentId);
        }
    }

    function _nextCustodyStatus(
        uint256 segmentId,
        address to,
        SegmentStatus status
    ) private view returns (SegmentStatus) {
        if (status == SegmentStatus.Created && hasRole(DISTRIBUTOR_ROLE, to)) {
            return SegmentStatus.Transferred;
        }
        if (status == SegmentStatus.Transferred && hasRole(PHARMACY_ROLE, to)) {
            return SegmentStatus.AtPointOfSale;
        }
        revert InvalidTransfer(segmentId, to, status);
    }

    function _consumableUnit(
        ConsumeRequest calldata request
    ) private view returns (Batch storage batch) {
        uint256 batchId = request.batchId;
        uint32 index = request.index;
        batch = _existingBatch(batchId);
        if (batch.invalid) {
            revert BatchIsInvalid(batchId);
        }
        Segment storage segment = _existingSegment(request.segmentId);
        if (segment.batchId != batchId) {
            revert SegmentBatchMismatch(request.segmentId, batchId);
        }
        if (segment.status != SegmentStatus.AtPointOfSale) {
            revert SegmentNotConsumable(request.segmentId, segment.status);
        }
        if (index < segment.start || index >= segment.end) {
            revert IndexOutOfSegment(request.segmentId, index);
        }
        if (_consumed[batchId].get(index)) {
            revert UnitAlreadyConsumed(batchId, index);
        }
    }

    function _requireUnitSignature(
        ConsumeRequest calldata request,
        bytes calldata signature
    ) private view {
        bytes32 digest = hashConsumeAuthorization(
            request.batchId,
            request.index,
            request.consumer,
            request.deadline
        );
        // slither-disable-next-line unused-return
        (address signer, ECDSA.RecoverError err, ) = ECDSA.tryRecover(
            digest,
            signature
        );
        if (err != ECDSA.RecoverError.NoError || signer != request.unitKey) {
            revert InvalidUnitSignature(request.batchId, request.index);
        }
    }

    function _requireInvalidator(address manufacturer) private view {
        if (
            msg.sender != manufacturer &&
            !hasRole(DEFAULT_ADMIN_ROLE, msg.sender)
        ) {
            revert NotAuthorizedToInvalidate(msg.sender);
        }
    }
}
