import { Injectable } from '@nestjs/common';
import { PrismaService } from '../../infrastructure/prisma/prisma.service';
import { FeatureFlagsService } from '../../config/feature-flags.service';

@Injectable()
export class AnalyticsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly flags: FeatureFlagsService,
  ) {}

  async increment(name: string) {
    if (!this.flags.getFlags().analyticsEnabled) {
      return { skipped: true };
    }
    const row = await this.prisma.analyticsCounter.upsert({
      where: { name },
      create: { name, value: 1n },
      update: { value: { increment: 1n } },
    });
    return { name: row.name, value: row.value.toString() };
  }

  async snapshot() {
    const rows = await this.prisma.analyticsCounter.findMany({
      orderBy: { name: 'asc' },
    });
    return {
      counters: rows.map((row) => ({
        name: row.name,
        value: row.value.toString(),
      })),
      note: 'Technical counters only; no wallets or product IDs are stored here.',
    };
  }
}
