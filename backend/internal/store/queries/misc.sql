-- name: CreateAuditLog :exec
INSERT INTO "AuditLog" ("id", "action", "entityId", "actor", "detail", "createdAt")
VALUES (@id, @action, @entity_id, @actor, @detail, @now);

-- name: CreateCounterfeitReport :one
INSERT INTO "CounterfeitReport" ("id", "chainProductId", "note", "reporter", "createdAt")
VALUES (@id, @chain_product_id, @note, @reporter, @now)
RETURNING *;

-- name: ListCounterfeitReports :many
SELECT * FROM "CounterfeitReport" ORDER BY "createdAt" DESC, "id" DESC LIMIT @max_rows;

-- name: IncrementAnalyticsCounter :one
INSERT INTO "AnalyticsCounter" ("id", "name", "value", "updatedAt")
VALUES (@id, @name, 1, @now)
ON CONFLICT ("name") DO UPDATE SET
    "value" = "AnalyticsCounter"."value" + 1,
    "updatedAt" = EXCLUDED."updatedAt"
RETURNING "name", "value";

-- name: ListAnalyticsCounters :many
SELECT "name", "value" FROM "AnalyticsCounter" ORDER BY "name" ASC;
