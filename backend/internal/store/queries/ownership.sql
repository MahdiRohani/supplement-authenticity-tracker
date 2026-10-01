-- name: OwnershipEventExists :one
SELECT EXISTS (
    SELECT 1 FROM "OwnershipEvent"
    WHERE "txHash" = @tx_hash AND "chainProductId" = @chain_product_id
);

-- name: CreateOwnershipEvent :exec
INSERT INTO "OwnershipEvent" (
    "id", "productId", "chainProductId", "fromAddress", "toAddress", "txHash", "blockNumber", "createdAt"
) VALUES (
    @id, @product_id, @chain_product_id, @from_address, @to_address, @tx_hash, @block_number, @now
);

-- name: ListOwnershipEvents :many
SELECT "id", "fromAddress", "toAddress", "txHash", "blockNumber", "createdAt"
FROM "OwnershipEvent"
WHERE "productId" = @product_id
ORDER BY "blockNumber" ASC, "createdAt" ASC;
