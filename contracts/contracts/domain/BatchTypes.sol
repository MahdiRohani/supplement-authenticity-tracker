// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

/// Custody state of a contiguous index range of a batch. Consumption is
/// tracked per unit in a bitmap, so a segment never becomes "Consumed".
enum SegmentStatus {
    Created,
    Transferred,
    AtPointOfSale,
    Invalid
}

struct Batch {
    bytes32 merkleRoot;
    bytes32 metadataHash;
    bytes32 physicalBatchId;
    address manufacturer;
    uint32 size;
    uint32 consumedCount;
    bool invalid;
    string metadataCid;
}

/// Units [start, end) of `batchId` held by `owner`.
struct Segment {
    uint256 batchId;
    address owner;
    uint32 start;
    uint32 end;
    SegmentStatus status;
}

struct ConsumeRequest {
    uint256 batchId;
    uint256 segmentId;
    uint32 index;
    address unitKey;
    address consumer;
    uint256 deadline;
}
