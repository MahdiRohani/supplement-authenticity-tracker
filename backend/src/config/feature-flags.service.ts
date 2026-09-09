import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

export type FeatureFlags = {
  reportsEnabled: boolean;
  scanEnabled: boolean;
  labelsPdfEnabled: boolean;
  analyticsEnabled: boolean;
  eip712MetadataEnabled: boolean;
  metaTxConsumeEnabled: boolean;
  subgraphPreferred: boolean;
};

@Injectable()
export class FeatureFlagsService {
  constructor(private readonly config: ConfigService) {}

  getFlags(): FeatureFlags {
    return {
      reportsEnabled: this.bool('FF_REPORTS', true),
      scanEnabled: this.bool('FF_SCAN', true),
      labelsPdfEnabled: this.bool('FF_LABELS_PDF', true),
      analyticsEnabled: this.bool('FF_ANALYTICS', true),
      eip712MetadataEnabled: this.bool('FF_EIP712_METADATA', true),
      metaTxConsumeEnabled: this.bool('FF_META_TX_CONSUME', true),
      subgraphPreferred: this.bool('FF_SUBGRAPH', false),
    };
  }

  private bool(key: string, fallback: boolean): boolean {
    const raw = this.config.get<string>(key);
    if (raw == null || raw === '') {
      return fallback;
    }
    return raw === '1' || raw.toLowerCase() === 'true';
  }
}
