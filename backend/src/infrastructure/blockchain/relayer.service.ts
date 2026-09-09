import {
  BadRequestException,
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Contract, JsonRpcProvider, Wallet } from 'ethers';
import * as fs from 'fs';
import * as path from 'path';
import { PrismaService } from '../prisma/prisma.service';
import { RelayerKeyStore } from './relayer-key.store';

type RegistryArtifact = {
  address?: string;
  abi: unknown[];
  abiVersion?: string;
};

@Injectable()
export class RelayerService {
  constructor(
    private readonly config: ConfigService,
    private readonly prisma: PrismaService,
    private readonly keys: RelayerKeyStore,
  ) {}

  async registerUnit(input: {
    manufacturerAddress: string;
    secretHash: string;
    metadataCid: string;
    metadataHash: string;
    physicalId: string;
  }) {
    const manufacturer = input.manufacturerAddress.toLowerCase();
    const manufacturerKey = this.keys.resolveKey(manufacturer);
    if (!manufacturerKey) {
      throw new BadRequestException(
        `No relayer key configured for manufacturer ${manufacturer}`,
      );
    }

    const { contract, wallet } = this.connectWallet(manufacturerKey);
    const tx = await contract.registerUnit(
      input.secretHash,
      input.metadataCid,
      input.metadataHash,
      input.physicalId,
    );
    const receipt = await tx.wait();
    const productId = await this.readRegisteredProductId(contract, receipt);
    return {
      chainProductId: productId,
      ownerAddress: wallet.address.toLowerCase(),
      txHash: receipt.hash,
      blockNumber: receipt.blockNumber,
    };
  }

  async registerBatch(input: {
    manufacturerAddress: string;
    secretHashes: string[];
    metadataCid: string;
    metadataHash: string;
    physicalIds: string[];
  }) {
    const manufacturer = input.manufacturerAddress.toLowerCase();
    const manufacturerKey = this.keys.resolveKey(manufacturer);
    if (!manufacturerKey) {
      throw new BadRequestException(
        `No relayer key configured for manufacturer ${manufacturer}`,
      );
    }
    if (input.secretHashes.length === 0) {
      throw new BadRequestException('secretHashes required');
    }
    if (input.secretHashes.length !== input.physicalIds.length) {
      throw new BadRequestException('secretHashes and physicalIds length mismatch');
    }

    const { contract, wallet } = this.connectWallet(manufacturerKey);
    const tx = await contract.registerBatch(
      input.secretHashes,
      input.metadataCid,
      input.metadataHash,
      input.physicalIds,
    );
    const receipt = await tx.wait();
    const firstChainProductId = await this.readRegisteredProductId(
      contract,
      receipt,
    );
    return {
      firstChainProductId,
      count: input.secretHashes.length,
      ownerAddress: wallet.address.toLowerCase(),
      txHash: receipt.hash,
      blockNumber: receipt.blockNumber,
    };
  }

  async transferOwnership(chainProductId: string, toAddress: string) {
    if (!toAddress?.startsWith('0x')) {
      throw new BadRequestException('toAddress must be a hex address');
    }

    const product = await this.findProduct(chainProductId);
    const ownerKey = this.keys.resolveKey(product.ownerAddress);
    if (!ownerKey) {
      throw new BadRequestException(
        `No relayer key configured for owner ${product.ownerAddress}`,
      );
    }

    const { contract } = this.connectWallet(ownerKey);
    try {
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
    } catch (error) {
      this.rethrowChainError(error);
    }
  }

  async consume(chainProductId: string, secret: string) {
    if (!secret?.startsWith('0x') || secret.length !== 66) {
      throw new BadRequestException('secret must be a bytes32 hex string');
    }

    const product = await this.findProduct(chainProductId);
    if (product.status === 'Consumed') {
      throw new ConflictException(
        'Product already consumed; refill is not allowed',
      );
    }

    const actorKey =
      this.keys.resolveKey(product.ownerAddress) ||
      Object.values(this.keys.getActiveMap())[0];
    if (!actorKey) {
      throw new BadRequestException('No relayer key configured for consume');
    }

    const { contract, wallet } = this.connectWallet(actorKey);
    try {
      const tx = await contract.consume(BigInt(product.chainProductId), secret);
      const receipt = await tx.wait();
      return {
        chainProductId: product.chainProductId,
        productId: product.id,
        status: 'Consumed',
        actor: wallet.address.toLowerCase(),
        txHash: receipt.hash,
        blockNumber: receipt.blockNumber,
      };
    } catch (error) {
      this.rethrowChainError(error, product.chainProductId);
    }
  }

  private async findProduct(id: string) {
    const product = await this.prisma.product.findFirst({
      where: {
        OR: [{ chainProductId: id }, { id }],
      },
    });
    if (!product) {
      throw new NotFoundException(`Product ${id} not found`);
    }
    if (!/^\d+$/.test(product.chainProductId)) {
      throw new BadRequestException(
        `Product ${id} is not minted on-chain yet`,
      );
    }
    return product;
  }

  private connectWallet(privateKey: string) {
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
    const wallet = new Wallet(privateKey, provider);
    const contract = new Contract(address, artifact.abi as never, wallet);
    return { contract, wallet, artifact };
  }

  private async readRegisteredProductId(
    contract: Contract,
    receipt: { logs: readonly { topics: readonly string[]; data: string }[] },
  ) {
    for (const log of receipt.logs) {
      try {
        const parsed = contract.interface.parseLog({
          topics: [...log.topics],
          data: log.data,
        });
        if (parsed?.name === 'ProductRegistered') {
          return parsed.args.productId.toString();
        }
      } catch {
        continue;
      }
    }
    const next = await contract.nextProductId();
    return next.toString();
  }

  private rethrowChainError(error: unknown, productId?: string): never {
    const message = String(error);
    if (message.includes('ProductAlreadyConsumed')) {
      throw new ConflictException(
        'Product already consumed; refill is not allowed',
      );
    }
    if (message.includes('InvalidSecret')) {
      throw new BadRequestException('Invalid scratch secret');
    }
    if (message.includes('ProductNotConsumable')) {
      throw new BadRequestException(
        `Product ${productId ?? ''} is not consumable in its current status`,
      );
    }
    if (message.includes('EnforcedPause')) {
      throw new BadRequestException('Registry is paused');
    }
    throw error;
  }

  private loadArtifact(): RegistryArtifact {
    const configured = this.config.get<string>('REGISTRY_ABI_PATH');
    const abiPath = configured
      ? path.resolve(process.cwd(), configured)
      : path.resolve(process.cwd(), '../packages/abis/SupplementRegistry.json');
    return JSON.parse(fs.readFileSync(abiPath, 'utf8')) as RegistryArtifact;
  }
}
