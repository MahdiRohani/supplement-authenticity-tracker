import { IsInt, IsOptional, IsString, Max, Min, MinLength } from 'class-validator';

export class RegisterProductDto {
  @IsString()
  @MinLength(1)
  name!: string;

  @IsOptional()
  @IsString()
  batch?: string;

  @IsOptional()
  @IsString()
  manufacturerAddress?: string;

  @IsOptional()
  @IsString()
  physicalId?: string;
}

export class RegisterBatchDto {
  @IsString()
  @MinLength(1)
  name!: string;

  @IsOptional()
  @IsString()
  batch?: string;

  @IsInt()
  @Min(1)
  @Max(100)
  count!: number;

  @IsOptional()
  @IsString()
  manufacturerAddress?: string;
}

export class TransferDto {
  @IsString()
  @MinLength(42)
  toAddress!: string;
}

export class ConsumeDto {
  @IsString()
  @MinLength(66)
  secret!: string;
}
