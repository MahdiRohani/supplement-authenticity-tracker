-- Projection of SupplementRegistryV2: Merkle-committed batches, custody
-- segments (contiguous unit ranges), per-unit consumption, custody history,
-- public scan events for clone detection, and the indexer cursor.
-- Unit private keys are never stored; only the public unit key and the
-- Merkle proof needed to relay a consume.

-- +goose Up
CREATE TYPE "SegmentStatus" AS ENUM ('Created', 'Transferred', 'AtPointOfSale', 'Invalid');

CREATE TABLE "Batch" (
    "batchId" BIGINT NOT NULL,
    "manufacturer" TEXT NOT NULL,
    "size" INTEGER NOT NULL,
    "merkleRoot" TEXT NOT NULL,
    "physicalBatchId" TEXT NOT NULL,
    "metadataCid" TEXT NOT NULL,
    "metadataHash" TEXT NOT NULL,
    "name" TEXT,
    "lotCode" TEXT,
    "consumedCount" INTEGER NOT NULL DEFAULT 0,
    "invalid" BOOLEAN NOT NULL DEFAULT false,
    "txHash" TEXT NOT NULL,
    "blockNumber" BIGINT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "Batch_pkey" PRIMARY KEY ("batchId"),
    CONSTRAINT "Batch_size_check" CHECK ("size" > 0)
);

CREATE TABLE "Segment" (
    "segmentId" BIGINT NOT NULL,
    "batchId" BIGINT NOT NULL,
    "owner" TEXT NOT NULL,
    "start" INTEGER NOT NULL,
    "end" INTEGER NOT NULL,
    "status" "SegmentStatus" NOT NULL,
    -- Position of the last applied chain event. The API (from receipts) and
    -- the indexer (from logs) apply the same events; updates only move this
    -- forward, so replays and races cannot regress a segment.
    "blockNumber" BIGINT NOT NULL,
    "logIndex" INTEGER NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "Segment_pkey" PRIMARY KEY ("segmentId"),
    CONSTRAINT "Segment_range_check" CHECK ("start" >= 0 AND "start" <= "end")
);

CREATE TABLE "Unit" (
    "batchId" BIGINT NOT NULL,
    "index" INTEGER NOT NULL,
    "unitKey" TEXT NOT NULL,
    "proof" BYTEA,
    "consumer" TEXT,
    "consumedTxHash" TEXT,
    "consumedBlock" BIGINT,
    "consumedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "Unit_pkey" PRIMARY KEY ("batchId", "index")
);

CREATE TABLE "CustodyEvent" (
    "id" TEXT NOT NULL,
    "batchId" BIGINT NOT NULL,
    "fromSegmentId" BIGINT NOT NULL,
    "toSegmentId" BIGINT NOT NULL,
    "fromAddress" TEXT NOT NULL,
    "toAddress" TEXT NOT NULL,
    "start" INTEGER NOT NULL,
    "end" INTEGER NOT NULL,
    "status" "SegmentStatus" NOT NULL,
    "txHash" TEXT NOT NULL,
    "logIndex" INTEGER NOT NULL,
    "blockNumber" BIGINT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustodyEvent_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "ScanEvent" (
    "id" TEXT NOT NULL,
    "batchId" BIGINT NOT NULL,
    "index" INTEGER NOT NULL,
    "deviceHash" TEXT NOT NULL,
    "region" TEXT,
    "statusAtScan" TEXT NOT NULL,
    "riskScore" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ScanEvent_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "IndexerCursor" (
    "name" TEXT NOT NULL,
    "lastBlock" BIGINT NOT NULL,
    -- Hash of lastBlock; a mismatch on restart means a reorg or chain reset.
    "blockHash" TEXT NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "IndexerCursor_pkey" PRIMARY KEY ("name")
);

ALTER TABLE "RoleBinding" ADD COLUMN "displayName" TEXT;
ALTER TABLE "RoleBinding" ADD COLUMN "region" TEXT;

CREATE UNIQUE INDEX "Batch_physicalBatchId_key" ON "Batch"("physicalBatchId");
CREATE INDEX "Batch_manufacturer_idx" ON "Batch"("manufacturer");
CREATE INDEX "Batch_lotCode_idx" ON "Batch"("lotCode");

CREATE INDEX "Segment_owner_idx" ON "Segment"("owner");
CREATE INDEX "Segment_batchId_start_idx" ON "Segment"("batchId", "start");

CREATE UNIQUE INDEX "CustodyEvent_txHash_logIndex_key" ON "CustodyEvent"("txHash", "logIndex");
CREATE INDEX "CustodyEvent_batchId_idx" ON "CustodyEvent"("batchId");

CREATE INDEX "ScanEvent_unit_createdAt_idx" ON "ScanEvent"("batchId", "index", "createdAt");
CREATE INDEX "ScanEvent_createdAt_idx" ON "ScanEvent"("createdAt");

ALTER TABLE "Segment" ADD CONSTRAINT "Segment_batchId_fkey"
    FOREIGN KEY ("batchId") REFERENCES "Batch"("batchId") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "Unit" ADD CONSTRAINT "Unit_batchId_fkey"
    FOREIGN KEY ("batchId") REFERENCES "Batch"("batchId") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "CustodyEvent" ADD CONSTRAINT "CustodyEvent_batchId_fkey"
    FOREIGN KEY ("batchId") REFERENCES "Batch"("batchId") ON DELETE RESTRICT ON UPDATE CASCADE;

-- +goose Down
DROP TABLE "ScanEvent";
DROP TABLE "CustodyEvent";
DROP TABLE "Unit";
DROP TABLE "Segment";
DROP TABLE "Batch";
DROP TABLE "IndexerCursor";
ALTER TABLE "RoleBinding" DROP COLUMN "region";
ALTER TABLE "RoleBinding" DROP COLUMN "displayName";
DROP TYPE "SegmentStatus";
