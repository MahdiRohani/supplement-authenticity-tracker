import { UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { ApiWriteGuard } from '../src/security/api-write.guard';

describe('ApiWriteGuard', () => {
  const reflector = {
    getAllAndOverride: jest.fn().mockReturnValue(false),
  };

  function context(method: string, headers: Record<string, string> = {}) {
    return {
      getHandler: () => ({}),
      getClass: () => ({}),
      switchToHttp: () => ({
        getRequest: () => ({ method, headers }),
      }),
    };
  }

  it('allows GET without key', () => {
    const guard = new ApiWriteGuard(
      { get: () => 'secret' } as never,
      reflector as never,
    );
    expect(guard.canActivate(context('GET') as never)).toBe(true);
  });

  it('rejects POST without key in production when API_WRITE_KEY empty', () => {
    const guard = new ApiWriteGuard(
      {
        get: (key: string) =>
          key === 'API_WRITE_KEY' ? undefined : key === 'NODE_ENV' ? 'production' : undefined,
      } as never,
      reflector as never,
    );
    expect(() => guard.canActivate(context('POST') as never)).toThrow(
      UnauthorizedException,
    );
  });
});
