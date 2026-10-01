package config

import (
	"strings"
	"testing"
	"time"
)

func env(vars map[string]string) Lookup {
	return func(key string) (string, bool) {
		v, ok := vars[key]
		return v, ok
	}
}

const testDatabaseURL = "postgresql://postgres:postgres@localhost:5432/supplement_tracker"

func TestLoadRequiresWriteKeyInProduction(t *testing.T) {
	_, err := Load(env(map[string]string{
		"DATABASE_URL":  testDatabaseURL,
		"NODE_ENV":      "production",
		"API_WRITE_KEY": "",
	}))
	if err == nil || !strings.Contains(err.Error(), "API_WRITE_KEY") {
		t.Fatalf("expected API_WRITE_KEY error, got %v", err)
	}
}

func TestLoadRejectsIPFSStubInProduction(t *testing.T) {
	_, err := Load(env(map[string]string{
		"DATABASE_URL":    testDatabaseURL,
		"APP_ENV":         "production",
		"API_WRITE_KEY":   "secret",
		"ALLOW_IPFS_STUB": "true",
	}))
	if err == nil || !strings.Contains(err.Error(), "ALLOW_IPFS_STUB") {
		t.Fatalf("expected ALLOW_IPFS_STUB error, got %v", err)
	}
}

func TestLoadDevelopmentDefaults(t *testing.T) {
	cfg, err := Load(env(map[string]string{"DATABASE_URL": testDatabaseURL}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Env != "development" || cfg.IsProduction() {
		t.Fatalf("env = %q", cfg.Env)
	}
	if cfg.Port != 3000 || cfg.ActiveChainID() != DefaultChainID {
		t.Fatalf("port=%d chain=%d", cfg.Port, cfg.ActiveChainID())
	}
	if cfg.VerifyCacheTTL != 15*time.Second || cfg.RateLimitWindow != time.Minute {
		t.Fatalf("ttl=%v window=%v", cfg.VerifyCacheTTL, cfg.RateLimitWindow)
	}
	if cfg.VerifyRateLimit != 60 || cfg.ConsumeRateLimit != 20 || !cfg.AutoMigrate {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestAppEnvTakesPrecedenceOverNodeEnv(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"DATABASE_URL": testDatabaseURL,
		"APP_ENV":      "test",
		"NODE_ENV":     "production",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Env != "test" {
		t.Fatalf("env = %q", cfg.Env)
	}
}

func TestLoadReportsAllProblems(t *testing.T) {
	_, err := Load(env(map[string]string{
		"PORT":         "abc",
		"RPC_URL":      "not a url",
		"NODE_ENV":     "staging",
		"AUTO_MIGRATE": "maybe",
	}))
	if err == nil {
		t.Fatal("expected error")
	}
	for _, key := range []string{"DATABASE_URL", "PORT", "RPC_URL", "NODE_ENV", "AUTO_MIGRATE"} {
		if !strings.Contains(err.Error(), key) {
			t.Errorf("error does not mention %s: %v", key, err)
		}
	}
}

func TestFlagsDefaultsAndOverrides(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"DATABASE_URL":  testDatabaseURL,
		"FF_SUBGRAPH":   "true",
		"FF_REPORTS":    "0",
		"FF_LABELS_PDF": "TRUE",
		"FF_SCAN":       "",
	}))
	if err != nil {
		t.Fatal(err)
	}
	f := cfg.Flags
	if !f.SubgraphPreferred || f.ReportsEnabled || !f.LabelsPDFEnabled || !f.ScanEnabled {
		t.Fatalf("unexpected flags: %+v", f)
	}
	if !f.AnalyticsEnabled || !f.EIP712MetadataEnabled || !f.MetaTxConsumeEnabled {
		t.Fatalf("unexpected defaults: %+v", f)
	}
}
