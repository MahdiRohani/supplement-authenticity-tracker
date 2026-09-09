import { Controller, Get } from '@nestjs/common';
import { ChainConfigService } from '../../config/chain-config.service';
import { Public } from '../../security/public.decorator';

@Controller('chains')
export class ChainsController {
  constructor(private readonly chain: ChainConfigService) {}

  @Public()
  @Get()
  list() {
    return {
      activeChainId: this.chain.getChainId(),
      registryAddress: this.chain.resolveRegistryAddress(),
      active: this.chain.getActiveDeployment(),
      deployments: this.chain.listDeployments(),
    };
  }
}
