import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class AuditLogService {
  constructor(private readonly prisma: PrismaService) {}

  async record(input: {
    action: string;
    entityId?: string;
    actor?: string;
    detail?: Record<string, unknown> | string;
  }) {
    const detail =
      typeof input.detail === 'string'
        ? input.detail
        : input.detail
          ? JSON.stringify(input.detail)
          : null;
    return this.prisma.auditLog.create({
      data: {
        action: input.action,
        entityId: input.entityId,
        actor: input.actor?.toLowerCase(),
        detail,
      },
    });
  }
}
