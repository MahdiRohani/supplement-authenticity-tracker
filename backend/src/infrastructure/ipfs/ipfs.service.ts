import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHash } from 'crypto';

export type ResolvedMetadata = {
  name?: string;
  batch?: string;
  expiresAt?: string;
  image?: string;
};

@Injectable()
export class IpfsService {
  private readonly logger = new Logger(IpfsService.name);
  private readonly localStore = new Map<string, string>();

  constructor(private readonly config: ConfigService) {}

  async pinJson(payload: Record<string, unknown>): Promise<{
    cid: string;
    contentHash: string;
  }> {
    const apiUrl =
      this.config.get<string>('IPFS_API_URL') ?? 'http://127.0.0.1:5001';
    const body = JSON.stringify(payload);
    const contentHash = `0x${createHash('sha256').update(body).digest('hex')}`;

    try {
      const form = new FormData();
      form.append(
        'file',
        new Blob([body], { type: 'application/json' }),
        'metadata.json',
      );
      const response = await fetch(`${apiUrl}/api/v0/add?pin=true`, {
        method: 'POST',
        body: form,
      });
      if (!response.ok) {
        throw new Error(`IPFS add failed: ${response.status}`);
      }
      const result = (await response.json()) as { Hash: string };
      this.localStore.set(result.Hash, body);
      return { cid: result.Hash, contentHash };
    } catch (error) {
      this.logger.warn(`IPFS unavailable, using local cid stub: ${String(error)}`);
      const digest = createHash('sha256').update(body).digest('hex').slice(0, 46);
      const cid = `bafy${digest}`;
      this.localStore.set(cid, body);
      return {
        cid,
        contentHash,
      };
    }
  }

  async resolveJson(cid: string): Promise<ResolvedMetadata | null> {
    const local = this.localStore.get(cid);
    if (local) {
      return JSON.parse(local) as ResolvedMetadata;
    }

    const gateway =
      this.config.get<string>('IPFS_GATEWAY_URL') ??
      'https://ipfs.io/ipfs';
    try {
      const response = await fetch(`${gateway.replace(/\/$/, '')}/${cid}`);
      if (!response.ok) {
        return null;
      }
      return (await response.json()) as ResolvedMetadata;
    } catch (error) {
      this.logger.warn(`IPFS resolve failed for ${cid}: ${String(error)}`);
      return null;
    }
  }
}
