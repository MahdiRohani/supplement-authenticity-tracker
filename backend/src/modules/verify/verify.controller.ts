import { Controller, Get, Param } from '@nestjs/common';
import { VerifyService } from './verify.service';

@Controller('verify')
export class VerifyController {
  constructor(private readonly verifyService: VerifyService) {}

  @Get(':id')
  verify(@Param('id') id: string) {
    return this.verifyService.verify(id);
  }
}
