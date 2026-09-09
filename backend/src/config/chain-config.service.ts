import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as fs from 'fs';
import * as path from 'path';

export type ChainDeployment = {
  network: string;
  contractName: string;
  abiVersion: string;
  address: string;
};

@Injectable()
export class ChainConfigService {
  private readonly deployments: Record<string, ChainDeployment>;

  constructor(private readonly config: ConfigService) {
    this.deployments = this.loadDeployments();
  }

  getChainId(): number {
    const configured = this.config.get<string>('CHAIN_ID');
    if (configured) {
      return Number(configured);
    }
    return 31337;
  }

  getActiveDeployment(): ChainDeployment | null {
    const entry = this.deployments[String(this.getChainId())];
    if (!entry) {
      return null;
    }
    return entry;
  }

  resolveRegistryAddress(): string {
    const envAddress = this.config.get<string>('REGISTRY_ADDRESS')?.trim();
    if (envAddress) {
      return envAddress;
    }
    return this.getActiveDeployment()?.address?.trim() || '';
  }

  listDeployments() {
    return this.deployments;
  }

  private loadDeployments(): Record<string, ChainDeployment> {
    const configured = this.config.get<string>('DEPLOYMENTS_PATH');
    const filePath = configured
      ? path.resolve(process.cwd(), configured)
      : path.resolve(process.cwd(), '../packages/abis/deployments.json');
    try {
      const raw = fs.readFileSync(filePath, 'utf8');
      return JSON.parse(raw) as Record<string, ChainDeployment>;
    } catch {
      return {};
    }
  }
}
