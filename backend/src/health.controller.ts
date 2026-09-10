import { Controller, Get } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PrismaService } from './infrastructure/prisma/prisma.service';
import { Public } from './security/public.decorator';

@Controller('health')
export class HealthController {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService,
  ) {}

  @Public()
  @Get()
  async getHealth() {
    const checks: Record<string, string> = {
      api: 'ok',
    };
    try {
      await this.prisma.$queryRaw`SELECT 1`;
      checks.database = 'ok';
    } catch {
      checks.database = 'down';
    }
    checks.rpc = this.config.get<string>('RPC_URL') ? 'configured' : 'missing';
    checks.ipfs = this.config.get<string>('IPFS_API_URL')
      ? 'configured'
      : 'missing';
    const status = checks.database === 'ok' ? 'ok' : 'degraded';
    return {
      status,
      version: '1.1.0',
      api: 'v1',
      checks,
      timestamp: new Date().toISOString(),
    };
  }

  @Public()
  @Get('ready')
  async getReady() {
    await this.prisma.$queryRaw`SELECT 1`;
    return { status: 'ready' };
  }
}
