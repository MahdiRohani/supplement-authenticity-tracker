import {
  Injectable,
  Logger,
  BadRequestException,
  NotFoundException,
} from '@nestjs/common';
import { ProductStatus } from '@prisma/client';
import { ethers } from 'ethers';
import { AuditLogService } from '../../infrastructure/audit/audit-log.service';
import { RelayerService } from '../../infrastructure/blockchain/relayer.service';
import { MemoryTtlCache } from '../../infrastructure/cache/memory-ttl.cache';
import { IpfsService } from '../../infrastructure/ipfs/ipfs.service';
import { PrismaService } from '../../infrastructure/prisma/prisma.service';

type RegisterProductInput = {
  name: string;
  batch?: string;
  manufacturerAddress?: string;
  physicalId?: string;
};

@Injectable()
export class ProductsService {
  private readonly logger = new Logger(ProductsService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly ipfs: IpfsService,
    private readonly relayer: RelayerService,
    private readonly audit: AuditLogService,
    private readonly cache: MemoryTtlCache,
  ) {}

  async registerProduct(input: RegisterProductInput) {
    const secret = ethers.hexlify(ethers.randomBytes(32));
    const secretHash = ethers.keccak256(
      ethers.solidityPacked(['bytes32'], [secret]),
    );
    const physicalId =
      input.physicalId && input.physicalId.startsWith('0x')
        ? input.physicalId
        : ethers.keccak256(
            ethers.toUtf8Bytes(
              `${input.name}:${input.batch ?? ''}:${Date.now()}:${secret}`,
            ),
          );

    const metadata = {
      name: input.name,
      batch: input.batch ?? null,
      schemaVersion: 1,
    };
    const pinned = await this.ipfs.pinJson(metadata);
    const manufacturerAddress = (
      input.manufacturerAddress ??
      '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266'
    ).toLowerCase();

    const placeholderId = `pending-${Date.now()}`;
    let product = await this.prisma.product.create({
      data: {
        chainProductId: placeholderId,
        ownerAddress: manufacturerAddress,
        status: ProductStatus.Created,
        name: input.name,
        batchCode: input.batch ?? null,
        metadataCid: pinned.cid,
        metadataHash: pinned.contentHash,
      },
    });

    let mintedOnChain = false;
    try {
      const minted = await this.relayer.registerUnit({
        manufacturerAddress,
        secretHash,
        metadataCid: pinned.cid,
        metadataHash: pinned.contentHash,
        physicalId,
      });
      product = await this.prisma.product.update({
        where: { id: product.id },
        data: {
          chainProductId: minted.chainProductId,
          ownerAddress: minted.ownerAddress,
        },
      });
      mintedOnChain = true;
    } catch (error) {
      this.logger.warn(
        `On-chain register skipped for ${product.id}: ${String(error)}`,
      );
    }

    await this.audit.record({
      action: 'product.register',
      entityId: product.id,
      actor: manufacturerAddress,
      detail: {
        chainProductId: product.chainProductId,
        mintedOnChain,
        secretHash,
        physicalId,
      },
    });

    return {
      id: product.id,
      chainProductId: product.chainProductId,
      metadataCid: product.metadataCid,
      metadataHash: product.metadataHash,
      metadataGatewayUrl: pinned.gatewayUrl,
      ipfsPinned: pinned.pinned,
      secret,
      secretHash,
      physicalId,
      status: product.status,
      name: product.name,
      batchCode: product.batchCode,
      secretRevealOnce: true,
      mintedOnChain,
    };
  }

