import { Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ProductStatus } from '@prisma/client';
import { Contract, JsonRpcProvider } from 'ethers';
import * as fs from 'fs';
import * as path from 'path';
import { MemoryTtlCache } from '../../infrastructure/cache/memory-ttl.cache';
import { IpfsService } from '../../infrastructure/ipfs/ipfs.service';
import { PrismaService } from '../../infrastructure/prisma/prisma.service';

export type VerifyResult = {
  productId: string;
  chainProductId: string;
  status: string;
  authenticity: string;
  currentOwner: string;
  metadataCid: string | null;
  metadataHash: string | null;
  metadata: {
    name?: string;
    batch?: string;
    expiresAt?: string;
    image?: string;
  } | null;
  cached: boolean;
  source: 'db' | 'chain';
};

@Injectable()
export class VerifyService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly cache: MemoryTtlCache,
    private readonly ipfs: IpfsService,
    private readonly config: ConfigService,
  ) {}

  async verify(id: string): Promise<VerifyResult> {
    const cacheKey = `verify:${id}`;
    const cached = this.cache.get<VerifyResult>(cacheKey);
    if (cached) {
      return { ...cached, cached: true };
    }

    let result = await this.verifyFromDb(id);
    if (!result) {
      result = await this.verifyFromChain(id);
    }
    if (!result) {
      throw new NotFoundException(`Product ${id} not found`);
    }

    if (result.metadataCid) {
      result.metadata = await this.ipfs.resolveJson(result.metadataCid);
    }

    const ttlMs = Number(this.config.get('VERIFY_CACHE_TTL_MS') ?? 15_000);
    this.cache.set(cacheKey, { ...result, cached: false }, ttlMs);
    return result;
  }

  private async verifyFromDb(id: string): Promise<VerifyResult | null> {
    const product = await this.prisma.product.findFirst({
      where: {
        OR: [{ chainProductId: id }, { id }],
      },
    });
    if (!product) {
      return null;
    }
    return {
      productId: product.id,
      chainProductId: product.chainProductId,
      status: product.status,
      authenticity: this.mapAuthenticity(product.status),
      currentOwner: product.ownerAddress,
      metadataCid: product.metadataCid,
      metadataHash: product.metadataHash,
      metadata: null,
      cached: false,
      source: 'db',
    };
  }

  private async verifyFromChain(id: string): Promise<VerifyResult | null> {
    const rpcUrl = this.config.get<string>('RPC_URL');
    const artifact = this.loadArtifact();
    const address =
      this.config.get<string>('REGISTRY_ADDRESS') || artifact.address;
    if (!rpcUrl || !address || !/^\d+$/.test(id)) {
      return null;
    }

    try {
      const provider = new JsonRpcProvider(rpcUrl);
      const contract = new Contract(address, artifact.abi as never, provider);
      const view = await contract.getProductStatus(BigInt(id));
      const status = this.mapChainStatus(Number(view.status));
      return {
        productId: id,
        chainProductId: id,
        status,
        authenticity: this.mapAuthenticity(status as ProductStatus),
        currentOwner: String(view.currentOwner).toLowerCase(),
        metadataCid: String(view.metadataCid),
        metadataHash: null,
        metadata: null,
        cached: false,
        source: 'chain',
      };
    } catch {
      return null;
    }
  }

  private mapAuthenticity(status: ProductStatus | string): string {
    switch (status) {
      case ProductStatus.Created:
      case ProductStatus.Transferred:
      case ProductStatus.AtPointOfSale:
      case 'Created':
      case 'Transferred':
      case 'AtPointOfSale':
        return 'Authentic';
      case ProductStatus.Consumed:
      case 'Consumed':
        return 'Consumed';
      case ProductStatus.Invalid:
      case 'Invalid':
        return 'Invalid';
      default:
        return 'NotFound';
    }
  }

  private mapChainStatus(value: number): string {
    return (
      [
        ProductStatus.Created,
        ProductStatus.Transferred,
        ProductStatus.AtPointOfSale,
        ProductStatus.Consumed,
        ProductStatus.Invalid,
      ][value] ?? 'Invalid'
    );
  }

  private loadArtifact(): { address?: string; abi: unknown[] } {
    const configured = this.config.get<string>('REGISTRY_ABI_PATH');
    const abiPath = configured
      ? path.resolve(process.cwd(), configured)
      : path.resolve(process.cwd(), '../packages/abis/SupplementRegistry.json');
    return JSON.parse(fs.readFileSync(abiPath, 'utf8')) as {
      address?: string;
      abi: unknown[];
    };
  }
}
