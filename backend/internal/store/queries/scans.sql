-- name: InsertScanEvent :exec
INSERT INTO "ScanEvent" ("id", "batchId", "index", "deviceHash", "statusAtScan", "riskScore", "createdAt")
VALUES (@id, @batch_id, @index, @device_hash, @status_at_scan, @risk_score, @now);

-- A scan is stored before it is scored so its own signal counts.
-- name: SetScanRisk :exec
UPDATE "ScanEvent" SET "riskScore" = @risk_score, "statusAtScan" = @status_at_scan WHERE "id" = @id;

-- Aggregates the clone-detection signals of one unit (see internal/risk,
-- whose Signals/FromHistory compute the same values in memory) and the scan
-- history shown to the buyer, relative to the scanning device.
-- name: UnitScanSignals :one
WITH s AS (
    SELECT "deviceHash", "createdAt" FROM "ScanEvent"
    WHERE "batchId" = @batch_id AND "index" = @index
),
before_consume AS (
    SELECT DISTINCT "deviceHash" FROM s
    WHERE sqlc.narg('consumed_at')::timestamp IS NULL OR "createdAt" <= sqlc.narg('consumed_at')::timestamp
)
SELECT
    (SELECT count(*) FROM s)::integer AS total_scans,
    (SELECT count(*) FROM before_consume)::integer AS devices_before_consume,
    (SELECT count(*) FROM s WHERE "createdAt" > sqlc.narg('consumed_at')::timestamp)::integer AS scans_after_consume,
    (SELECT count(DISTINCT "deviceHash") FROM s
        WHERE "createdAt" > sqlc.narg('consumed_at')::timestamp
          AND "deviceHash" NOT IN (SELECT "deviceHash" FROM before_consume))::integer AS new_devices_after_consume,
    (SELECT count(DISTINCT "deviceHash") FROM s WHERE "deviceHash" <> @device_hash::text)::integer AS other_devices,
    (SELECT min("createdAt") FROM s)::timestamp AS first_scan_at;

-- name: ListSuspiciousUnits :many
SELECT
    "batchId",
    "index",
    max("riskScore")::double precision AS max_risk,
    count(*)::integer AS scans,
    count(DISTINCT "deviceHash")::integer AS devices,
    max("createdAt")::timestamp AS last_scan_at
FROM "ScanEvent"
WHERE "createdAt" >= @since
GROUP BY "batchId", "index"
HAVING max("riskScore") >= @min_risk::double precision
ORDER BY max_risk DESC, last_scan_at DESC
LIMIT @max_rows;
