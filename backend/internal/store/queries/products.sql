-- name: CreateProduct :one
INSERT INTO "Product" (
    "id", "chainProductId", "ownerAddress", "status", "name", "batchCode",
    "metadataCid", "metadataHash", "createdAt", "updatedAt"
) VALUES (
    @id, @chain_product_id, @owner_address, @status, @name, @batch_code,
    @metadata_cid, @metadata_hash, @now, @now
)
RETURNING *;

-- name: SetProductChainIdentity :one
UPDATE "Product"
SET "chainProductId" = @chain_product_id, "ownerAddress" = @owner_address, "updatedAt" = @now
WHERE "id" = @id
RETURNING *;

-- name: SetProductStatus :execrows
UPDATE "Product"
SET "status" = @status, "updatedAt" = @now
WHERE "id" = @id;

-- name: GetProductByChainID :one
SELECT * FROM "Product" WHERE "chainProductId" = @chain_product_id;

-- name: FindProduct :one
SELECT * FROM "Product" WHERE "chainProductId" = @key OR "id" = @key LIMIT 1;

-- name: CountProducts :one
SELECT count(*) FROM "Product"
WHERE (sqlc.narg('owner')::text IS NULL OR "ownerAddress" = sqlc.narg('owner'))
  AND (sqlc.narg('status')::"ProductStatus" IS NULL OR "status" = sqlc.narg('status'))
  AND (
    sqlc.narg('pattern')::text IS NULL
    OR "name" ILIKE sqlc.narg('pattern')
    OR "batchCode" ILIKE sqlc.narg('pattern')
    OR "chainProductId" LIKE sqlc.narg('pattern')
    OR "id" LIKE sqlc.narg('pattern')
  );

-- name: ListProducts :many
SELECT "id", "chainProductId", "ownerAddress", "status", "name", "batchCode", "metadataCid", "createdAt"
FROM "Product"
WHERE (sqlc.narg('owner')::text IS NULL OR "ownerAddress" = sqlc.narg('owner'))
  AND (sqlc.narg('status')::"ProductStatus" IS NULL OR "status" = sqlc.narg('status'))
  AND (
    sqlc.narg('pattern')::text IS NULL
    OR "name" ILIKE sqlc.narg('pattern')
    OR "batchCode" ILIKE sqlc.narg('pattern')
    OR "chainProductId" LIKE sqlc.narg('pattern')
    OR "id" LIKE sqlc.narg('pattern')
  )
ORDER BY "createdAt" DESC, "id" DESC
LIMIT @page_limit OFFSET @page_offset;

-- name: ListProductsByBatch :many
SELECT "chainProductId", "name", "batchCode", "status"
FROM "Product"
WHERE "batchCode" = @batch_code
ORDER BY "createdAt" ASC, "id" ASC
LIMIT 500;

-- name: UpsertRegisteredProduct :exec
INSERT INTO "Product" (
    "id", "chainProductId", "ownerAddress", "status", "metadataCid", "metadataHash", "createdAt", "updatedAt"
) VALUES (
    @id, @chain_product_id, @owner_address, @status, @metadata_cid, @metadata_hash, @now, @now
)
ON CONFLICT ("chainProductId") DO UPDATE SET
    "ownerAddress" = EXCLUDED."ownerAddress",
    "status" = EXCLUDED."status",
    "metadataCid" = EXCLUDED."metadataCid",
    "metadataHash" = EXCLUDED."metadataHash",
    "updatedAt" = EXCLUDED."updatedAt";

-- name: UpsertProductOwner :one
INSERT INTO "Product" ("id", "chainProductId", "ownerAddress", "status", "createdAt", "updatedAt")
VALUES (@id, @chain_product_id, @owner_address, @status, @now, @now)
ON CONFLICT ("chainProductId") DO UPDATE SET
    "ownerAddress" = EXCLUDED."ownerAddress",
    "status" = EXCLUDED."status",
    "updatedAt" = EXCLUDED."updatedAt"
RETURNING "id";

-- name: UpsertProductStatus :exec
INSERT INTO "Product" ("id", "chainProductId", "ownerAddress", "status", "createdAt", "updatedAt")
VALUES (@id, @chain_product_id, @owner_address, @status, @now, @now)
ON CONFLICT ("chainProductId") DO UPDATE SET
    "status" = EXCLUDED."status",
    "updatedAt" = EXCLUDED."updatedAt";
