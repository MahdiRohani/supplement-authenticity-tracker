-- name: InsertUnits :execrows
INSERT INTO "Unit" ("batchId", "index", "unitKey", "proof", "createdAt")
SELECT @batch_id::bigint, unnest(@indexes::integer[]), unnest(@unit_keys::text[]), unnest(@proofs::bytea[]), @now::timestamp
ON CONFLICT ("batchId", "index") DO UPDATE SET
    "proof" = COALESCE("Unit"."proof", EXCLUDED."proof");

-- name: GetUnit :one
SELECT * FROM "Unit" WHERE "batchId" = @batch_id AND "index" = @index;

-- name: ListUnits :many
SELECT "batchId", "index", "unitKey", "consumer", "consumedTxHash", "consumedBlock", "consumedAt"
FROM "Unit"
WHERE "batchId" = @batch_id
  AND (sqlc.narg('consumed')::boolean IS NULL OR ("consumedTxHash" IS NOT NULL) = sqlc.narg('consumed'))
ORDER BY "index" ASC
LIMIT @page_limit OFFSET @page_offset;

-- Consumption is one-shot: only the first write for a unit counts towards
-- the batch total, whichever of the API or the indexer gets there first.
-- name: MarkUnitConsumed :execrows
WITH changed AS (
    INSERT INTO "Unit" (
        "batchId", "index", "unitKey", "consumer", "consumedTxHash", "consumedBlock", "consumedAt", "createdAt"
    ) VALUES (
        @batch_id, @index, @unit_key, @consumer, @tx_hash, @block_number, @now, @now
    )
    ON CONFLICT ("batchId", "index") DO UPDATE SET
        "consumer" = EXCLUDED."consumer",
        "consumedTxHash" = EXCLUDED."consumedTxHash",
        "consumedBlock" = EXCLUDED."consumedBlock",
        "consumedAt" = EXCLUDED."consumedAt"
    WHERE "Unit"."consumedTxHash" IS NULL
    RETURNING "batchId"
)
UPDATE "Batch"
SET "consumedCount" = "consumedCount" + 1, "updatedAt" = @now
WHERE "batchId" IN (SELECT "batchId" FROM changed);
