-- Segment writes carry the (blockNumber, logIndex) of the chain event they
-- apply and are ignored when the row already reflects that event or a later one.

-- name: PutSegment :exec
INSERT INTO "Segment" (
    "segmentId", "batchId", "owner", "start", "end", "status",
    "blockNumber", "logIndex", "createdAt", "updatedAt"
) VALUES (
    @segment_id, @batch_id, @owner, @range_start, @range_end, @status,
    @block_number, @log_index, @now, @now
)
ON CONFLICT ("segmentId") DO UPDATE SET
    "owner" = EXCLUDED."owner",
    "start" = GREATEST("Segment"."start", EXCLUDED."start"),
    "end" = EXCLUDED."end",
    "status" = EXCLUDED."status",
    "blockNumber" = EXCLUDED."blockNumber",
    "logIndex" = EXCLUDED."logIndex",
    "updatedAt" = EXCLUDED."updatedAt"
WHERE ("Segment"."blockNumber", "Segment"."logIndex") < (EXCLUDED."blockNumber", EXCLUDED."logIndex");

-- name: MoveSegment :execrows
UPDATE "Segment"
SET "owner" = @owner, "status" = @status, "blockNumber" = @block_number, "logIndex" = @log_index, "updatedAt" = @now
WHERE "segmentId" = @segment_id
  AND ("blockNumber", "logIndex") < (@block_number::bigint, @log_index::integer);

-- A split always moves the front of a segment, so "start" only grows. The
-- shrink is therefore not position-guarded: it must still apply when a later
-- move of the same segment was projected first (API receipt ahead of the
-- indexer), and GREATEST keeps replays harmless.
-- name: ShrinkSegment :execrows
UPDATE "Segment"
SET "start" = GREATEST("start", @range_start::integer), "updatedAt" = @now
WHERE "segmentId" = @segment_id;

-- name: InvalidateSegment :execrows
UPDATE "Segment"
SET "status" = 'Invalid', "blockNumber" = @block_number, "logIndex" = @log_index, "updatedAt" = @now
WHERE "segmentId" = @segment_id
  AND ("blockNumber", "logIndex") < (@block_number::bigint, @log_index::integer);

-- name: GetSegment :one
SELECT * FROM "Segment" WHERE "segmentId" = @segment_id;

-- name: FindSegmentForUnit :one
SELECT * FROM "Segment"
WHERE "batchId" = @batch_id AND "start" <= @index::integer AND "end" > @index::integer
ORDER BY "segmentId" ASC
LIMIT 1;

-- name: ListBatchSegments :many
SELECT * FROM "Segment" WHERE "batchId" = @batch_id ORDER BY "start" ASC, "segmentId" ASC;

-- name: CountSegments :one
SELECT count(*) FROM "Segment"
WHERE (sqlc.narg('owner')::text IS NULL OR "owner" = sqlc.narg('owner'))
  AND (sqlc.narg('batch_id')::bigint IS NULL OR "batchId" = sqlc.narg('batch_id'))
  AND (sqlc.narg('status')::"SegmentStatus" IS NULL OR "status" = sqlc.narg('status'));

-- name: ListSegments :many
SELECT * FROM "Segment"
WHERE (sqlc.narg('owner')::text IS NULL OR "owner" = sqlc.narg('owner'))
  AND (sqlc.narg('batch_id')::bigint IS NULL OR "batchId" = sqlc.narg('batch_id'))
  AND (sqlc.narg('status')::"SegmentStatus" IS NULL OR "status" = sqlc.narg('status'))
ORDER BY "batchId" DESC, "start" ASC, "segmentId" ASC
LIMIT @page_limit OFFSET @page_offset;

-- name: InsertCustodyEvent :exec
INSERT INTO "CustodyEvent" (
    "id", "batchId", "fromSegmentId", "toSegmentId", "fromAddress", "toAddress",
    "start", "end", "status", "txHash", "logIndex", "blockNumber", "createdAt"
) VALUES (
    @id, @batch_id, @from_segment_id, @to_segment_id, @from_address, @to_address,
    @range_start, @range_end, @status, @tx_hash, @log_index, @block_number, @now
)
ON CONFLICT ("txHash", "logIndex") DO NOTHING;

-- name: ListUnitCustody :many
SELECT * FROM "CustodyEvent"
WHERE "batchId" = @batch_id AND "start" <= @index::integer AND "end" > @index::integer
ORDER BY "blockNumber" ASC, "logIndex" ASC;
