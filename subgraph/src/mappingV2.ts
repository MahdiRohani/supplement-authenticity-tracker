import { Address, ethereum } from "@graphprotocol/graph-ts";
import {
  BatchInvalidated,
  BatchRegistered,
  SegmentInvalidated,
  SegmentTransferred,
  UnitConsumed,
} from "../generated/SupplementRegistryV2/SupplementRegistryV2";
import {
  Batch,
  Recall,
  Segment,
  SegmentTransfer,
  UnitConsumption,
} from "../generated/schema";

// Must follow the order of SegmentStatus in contracts/domain/BatchTypes.sol.
const SEGMENT_STATUSES: string[] = [
  "Created",
  "Transferred",
  "AtPointOfSale",
  "Invalid",
];

function segmentStatus(value: i32): string {
  return value >= 0 && value < SEGMENT_STATUSES.length
    ? SEGMENT_STATUSES[value]
    : "Invalid";
}

function eventId(event: ethereum.Event): string {
  return event.transaction.hash.toHex() + "-" + event.logIndex.toString();
}

export function handleBatchRegistered(event: BatchRegistered): void {
  let batch = new Batch(event.params.batchId.toString());
  batch.manufacturer = event.params.manufacturer;
  batch.size = event.params.size.toI32();
  batch.merkleRoot = event.params.merkleRoot;
  batch.physicalBatchId = event.params.physicalBatchId;
  batch.metadataCid = event.params.metadataCid;
  batch.metadataHash = event.params.metadataHash;
  batch.consumedCount = 0;
  batch.recalled = false;
  batch.registeredAtBlock = event.block.number;
  batch.registeredAt = event.block.timestamp;
  batch.registerTx = event.transaction.hash;
  batch.save();

  let segment = new Segment(event.params.segmentId.toString());
  segment.batch = batch.id;
  segment.parent = null;
  segment.owner = event.params.manufacturer;
  segment.start = 0;
  segment.end = batch.size;
  segment.units = batch.size;
  segment.status = "Created";
  segment.updatedAtBlock = event.block.number;
  segment.save();
}

export function handleSegmentTransferred(event: SegmentTransferred): void {
  let batchId = event.params.batchId.toString();
  let fromId = event.params.fromSegmentId.toString();
  let toId = event.params.toSegmentId.toString();
  let start = event.params.start.toI32();
  let end = event.params.end.toI32();
  let status = segmentStatus(event.params.status);
  let split = fromId != toId;

  let source = Segment.load(fromId);
  if (source == null) {
    return;
  }

  // A partial transfer moves the head [start, end) into a new segment and
  // leaves the tail with the sender; a full transfer re-owns the segment.
  let moved: Segment;
  if (split) {
    source.start = end;
    source.units = source.end - end;
    source.updatedAtBlock = event.block.number;
    source.save();

    moved = new Segment(toId);
    moved.batch = batchId;
    moved.parent = fromId;
    moved.start = start;
    moved.end = end;
    moved.units = end - start;
  } else {
    moved = source!;
  }
  moved.owner = event.params.to;
  moved.status = status;
  moved.updatedAtBlock = event.block.number;
  moved.save();

  let transfer = new SegmentTransfer(eventId(event));
  transfer.batch = batchId;
  transfer.fromSegment = fromId;
  transfer.toSegment = toId;
  transfer.split = split;
  transfer.from = event.params.from;
  transfer.to = event.params.to;
  transfer.start = start;
  transfer.end = end;
  transfer.status = status;
  transfer.txHash = event.transaction.hash;
  transfer.blockNumber = event.block.number;
  transfer.timestamp = event.block.timestamp;
  transfer.save();
}

export function handleUnitConsumed(event: UnitConsumed): void {
  let batchId = event.params.batchId.toString();
  let batch = Batch.load(batchId);
  if (batch == null) {
    return;
  }
  batch.consumedCount = batch.consumedCount + 1;
  batch.save();

  let index = event.params.index.toI32();
  let consumption = new UnitConsumption(batchId + "-" + index.toString());
  consumption.batch = batchId;
  consumption.index = index;
  consumption.segment = event.params.segmentId.toString();
  consumption.unitKey = event.params.unitKey;
  consumption.consumer = event.params.consumer;
  consumption.submitter = event.params.submitter;
  consumption.txHash = event.transaction.hash;
  consumption.blockNumber = event.block.number;
  consumption.timestamp = event.block.timestamp;
  consumption.save();
}

export function handleBatchInvalidated(event: BatchInvalidated): void {
  let batch = Batch.load(event.params.batchId.toString());
  if (batch == null) {
    return;
  }
  batch.recalled = true;
  batch.save();
  saveRecall(event, batch.id, null, event.params.actor);
}

export function handleSegmentInvalidated(event: SegmentInvalidated): void {
  let segment = Segment.load(event.params.segmentId.toString());
  if (segment == null) {
    return;
  }
  segment.status = "Invalid";
  segment.updatedAtBlock = event.block.number;
  segment.save();
  saveRecall(event, segment.batch, segment.id, event.params.actor);
}

function saveRecall(
  event: ethereum.Event,
  batchId: string,
  segmentId: string | null,
  actor: Address
): void {
  let recall = new Recall(eventId(event));
  recall.batch = batchId;
  recall.segment = segmentId;
  recall.actor = actor;
  recall.txHash = event.transaction.hash;
  recall.blockNumber = event.block.number;
  recall.timestamp = event.block.timestamp;
  recall.save();
}
