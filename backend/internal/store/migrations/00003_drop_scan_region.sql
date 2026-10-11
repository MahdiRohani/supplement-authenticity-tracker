-- Scan regions were optional and self-reported by buyers; clone detection
-- now relies on device and consumption signals only. RoleBinding.region
-- stays as the custodian's public profile.

-- +goose Up
ALTER TABLE "ScanEvent" DROP COLUMN "region";

-- +goose Down
ALTER TABLE "ScanEvent" ADD COLUMN "region" TEXT;
