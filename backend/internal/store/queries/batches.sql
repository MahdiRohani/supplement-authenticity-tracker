-- Chain fields are immutable; name and lot code only come from the API, so a
-- later indexer upsert must not erase them.
-- name: UpsertBatch :one
INSERT INTO "Batch" (
    "batchId", "manufacturer", "size", "merkleRoot", "physicalBatchId", "metadataCid",
    "metadataHash", "name", "lotCode", "txHash", "blockNumber", "createdAt", "updatedAt"
) VALUES (
    @batch_id, @manufacturer, @size, @merkle_root, @physical_batch_id, @metadata_cid,
    @metadata_hash, sqlc.narg('name'), sqlc.narg('lot_code'), @tx_hash, @block_number, @now, @now
)
ON CONFLICT ("batchId") DO UPDATE SET
    "name" = COALESCE(EXCLUDED."name", "Batch"."name"),
    "lotCode" = COALESCE(EXCLUDED."lotCode", "Batch"."lotCode"),
    "updatedAt" = EXCLUDED."updatedAt"
RETURNING *;

-- name: GetBatch :one
SELECT * FROM "Batch" WHERE "batchId" = @batch_id;

-- name: CountBatches :one
SELECT count(*) FROM "Batch"
WHERE (sqlc.narg('manufacturer')::text IS NULL OR "manufacturer" = sqlc.narg('manufacturer'));

-- name: ListBatches :many
SELECT * FROM "Batch"
WHERE (sqlc.narg('manufacturer')::text IS NULL OR "manufacturer" = sqlc.narg('manufacturer'))
ORDER BY "batchId" DESC
LIMIT @page_limit OFFSET @page_offset;

-- name: SetBatchInvalid :execrows
UPDATE "Batch" SET "invalid" = true, "updatedAt" = @now WHERE "batchId" = @batch_id;

-- name: GetIndexerCursor :one
SELECT * FROM "IndexerCursor" WHERE "name" = @name;

-- name: SetIndexerCursor :exec
INSERT INTO "IndexerCursor" ("name", "lastBlock", "blockHash", "updatedAt")
VALUES (@name, @last_block, @block_hash, @now)
ON CONFLICT ("name") DO UPDATE SET
    "lastBlock" = EXCLUDED."lastBlock",
    "blockHash" = EXCLUDED."blockHash",
    "updatedAt" = EXCLUDED."updatedAt";
