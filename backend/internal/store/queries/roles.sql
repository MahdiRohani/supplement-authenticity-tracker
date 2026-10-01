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

-- Unlike UpsertRoleBinding, a repeated bind updates the profile fields that
-- were provided and keeps the others.
-- name: UpsertRoleBindingProfile :one
INSERT INTO "RoleBinding" ("id", "address", "role", "displayName", "region", "createdAt", "updatedAt")
VALUES (@id, @address, @role, sqlc.narg('display_name'), sqlc.narg('region'), @now, @now)
ON CONFLICT ("address", "role") DO UPDATE SET
    "displayName" = COALESCE(EXCLUDED."displayName", "RoleBinding"."displayName"),
    "region" = COALESCE(EXCLUDED."region", "RoleBinding"."region"),
    "updatedAt" = EXCLUDED."updatedAt"
RETURNING *;

-- The profile shown for a custodian: a Pharmacy binding wins, then any
-- binding with a display name.
-- name: GetPartyProfile :one
SELECT "address", "role", "displayName", "region" FROM "RoleBinding"
WHERE "address" = @address
ORDER BY ("role" = 'Pharmacy') DESC, ("displayName" IS NOT NULL) DESC, "role" ASC
LIMIT 1;

-- name: DeleteRoleBinding :execrows
DELETE FROM "RoleBinding" WHERE "address" = @address AND "role" = @role;
