import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Contract, JsonRpcProvider, Wallet } from 'ethers';
import * as fs from 'fs';
import * as path from 'path';
import { PrismaService } from '../prisma/prisma.service';

type RegistryArtifact = {
  address?: string;
  abi: unknown[];
};

@Injectable()
export class RelayerService {
  constructor(
    private readonly config: ConfigService,
    private readonly prisma: PrismaService,
  ) {}

  async transferOwnership(chainProductId: string, toAddress: string) {
    if (!toAddress?.startsWith('0x')) {
      throw new BadRequestException('toAddress must be a hex address');
    }

    const product = await this.prisma.product.findFirst({
      where: {
        OR: [{ chainProductId }, { id: chainProductId }],
      },
    });
    if (!product) {
      throw new NotFoundException(`Product ${chainProductId} not found`);
    }

    const keyMap = this.loadKeyMap();
    const ownerKey = keyMap[product.ownerAddress.toLowerCase()];
    if (!ownerKey) {
      throw new BadRequestException(
        `No relayer key configured for owner ${product.ownerAddress}`,
      );
    }

    const rpcUrl = this.config.get<string>('RPC_URL');
    if (!rpcUrl) {
      throw new BadRequestException('RPC_URL is not configured');
    }

    const artifact = this.loadArtifact();
    const address =
      this.config.get<string>('REGISTRY_ADDRESS') || artifact.address;
    if (!address) {
      throw new BadRequestException('REGISTRY_ADDRESS is not configured');
    }

    const provider = new JsonRpcProvider(rpcUrl);
    const wallet = new Wallet(ownerKey, provider);
    const contract = new Contract(address, artifact.abi as never, wallet);
    const tx = await contract.transferOwnership(
      BigInt(product.chainProductId),
      toAddress,
    );
    const receipt = await tx.wait();

    return {
      chainProductId: product.chainProductId,
      fromAddress: product.ownerAddress,
      toAddress: toAddress.toLowerCase(),
      txHash: receipt.hash,
      blockNumber: receipt.blockNumber,
    };
  }

  private loadKeyMap(): Record<string, string> {
    const raw = this.config.get<string>('RELAYER_KEYS_JSON') ?? '{}';
    const parsed = JSON.parse(raw) as Record<string, string>;
    return Object.fromEntries(
      Object.entries(parsed).map(([address, key]) => [
        address.toLowerCase(),
        key,
      ]),
    );
  }

  private loadArtifact(): RegistryArtifact {
    const configured = this.config.get<string>('REGISTRY_ABI_PATH');
    const abiPath = configured
      ? path.resolve(process.cwd(), configured)
      : path.resolve(process.cwd(), '../packages/abis/SupplementRegistry.json');
    return JSON.parse(fs.readFileSync(abiPath, 'utf8')) as RegistryArtifact;
  }
}
