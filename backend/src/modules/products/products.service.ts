import { Injectable, NotFoundException } from '@nestjs/common';
import { ProductStatus } from '@prisma/client';
import { ethers } from 'ethers';
import { IpfsService } from '../../infrastructure/ipfs/ipfs.service';
import { PrismaService } from '../../infrastructure/prisma/prisma.service';

type RegisterProductInput = {
  name: string;
  batch?: string;
  manufacturerAddress?: string;
};

@Injectable()
export class ProductsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ipfs: IpfsService,
  ) {}

  async registerProduct(input: RegisterProductInput) {
    const secret = ethers.hexlify(ethers.randomBytes(32));
    const secretHash = ethers.keccak256(
      ethers.solidityPacked(['bytes32'], [secret]),
    );

    const metadata = {
      name: input.name,
      batch: input.batch ?? null,
      schemaVersion: 1,
    };
    const pinned = await this.ipfs.pinJson(metadata);

    const placeholderId = `pending-${Date.now()}`;
    const product = await this.prisma.product.create({
      data: {
        chainProductId: placeholderId,
        ownerAddress: (input.manufacturerAddress ?? '0x0').toLowerCase(),
        status: ProductStatus.Created,
        metadataCid: pinned.cid,
        metadataHash: pinned.contentHash,
      },
    });

    return {
      id: product.id,
      chainProductId: product.chainProductId,
      metadataCid: product.metadataCid,
      metadataHash: product.metadataHash,
      secret,
      secretHash,
      status: product.status,
    };
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
