-- name: ListRoleBindings :many
SELECT * FROM "RoleBinding" ORDER BY "role" ASC, "address" ASC;

-- name: ListRoleBindingsForAddress :many
SELECT * FROM "RoleBinding" WHERE "address" = @address ORDER BY "role" ASC;

-- Existing bindings are returned untouched, matching Prisma's `upsert` with an empty update.
-- name: UpsertRoleBinding :one
INSERT INTO "RoleBinding" ("id", "address", "role", "createdAt", "updatedAt")
VALUES (@id, @address, @role, @now, @now)
ON CONFLICT ("address", "role") DO UPDATE SET "address" = "RoleBinding"."address"
RETURNING *;

-- name: DeleteRoleBinding :execrows
DELETE FROM "RoleBinding" WHERE "address" = @address AND "role" = @role;