  async registerBatch(input: {
    name: string;
    batch?: string;
    count: number;
    manufacturerAddress?: string;
  }) {
    const count = Math.floor(input.count);
    if (!Number.isFinite(count) || count < 1 || count > 100) {
      throw new BadRequestException('count must be between 1 and 100');
    }
    const manufacturerAddress = (
      input.manufacturerAddress ??
      '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266'
    ).toLowerCase();

    const secrets: string[] = [];
    const secretHashes: string[] = [];
    const physicalIds: string[] = [];
    for (let i = 0; i < count; i += 1) {
      const secret = ethers.hexlify(ethers.randomBytes(32));
      secrets.push(secret);
      secretHashes.push(
        ethers.keccak256(ethers.solidityPacked(['bytes32'], [secret])),
      );
      physicalIds.push(
        ethers.keccak256(
          ethers.toUtf8Bytes(
            `${input.name}:${input.batch ?? ''}:${Date.now()}:${i}:${secret}`,
          ),
        ),
      );
    }

    const metadata = {
      name: input.name,
      batch: input.batch ?? null,
      schemaVersion: 1,
      count,
    };
    const pinned = await this.ipfs.pinJson(metadata);

    let mintedOnChain = false;
    let firstChainId: string | null = null;
    let txHash: string | null = null;
    try {
      const minted = await this.relayer.registerBatch({
        manufacturerAddress,
        secretHashes,
        metadataCid: pinned.cid,
        metadataHash: pinned.contentHash,
        physicalIds,
      });
      mintedOnChain = true;
      firstChainId = minted.firstChainProductId;
      txHash = minted.txHash;
    } catch (error) {
      this.logger.warn(`On-chain batch register skipped: ${String(error)}`);
    }

    const created = [];
    for (let i = 0; i < count; i += 1) {
      const chainProductId =
        mintedOnChain && firstChainId
          ? String(BigInt(firstChainId) + BigInt(i))
          : `pending-batch-${Date.now()}-${i}`;
      const product = await this.prisma.product.create({
        data: {
          chainProductId,
          ownerAddress: manufacturerAddress,
          status: ProductStatus.Created,
          name: input.name,
          batchCode: input.batch ?? null,
          metadataCid: pinned.cid,
          metadataHash: pinned.contentHash,
        },
      });
      created.push({
        id: product.id,
        chainProductId: product.chainProductId,
        secret: secrets[i],
        secretHash: secretHashes[i],
        physicalId: physicalIds[i],
      });
    }

    await this.audit.record({
      action: 'product.register_batch',
      actor: manufacturerAddress,
      detail: {
        count,
        mintedOnChain,
        txHash,
        firstChainProductId: firstChainId,
      },
    });

    return {
      count: created.length,
      metadataCid: pinned.cid,
      metadataHash: pinned.contentHash,
      mintedOnChain,
      txHash,
      secretRevealOnce: true,
      items: created,
    };
  }

