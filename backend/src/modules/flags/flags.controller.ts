import { Controller, Get } from '@nestjs/common';
import { FeatureFlagsService } from '../../config/feature-flags.service';
import { Public } from '../../security/public.decorator';

@Controller('flags')
export class FlagsController {
  constructor(private readonly flags: FeatureFlagsService) {}

  @Public()
  @Get()
  list() {
    return this.flags.getFlags();
  }
}
