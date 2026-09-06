import {
  BadRequestException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { SupplyRole } from '@prisma/client';
import { PrismaService } from '../../infrastructure/prisma/prisma.service';

@Injectable()
export class RolesService {
  constructor(private readonly prisma: PrismaService) {}

  list() {
    return this.prisma.roleBinding.findMany({
      orderBy: [{ role: 'asc' }, { address: 'asc' }],
    });
  }

  listForAddress(address: string) {
    return this.prisma.roleBinding.findMany({
      where: { address: address.toLowerCase() },
      orderBy: { role: 'asc' },
    });
  }

  async bind(address: string, role: SupplyRole) {
    if (!address?.startsWith('0x')) {
      throw new BadRequestException('address must be a hex wallet address');
    }
    if (!Object.values(SupplyRole).includes(role)) {
      throw new BadRequestException(`Unknown role: ${role}`);
    }

    return this.prisma.roleBinding.upsert({
      where: {
        address_role: {
          address: address.toLowerCase(),
          role,
        },
      },
      create: {
        address: address.toLowerCase(),
        role,
      },
      update: {},
    });
  }

  async unbind(address: string, role: SupplyRole) {
    try {
      await this.prisma.roleBinding.delete({
        where: {
          address_role: {
            address: address.toLowerCase(),
            role,
          },
        },
      });
      return { deleted: true };
    } catch {
      throw new NotFoundException('Role binding not found');
    }
  }
}
