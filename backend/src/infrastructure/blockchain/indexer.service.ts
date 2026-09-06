import { InterfaceAbi } from 'ethers';
import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ProductStatus } from '@prisma/client';
import { Contract, JsonRpcProvider, Log, id } from 'ethers';
import * as fs from 'fs';
import * as path from 'path';
import { PrismaService } from '../prisma/prisma.service';

type RegistryArtifact = {
  address?: string;
  abi: InterfaceAbi;
};

@Injectable()
export class IndexerService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(IndexerService.name);
  private provider?: JsonRpcProvider;
  private contract?: Contract;
  private polling?: ReturnType<typeof setInterval>;
  private lastBlock = 0n;
  private readonly productRegisteredTopic = id(
    'ProductRegistered(uint256,address,uint8,string,bytes32)',
  );
  private readonly ownershipTransferredTopic = id(
    'OwnershipTransferred(uint256,address,address)',
  );

  constructor(
    private readonly config: ConfigService,
    private readonly prisma: PrismaService,
  ) {}

  async onModuleInit() {
    const rpcUrl = this.config.get<string>('RPC_URL');
    const address =
      this.config.get<string>('REGISTRY_ADDRESS') ||
      this.loadArtifact().address;
    if (!rpcUrl || !address) {
      this.logger.warn('Indexer disabled: RPC_URL or REGISTRY_ADDRESS missing');
      return;
    }

    this.provider = new JsonRpcProvider(rpcUrl);
    this.contract = new Contract(address, this.loadArtifact().abi, this.provider);
    this.lastBlock = BigInt(await this.provider.getBlockNumber());
    this.polling = setInterval(() => {
      void this.poll();
    }, 3000);
    this.logger.log(`Indexer listening on ${address}`);
  }

  onModuleDestroy() {
    if (this.polling) {
      clearInterval(this.polling);
    }
  }

  private loadArtifact(): RegistryArtifact {
    const configured = this.config.get<string>('REGISTRY_ABI_PATH');
    const abiPath = configured
      ? path.resolve(process.cwd(), configured)
      : path.resolve(process.cwd(), '../packages/abis/SupplementRegistry.json');
    const raw = fs.readFileSync(abiPath, 'utf8');
    return JSON.parse(raw) as RegistryArtifact;
  }

  private async poll() {
    if (!this.provider || !this.contract) {
      return;
    }

    try {
      const latest = BigInt(await this.provider.getBlockNumber());
      if (latest <= this.lastBlock) {
        return;
      }

      const fromBlock = Number(this.lastBlock + 1n);
      const toBlock = Number(latest);
      const address = await this.contract.getAddress();

      const [registeredLogs, transferredLogs] = await Promise.all([
        this.provider.getLogs({
          address,
          fromBlock,
          toBlock,
          topics: [this.productRegisteredTopic],
        }),
        this.provider.getLogs({
          address,
          fromBlock,
          toBlock,
          topics: [this.ownershipTransferredTopic],
        }),
      ]);

      const ordered = [...registeredLogs, ...transferredLogs].sort((a, b) => {
        if (a.blockNumber !== b.blockNumber) {
          return a.blockNumber - b.blockNumber;
        }
        return a.index - b.index;
      });

      for (const log of ordered) {
        const topic = log.topics[0];
        if (topic === this.productRegisteredTopic) {
          await this.handleProductRegistered(log);
        } else if (topic === this.ownershipTransferredTopic) {
          await this.handleOwnershipTransferred(log);
        }
      }

      this.lastBlock = latest;
    } catch (error) {
      this.logger.error(`Indexer poll failed: ${String(error)}`);
    }
  }

  private async handleProductRegistered(log: Log) {
    if (!this.contract) {
      return;
    }

    const parsed = this.contract.interface.parseLog({
      topics: [...log.topics],
      data: log.data,
    });
    if (!parsed || parsed.name !== 'ProductRegistered') {
      return;
    }

    const productId = parsed.args.productId.toString();
    const manufacturer = String(parsed.args.manufacturer);
    const metadataCid = String(parsed.args.metadataCid);
    const metadataHash = String(parsed.args.metadataHash);
    const status = this.mapStatus(Number(parsed.args.status));

    await this.prisma.product.upsert({
      where: { chainProductId: productId },
      create: {
        chainProductId: productId,
        ownerAddress: manufacturer.toLowerCase(),
        status,
        metadataCid,
        metadataHash,
      },
      update: {
        ownerAddress: manufacturer.toLowerCase(),
        status,
        metadataCid,
        metadataHash,
      },
    });

    this.logger.log(`Indexed ProductRegistered id=${productId}`);
  }

  private async handleOwnershipTransferred(log: Log) {
    if (!this.contract) {
      return;
    }

    const parsed = this.contract.interface.parseLog({
      topics: [...log.topics],
      data: log.data,
    });
    if (!parsed || parsed.name !== 'OwnershipTransferred') {
      return;
    }

    const chainProductId = parsed.args.productId.toString();
    const fromAddress = String(parsed.args.from).toLowerCase();
    const toAddress = String(parsed.args.to).toLowerCase();
    const txHash = log.transactionHash;

    const alreadyIndexed = await this.prisma.ownershipEvent.findFirst({
      where: { txHash, chainProductId },
    });
    if (alreadyIndexed) {
      return;
    }

    const onChain = await this.contract.getProduct(parsed.args.productId);
    const status = this.mapStatus(Number(onChain.status));

    const product = await this.prisma.product.upsert({
      where: { chainProductId },
      create: {
        chainProductId,
        ownerAddress: toAddress,
        status,
      },
      update: {
        ownerAddress: toAddress,
        status,
      },
    });

    await this.prisma.ownershipEvent.create({
      data: {
        productId: product.id,
        chainProductId,
        fromAddress,
        toAddress,
        txHash,
        blockNumber: BigInt(log.blockNumber),
      },
    });

    this.logger.log(
      `Indexed OwnershipTransferred id=${chainProductId} ${fromAddress} -> ${toAddress}`,
    );
  }

  private mapStatus(value: number): ProductStatus {
    switch (value) {
      case 0:
        return ProductStatus.Created;
      case 1:
        return ProductStatus.Transferred;
      case 2:
        return ProductStatus.AtPointOfSale;
      case 3:
        return ProductStatus.Consumed;
      case 4:
        return ProductStatus.Invalid;
      default:
        return ProductStatus.Created;
    }
  }
}
