import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { AuditLogService } from '../audit/audit-log.service';

@Injectable()
export class RelayerKeyStore {
  private readonly logger = new Logger(RelayerKeyStore.name);
  private active: Record<string, string> = {};
  private previous: Record<string, string> = {};

  constructor(
    private readonly config: ConfigService,
    private readonly audit: AuditLogService,
  ) {
    this.reloadFromEnv('bootstrap');
  }

  getActiveMap(): Record<string, string> {
    return { ...this.active };
  }

  resolveKey(address: string): string | undefined {
    const normalized = address.toLowerCase();
    return this.active[normalized] ?? this.previous[normalized];
  }

  reloadFromEnv(reason = 'manual'): Record<string, string> {
    this.previous = this.active;
    this.active = this.parse(this.config.get<string>('RELAYER_KEYS_JSON') ?? '{}');
    const previousJson = this.config.get<string>('RELAYER_KEYS_PREVIOUS_JSON');
    if (previousJson) {
      this.previous = {
        ...this.previous,
        ...this.parse(previousJson),
      };
    }
    this.logger.log(
      `Relayer keys reloaded (${reason}): active=${Object.keys(this.active).length} previous=${Object.keys(this.previous).length}`,
    );
    void this.audit.record({
      action: 'relayer.keys.reload',
      detail: {
        reason,
        activeCount: Object.keys(this.active).length,
        previousCount: Object.keys(this.previous).length,
      },
    });
    return this.getActiveMap();
  }

  private parse(raw: string): Record<string, string> {
    const parsed = JSON.parse(raw) as Record<string, string>;
    return Object.fromEntries(
      Object.entries(parsed).map(([address, key]) => [
        address.toLowerCase(),
        key,
      ]),
    );
  }
}
