import {
  Body,
  Controller,
  Delete,
  Get,
  Param,
  Post,
} from '@nestjs/common';
import { SupplyRole } from '@prisma/client';
import { RolesService } from './roles.service';

type BindRoleBody = {
  address: string;
  role: SupplyRole;
};

@Controller('roles')
export class RolesController {
  constructor(private readonly rolesService: RolesService) {}

  @Get()
  list() {
    return this.rolesService.list();
  }

  @Get(':address')
  listForAddress(@Param('address') address: string) {
    return this.rolesService.listForAddress(address);
  }

  @Post()
  bind(@Body() body: BindRoleBody) {
    return this.rolesService.bind(body.address, body.role);
  }

  @Delete(':address/:role')
  unbind(
    @Param('address') address: string,
    @Param('role') role: SupplyRole,
  ) {
    return this.rolesService.unbind(address, role);
  }
}
