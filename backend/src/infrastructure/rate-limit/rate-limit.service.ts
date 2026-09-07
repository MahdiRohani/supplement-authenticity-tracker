import { HttpException, HttpStatus, Injectable } from '@nestjs/common';

@Injectable()
export class RateLimitService {
  private readonly hits = new Map<string, number[]>();

  check(key: string, limit: number, windowMs: number): void {
    const now = Date.now();
    const recent = (this.hits.get(key) ?? []).filter((ts) => now - ts < windowMs);
    if (recent.length >= limit) {
      throw new HttpException('Too Many Requests', HttpStatus.TOO_MANY_REQUESTS);
    }
    recent.push(now);
    this.hits.set(key, recent);
  }

  clear(): void {
    this.hits.clear();
  }
}
