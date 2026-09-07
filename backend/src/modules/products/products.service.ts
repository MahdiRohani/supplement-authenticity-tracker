import {
  Injectable,
  Logger,
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
      secret,
      secretHash,
      physicalId,
      status: product.status,
      secretRevealOnce: true,
      mintedOnChain,
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
}
