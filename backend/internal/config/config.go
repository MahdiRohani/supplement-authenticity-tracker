// Package config loads and validates the process environment at startup.
package config

import (
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const DefaultChainID int64 = 31337

// Config is the validated runtime configuration.
type Config struct {
	DatabaseURL string
	Port        int

	RPCURL          string
	RegistryAddress string
	RegistryABIPath string
	DeploymentsPath string
	ChainID         int64

	IPFSAPIURL     string
	IPFSGatewayURL string
	AllowIPFSStub  string

	VerifyCacheTTL   time.Duration
	VerifyRateLimit  int
	ConsumeRateLimit int
	RateLimitWindow  time.Duration

	RelayerKeysJSON         string
	RelayerKeysPreviousJSON string
	APIWriteKey             string

	LogLevel    string
	Env         string
	AutoMigrate bool

	Flags Flags
}

// Lookup matches os.LookupEnv.
type Lookup func(key string) (string, bool)

func (c Config) IsProduction() bool { return c.Env == "production" }

// ActiveChainID is CHAIN_ID when set, otherwise the local Hardhat chain.
func (c Config) ActiveChainID() int64 {
	if c.ChainID > 0 {
		return c.ChainID
	}
	return DefaultChainID
}

// Load reads and validates the environment. All problems are reported at once.
func Load(lookup Lookup) (Config, error) {
	p := parser{lookup: lookup}
	cfg := Config{
		DatabaseURL:             p.required("DATABASE_URL"),
		Port:                    p.positiveInt("PORT", 3000),
		RPCURL:                  p.optionalURL("RPC_URL"),
		RegistryAddress:         p.str("REGISTRY_ADDRESS", ""),
		RegistryABIPath:         p.str("REGISTRY_ABI_PATH", "../packages/abis/SupplementRegistry.json"),
		DeploymentsPath:         p.str("DEPLOYMENTS_PATH", "../packages/abis/deployments.json"),
		ChainID:                 int64(p.positiveInt("CHAIN_ID", 0)),
		IPFSAPIURL:              p.str("IPFS_API_URL", ""),
		IPFSGatewayURL:          p.str("IPFS_GATEWAY_URL", ""),
		AllowIPFSStub:           p.str("ALLOW_IPFS_STUB", ""),
		VerifyCacheTTL:          time.Duration(p.positiveInt("VERIFY_CACHE_TTL_MS", 15_000)) * time.Millisecond,
		VerifyRateLimit:         p.positiveInt("VERIFY_RATE_LIMIT", 60),
		ConsumeRateLimit:        p.positiveInt("CONSUME_RATE_LIMIT", 20),
		RateLimitWindow:         time.Duration(p.positiveInt("RATE_LIMIT_WINDOW_MS", 60_000)) * time.Millisecond,
		RelayerKeysJSON:         p.str("RELAYER_KEYS_JSON", "{}"),
		RelayerKeysPreviousJSON: p.str("RELAYER_KEYS_PREVIOUS_JSON", ""),
		APIWriteKey:             p.str("API_WRITE_KEY", ""),
		LogLevel:                p.str("LOG_LEVEL", "info"),
		Env:                     p.env(),
		AutoMigrate:             p.boolean("AUTO_MIGRATE", true),
		Flags:                   loadFlags(lookup),
	}
	if err := p.err(); err != nil {
		return Config{}, err
	}

	if cfg.IsProduction() && strings.TrimSpace(cfg.APIWriteKey) == "" {
		return Config{}, errors.New("invalid environment configuration: API_WRITE_KEY is required when APP_ENV=production")
	}
	if cfg.IsProduction() && (cfg.AllowIPFSStub == "true" || cfg.AllowIPFSStub == "1") {
		return Config{}, errors.New("invalid environment configuration: ALLOW_IPFS_STUB cannot be enabled in production")
	}
	return cfg, nil
}

type parser struct {
	lookup Lookup
	issues []string
}

func (p *parser) fail(key, msg string) {
	p.issues = append(p.issues, key+": "+msg)
}

func (p *parser) err() error {
	if len(p.issues) == 0 {
		return nil
	}
	return fmt.Errorf("invalid environment configuration: %s", strings.Join(p.issues, "; "))
}

func (p *parser) str(key, fallback string) string {
	if v, ok := p.lookup(key); ok {
		return v
	}
	return fallback
}

func (p *parser) required(key string) string {
	v, ok := p.lookup(key)
	if !ok || v == "" {
		p.fail(key, "required")
		return ""
	}
	return v
}

func (p *parser) positiveInt(key string, fallback int) int {
	raw, ok := p.lookup(key)
	if !ok {
		return fallback
	}
	n, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil || n <= 0 {
		p.fail(key, fmt.Sprintf("must be a positive integer, got %q", raw))
		return fallback
	}
	return n
}

func (p *parser) optionalURL(key string) string {
	raw, _ := p.lookup(key)
	v := strings.TrimSpace(raw)
	if v == "" {
		return ""
	}
	u, err := url.Parse(v)
	if err != nil || u.Scheme == "" || u.Host == "" {
		p.fail(key, fmt.Sprintf("must be a valid URL, got %q", raw))
		return ""
	}
	return v
}

func (p *parser) boolean(key string, fallback bool) bool {
	raw, ok := p.lookup(key)
	if !ok || raw == "" {
		return fallback
	}
	b, err := strconv.ParseBool(raw)
	if err != nil {
		p.fail(key, fmt.Sprintf("must be a boolean, got %q", raw))
		return fallback
	}
	return b
}

// env reads APP_ENV, falling back to NODE_ENV so existing compose files and
// CI settings keep working.
func (p *parser) env() string {
	key := "APP_ENV"
	raw, ok := p.lookup(key)
	if !ok || raw == "" {
		key = "NODE_ENV"
		raw, ok = p.lookup(key)
	}
	if !ok || raw == "" {
		return "development"
	}
	switch raw {
	case "development", "test", "production":
		return raw
	}
	p.fail(key, fmt.Sprintf("must be one of development, test, production, got %q", raw))
	return "development"
}
