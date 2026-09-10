import { validateEnv } from '../src/config/env.validation';

describe('validateEnv production hardening', () => {
  const base = {
    DATABASE_URL: 'postgresql://postgres:postgres@localhost:5432/supplement_tracker',
  };

  it('requires API_WRITE_KEY in production', () => {
    expect(() =>
      validateEnv({
        ...base,
        NODE_ENV: 'production',
        API_WRITE_KEY: '',
      }),
    ).toThrow(/API_WRITE_KEY/);
  });

  it('rejects IPFS stub in production', () => {
    expect(() =>
      validateEnv({
        ...base,
        NODE_ENV: 'production',
        API_WRITE_KEY: 'secret',
        ALLOW_IPFS_STUB: 'true',
      }),
    ).toThrow(/ALLOW_IPFS_STUB/);
  });

  it('allows development without write key', () => {
    const env = validateEnv({
      ...base,
      NODE_ENV: 'development',
    });
    expect(env.NODE_ENV).toBe('development');
  });
});
