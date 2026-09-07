import { Module } from '@nestjs/common';
import { IpfsModule } from '../../infrastructure/ipfs/ipfs.module';
import { VerifyController } from './verify.controller';
import { VerifyService } from './verify.service';

@Module({
  imports: [IpfsModule],
  controllers: [VerifyController],
  providers: [VerifyService],
  exports: [VerifyService],
})
export class VerifyModule {}
