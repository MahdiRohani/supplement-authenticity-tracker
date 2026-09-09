import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Reflector } from '@nestjs/core';
import { IS_PUBLIC_KEY } from './public.decorator';

@Injectable()
export class ApiWriteGuard implements CanActivate {
  constructor(
    private readonly config: ConfigService,
    private readonly reflector: Reflector,
  ) {}

  canActivate(context: ExecutionContext): boolean {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (isPublic) {
      return true;
    }

    const request = context.switchToHttp().getRequest<{
      method?: string;
      headers?: Record<string, string | undefined>;
    }>();
    const method = (request.method ?? 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') {
      return true;
    }

    const expected = this.config.get<string>('API_WRITE_KEY');
    if (!expected) {
      if (this.config.get<string>('NODE_ENV') === 'production') {
        throw new UnauthorizedException('API_WRITE_KEY is required in production');
      }
      return true;
    }

    const provided =
      request.headers?.['x-api-key'] ||
      request.headers?.authorization?.replace(/^Bearer\s+/i, '');
    if (!provided || provided !== expected) {
      throw new UnauthorizedException('Invalid or missing write API key');
    }
    return true;
  }
}
