import { Controller, Get, Post } from '@nestjs/common';
import { Public } from '../../security/public.decorator';
import { AnalyticsService } from './analytics.service';

@Controller('analytics')
export class AnalyticsController {
  constructor(private readonly analytics: AnalyticsService) {}

  @Public()
  @Post('events/verify')
  trackVerify() {
    return this.analytics.increment('verify_ok');
  }

  @Public()
  @Post('events/scan')
  trackScan() {
    return this.analytics.increment('scan_ok');
  }

  @Get('snapshot')
  snapshot() {
    return this.analytics.snapshot();
  }
}
