import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHash } from 'crypto';

@Injectable()
export class IpfsService {
  private readonly logger = new Logger(IpfsService.name);

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
      return { cid: result.Hash, contentHash };
    } catch (error) {
      this.logger.warn(`IPFS unavailable, using local cid stub: ${String(error)}`);
      const digest = createHash('sha256').update(body).digest('hex').slice(0, 46);
      return {
        cid: `bafy${digest}`,
        contentHash,
      };
    }
  }
}
