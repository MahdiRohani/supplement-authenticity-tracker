-- Mirrors the schema previously managed by Prisma (`prisma db push`), including
-- quoted camelCase identifiers and constraint/index names, so databases created
-- by the NestJS backend can be adopted as-is (see store.baselinePrismaSchema).

-- +goose Up
CREATE TYPE "ProductStatus" AS ENUM ('Created', 'Transferred', 'AtPointOfSale', 'Consumed', 'Invalid');

CREATE TYPE "SupplyRole" AS ENUM ('Manufacturer', 'Distributor', 'Pharmacy', 'Admin');

CREATE TABLE "Product" (
    "id" TEXT NOT NULL,
    "chainProductId" TEXT NOT NULL,
    "ownerAddress" TEXT NOT NULL,
    "status" "ProductStatus" NOT NULL,
    "name" TEXT,
    "batchCode" TEXT,
    "metadataCid" TEXT,
    "metadataHash" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "Product_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "OwnershipEvent" (
    "id" TEXT NOT NULL,
    "productId" TEXT NOT NULL,
    "chainProductId" TEXT NOT NULL,
    "fromAddress" TEXT NOT NULL,
    "toAddress" TEXT NOT NULL,
    "txHash" TEXT NOT NULL,
    "blockNumber" BIGINT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "OwnershipEvent_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "RoleBinding" (
    "id" TEXT NOT NULL,
    "address" TEXT NOT NULL,
    "role" "SupplyRole" NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "RoleBinding_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "AuditLog" (
    "id" TEXT NOT NULL,
    "action" TEXT NOT NULL,
    "entityId" TEXT,
    "actor" TEXT,
    "detail" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AuditLog_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "CounterfeitReport" (
    "id" TEXT NOT NULL,
    "chainProductId" TEXT NOT NULL,
    "note" TEXT,
    "reporter" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CounterfeitReport_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "AnalyticsCounter" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "value" BIGINT NOT NULL DEFAULT 0,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "AnalyticsCounter_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Product_chainProductId_key" ON "Product"("chainProductId");
CREATE INDEX "Product_ownerAddress_idx" ON "Product"("ownerAddress");
CREATE INDEX "Product_status_idx" ON "Product"("status");
CREATE INDEX "Product_name_idx" ON "Product"("name");
CREATE INDEX "Product_batchCode_idx" ON "Product"("batchCode");

CREATE INDEX "OwnershipEvent_chainProductId_idx" ON "OwnershipEvent"("chainProductId");
CREATE INDEX "OwnershipEvent_txHash_idx" ON "OwnershipEvent"("txHash");

CREATE UNIQUE INDEX "RoleBinding_address_role_key" ON "RoleBinding"("address", "role");

CREATE INDEX "AuditLog_action_idx" ON "AuditLog"("action");
CREATE INDEX "AuditLog_entityId_idx" ON "AuditLog"("entityId");
CREATE INDEX "AuditLog_createdAt_idx" ON "AuditLog"("createdAt");

CREATE INDEX "CounterfeitReport_chainProductId_idx" ON "CounterfeitReport"("chainProductId");
CREATE INDEX "CounterfeitReport_createdAt_idx" ON "CounterfeitReport"("createdAt");

CREATE UNIQUE INDEX "AnalyticsCounter_name_key" ON "AnalyticsCounter"("name");

ALTER TABLE "OwnershipEvent" ADD CONSTRAINT "OwnershipEvent_productId_fkey"
    FOREIGN KEY ("productId") REFERENCES "Product"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- +goose Down
DROP TABLE "OwnershipEvent";
DROP TABLE "Product";
DROP TABLE "RoleBinding";
DROP TABLE "AuditLog";
DROP TABLE "CounterfeitReport";
DROP TABLE "AnalyticsCounter";
DROP TYPE "SupplyRole";
DROP TYPE "ProductStatus";
