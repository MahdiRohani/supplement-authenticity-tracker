import { Body, Controller, Get, Post, Query } from '@nestjs/common';
import { IsOptional, IsString, MinLength } from 'class-validator';
import { Public } from '../../security/public.decorator';
import { ReportsService } from './reports.service';

class ReportCounterfeitDto {
  @IsString()
  @MinLength(1)
  chainProductId!: string;

  @IsOptional()
  @IsString()
  note?: string;

  @IsOptional()
  @IsString()
  reporter?: string;
}

@Controller('reports')
export class ReportsController {
  constructor(private readonly reports: ReportsService) {}

  @Public()
  @Post('counterfeit')
  report(@Body() body: ReportCounterfeitDto) {
    return this.reports.reportCounterfeit(body);
  }

  @Get('counterfeit')
  list(@Query('limit') limit?: string) {
    return this.reports.list(limit ? Number(limit) : 50);
  }
}