  async listProducts(query: {
    owner?: string;
    status?: string;
    q?: string;
    page?: number;
    limit?: number;
  }) {
    const page = Math.max(1, Number(query.page ?? 1));
    const limit = Math.min(100, Math.max(1, Number(query.limit ?? 20)));
    const where: Record<string, unknown> = {};
    if (query.owner) {
      where.ownerAddress = query.owner.toLowerCase();
    }
    if (query.status && Object.values(ProductStatus).includes(query.status as ProductStatus)) {
      where.status = query.status as ProductStatus;
    }
    if (query.q?.trim()) {
      const q = query.q.trim();
      where.OR = [
        { name: { contains: q, mode: 'insensitive' } },
        { batchCode: { contains: q, mode: 'insensitive' } },
        { chainProductId: { contains: q } },
        { id: { contains: q } },
      ];
    }

    const [total, items] = await Promise.all([
      this.prisma.product.count({ where }),
      this.prisma.product.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (page - 1) * limit,
        take: limit,
        select: {
          id: true,
          chainProductId: true,
          ownerAddress: true,
          status: true,
          name: true,
          batchCode: true,
          metadataCid: true,
          createdAt: true,
        },
      }),
    ]);

    return {
      page,
      limit,
      total,
      totalPages: Math.max(1, Math.ceil(total / limit)),
      items: items.map((item) => ({
        ...item,
        createdAt: item.createdAt.toISOString(),
      })),
    };
  }

  async transferProduct(id: string, toAddress: string) {
    const result = await this.relayer.transferOwnership(id, toAddress);
    await this.audit.record({
      action: 'product.transfer',
      entityId: id,
      actor: result.fromAddress,
      detail: result,
    });
    this.cache.delete(`verify:${id}`);
    this.cache.delete(`verify:${result.chainProductId}`);
    return result;
  }

  async consumeProduct(id: string, secret: string) {
    const result = await this.relayer.consume(id, secret);
    await this.prisma.product.update({
      where: { id: result.productId },
      data: { status: ProductStatus.Consumed },
    });
    this.cache.delete(`verify:${id}`);
    this.cache.delete(`verify:${result.chainProductId}`);
    await this.audit.record({
      action: 'product.consume',
      entityId: result.productId,
      actor: result.actor,
      detail: {
        chainProductId: result.chainProductId,
        txHash: result.txHash,
      },
    });
    return result;
  }

  async getByChainProductId(chainProductId: string) {
    const product = await this.prisma.product.findUnique({
      where: { chainProductId },
    });
    if (!product) {
      throw new NotFoundException(`Product ${chainProductId} not found`);
    }
    return product;
  }

  async getOwnershipHistory(id: string) {
    const started = Date.now();
    const product = await this.prisma.product.findFirst({
      where: {
        OR: [{ chainProductId: id }, { id }],
      },
    });
    if (!product) {
      throw new NotFoundException(`Product ${id} not found`);
    }

    const events = await this.prisma.ownershipEvent.findMany({
      where: { productId: product.id },
      orderBy: [{ blockNumber: 'asc' }, { createdAt: 'asc' }],
      select: {
        id: true,
        fromAddress: true,
        toAddress: true,
        txHash: true,
        blockNumber: true,
        createdAt: true,
      },
    });

    return {
      productId: product.id,
      chainProductId: product.chainProductId,
      currentOwner: product.ownerAddress,
      status: product.status,
      elapsedMs: Date.now() - started,
      events: events.map((event) => ({
        id: event.id,
        fromAddress: event.fromAddress,
        toAddress: event.toAddress,
        txHash: event.txHash,
        blockNumber: event.blockNumber.toString(),
        createdAt: event.createdAt.toISOString(),
      })),
    };
  }

  async buildBatchLabelsPdf(batchCode: string): Promise<Buffer> {
    const products = await this.prisma.product.findMany({
      where: { batchCode },
      orderBy: { createdAt: 'asc' },
      take: 500,
      select: {
        chainProductId: true,
        name: true,
        batchCode: true,
        status: true,
      },
    });
    if (products.length === 0) {
      throw new NotFoundException(`No products for batch ${batchCode}`);
    }

    const lines = [
      'Supplement batch labels',
      `Batch: ${batchCode}`,
      `Units: ${products.length}`,
      '',
      ...products.map((product) => {
        const qr = JSON.stringify({
          v: 1,
          productId: product.chainProductId,
          chainId: 31337,
        });
        return `${product.name ?? 'Product'} | id=${product.chainProductId} | status=${product.status} | ${qr}`;
      }),
    ];
    return this.renderSimplePdf(lines);
  }

  private renderSimplePdf(lines: string[]): Buffer {
    const escaped = lines
      .map((line) =>
        line.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)'),
      )
      .join('\n');
    const content = `BT /F1 11 Tf 50 780 Td 14 TL (${escaped.replace(/\n/g, ') Tj T* (')}) Tj ET`;
    const objects = [
      '1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n',
      '2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n',
      '3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources<< /Font<< /F1 5 0 R >> >> >>endobj\n',
      `4 0 obj<< /Length ${Buffer.byteLength(content)} >>stream\n${content}\nendstream\nendobj\n`,
      '5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n',
    ];
    let pdf = '%PDF-1.4\n';
    const offsets = [0];
    for (const object of objects) {
      offsets.push(Buffer.byteLength(pdf));
      pdf += object;
    }
    const xrefStart = Buffer.byteLength(pdf);
    pdf += `xref\n0 ${objects.length + 1}\n`;
    pdf += '0000000000 65535 f \n';
    for (let i = 1; i < offsets.length; i += 1) {
      pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
    }
    pdf += `trailer<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF`;
    return Buffer.from(pdf);
  }
}
