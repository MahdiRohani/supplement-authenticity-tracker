import {
  HttpException,
  Injectable,
  NestMiddleware,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { NextFunction, Request, Response } from 'express';
import { RateLimitService } from './rate-limit.service';

@Injectable()
export class RateLimitMiddleware implements NestMiddleware {
  constructor(
    private readonly rateLimit: RateLimitService,
    private readonly config: ConfigService,
  ) {}

  use(req: Request, _res: Response, next: NextFunction) {
    const path = req.path || '';
    const method = req.method.toUpperCase();
    const isVerify = method === 'GET' && /\/verify\//.test(path);
    const isConsume =
      method === 'POST' && /\/products\/[^/]+\/consume$/.test(path);
    if (!isVerify && !isConsume) {
      next();
      return;
    }

    const limit = Number(
      this.config.get(
        isConsume ? 'CONSUME_RATE_LIMIT' : 'VERIFY_RATE_LIMIT',
      ) ?? (isConsume ? 20 : 60),
    );
    const windowMs = Number(
      this.config.get('RATE_LIMIT_WINDOW_MS') ?? 60_000,
    );
    const ip = req.ip || req.socket.remoteAddress || 'unknown';
    try {
      this.rateLimit.check(
        `${isConsume ? 'consume' : 'verify'}:${ip}`,
        limit,
        windowMs,
      );
      next();
    } catch {
      next(new HttpException('Too Many Requests', 429));
    }
  }
}
