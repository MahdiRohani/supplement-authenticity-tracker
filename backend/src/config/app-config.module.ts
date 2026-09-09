import { Global, Module } from '@nestjs/common';
import { ChainConfigService } from './chain-config.service';
import { FeatureFlagsService } from './feature-flags.service';

@Global()
@Module({
  providers: [FeatureFlagsService, ChainConfigService],
  exports: [FeatureFlagsService, ChainConfigService],
})
export class AppConfigModule {}
