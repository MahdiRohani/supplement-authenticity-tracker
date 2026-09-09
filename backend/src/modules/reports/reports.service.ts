import {
  BadRequestException,
  Injectable,
  ServiceUnavailableException,
} from '@nestjs/common';
import { FeatureFlagsService } from '../../config/feature-flags.service';
import { AuditLogService } from '../../infrastructure/audit/audit-log.service';
import { PrismaService } from '../../infrastructure/prisma/prisma.service';

@Injectable()
export class ReportsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly flags: FeatureFlagsService,
    private readonly audit: AuditLogService,
  ) {}

  async reportCounterfeit(input: {
    chainProductId: string;
    note?: string;
    reporter?: string;
  }) {
    if (!this.flags.getFlags().reportsEnabled) {
      throw new ServiceUnavailableException('Counterfeit reports are disabled');
    }
    const chainProductId = input.chainProductId?.trim();
    if (!chainProductId) {
      throw new BadRequestException('chainProductId is required');
    }

    const report = await this.prisma.counterfeitReport.create({
      data: {
        chainProductId,
        note: input.note?.trim() || null,
        reporter: input.reporter?.trim().toLowerCase() || null,
      },
    });

    await this.audit.record({
      action: 'report.counterfeit',
      entityId: report.id,
      actor: report.reporter ?? undefined,
      detail: { chainProductId: report.chainProductId },
    });

    return {
      id: report.id,
      chainProductId: report.chainProductId,
      createdAt: report.createdAt.toISOString(),
    };
  }

  async list(limit = 50) {
    const take = Math.min(100, Math.max(1, limit));
    const items = await this.prisma.counterfeitReport.findMany({
      orderBy: { createdAt: 'desc' },
      take,
    });
    return {
      items: items.map((item) => ({
        id: item.id,
        chainProductId: item.chainProductId,
        note: item.note,
        reporter: item.reporter,
        createdAt: item.createdAt.toISOString(),
      })),
    };
  }
}
