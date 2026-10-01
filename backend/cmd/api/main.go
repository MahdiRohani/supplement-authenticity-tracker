// Command api runs the supplement authenticity REST API and the on-chain
// event indexer.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/ethclient"
	"github.com/joho/godotenv"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/analytics"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cache"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/config"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/httpapi"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ipfs"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/product"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ratelimit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/reports"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/verify"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fatal:", err)
		os.Exit(1)
	}
}

// healthcheck probes the local readiness endpoint. It backs the container
// HEALTHCHECK because the distroless image ships no shell or curl.
func healthcheck() int {
	port := os.Getenv("PORT")
	if port == "" {
		port = "3000"
	}
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/v1/health/ready")
	if err != nil {
		fmt.Fprintln(os.Stderr, "unhealthy:", err)
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		fmt.Fprintln(os.Stderr, "unhealthy: status", resp.StatusCode)
		return 1
	}
	return 0
}

func run() error {
	// A local .env is optional and never overrides the real environment.
	_ = godotenv.Load()

	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		return err
	}
	log := newLogger(cfg.LogLevel)
	slog.SetDefault(log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer st.Close()
	if cfg.AutoMigrate {
		if err := st.Migrate(ctx, log, time.Minute); err != nil {
			return fmt.Errorf("migrate: %w", err)
		}
	}

	auditor := audit.NewRecorder(st)
	chainDeps, err := setupChain(ctx, cfg, log)
	if err != nil {
		return err
	}
	if chainDeps.client != nil {
		defer chainDeps.client.Close()
	}

	keys := chain.NewKeyStore()
	reloadKeys := func(ctx context.Context, reason string) ([]string, error) {
		addresses, err := keys.Reload(cfg.RelayerKeysJSON, cfg.RelayerKeysPreviousJSON)
		if err != nil {
			return nil, err
		}
		active, previous := keys.Counts()
		log.Info("relayer keys reloaded", "reason", reason, "active", active, "previous", previous)
		go func() {
			err := auditor.Record(context.WithoutCancel(ctx), audit.Entry{
				Action: "relayer.keys.reload",
				Detail: struct {
					Reason        string `json:"reason"`
					ActiveCount   int    `json:"activeCount"`
					PreviousCount int    `json:"previousCount"`
				}{reason, active, previous},
			})
			if err != nil {
				log.Warn("audit relayer key reload failed", "err", err)
			}
		}()
		return addresses, nil
	}
	if _, err := reloadKeys(ctx, "bootstrap"); err != nil {
		return err
	}

	if chainDeps.writes != nil {
		indexer := chain.NewIndexer(chainDeps.client, chainDeps.writes, st, log)
		go indexer.Run(ctx)
	} else {
		log.Warn("indexer disabled: RPC_URL or REGISTRY_ADDRESS missing")
	}

	verifyCache := cache.NewTTL[verify.Result]()
	ipfsClient := ipfs.New(cfg.IPFSAPIURL, cfg.IPFSGatewayURL, cfg.AllowIPFSStub == "true" || !cfg.IsProduction(), log)
	relayer := chain.NewRelayer(chainDeps.writes, keys, chainDeps.writeErr)
	products := product.NewService(st, relayer, ipfsClient, auditor, verifyCache, log)

	// v2 reads are served from the projection even without RPC; writes then
	// fail with the setup error.
	v2Address := chainDeps.network.RegistryV2().Address
	if chainDeps.v2 != nil {
		v2Address = chainDeps.v2.Address().Hex()
	}
	registryV2 := chain.NewRegistryV2(chainDeps.v2, keys, chainDeps.v2Err)
	protocolSvc := protocol.NewService(st, registryV2, ipfsClient, auditor,
		chain.NewEIP712V2(v2Address), log, protocol.Options{
			ChainID:             cfg.ActiveChainID(),
			PublicBaseURL:       cfg.PublicVerifyBaseURL,
			MaxBatchUnits:       cfg.MaxBatchUnits,
			ScanSalt:            cfg.ScanHashSalt,
			DefaultManufacturer: product.DefaultManufacturer,
			SnapshotTTL:         cfg.VerifyCacheTTL,
		})
	if chainDeps.v2 != nil {
		indexerV2 := chain.NewIndexerV2(chainDeps.client, chainDeps.v2, protocolSvc.Projector(), st, log, chain.IndexerV2Options{
			StartBlock:    chainDeps.v2StartBlock,
			Confirmations: uint64(cfg.IndexerConfirmations),
			AllowReset:    !cfg.IsProduction(),
		})
		go indexerV2.Run(ctx)
	} else {
		log.Warn("v2 indexer disabled", "err", chainDeps.v2Err)
	}

	v1Address := cfg.RegistryAddress
	if chainDeps.writes != nil {
		v1Address = chainDeps.writes.Address().Hex()
	}

	handler := httpapi.New(httpapi.Deps{
		Log:       log,
		Config:    cfg,
		DB:        st,
		Network:   chainDeps.network,
		Products:  products,
		Verify:    verify.NewService(st, chain.NewReader(chainDeps.reads), ipfsClient, verifyCache, cfg.VerifyCacheTTL),
		Roles:     roles.NewService(st),
		Reports:   reports.NewService(st, auditor, cfg.Flags.ReportsEnabled),
		Analytics: analytics.NewService(st, cfg.Flags.AnalyticsEnabled),
		EIP712:    chain.NewEIP712(v1Address),
		Protocol:  protocolSvc,
		Parties:   roles.NewPartyService(st, registryV2),
		ReloadKeys: func(ctx context.Context) ([]string, error) {
			return reloadKeys(ctx, "api")
		},
		Limiter: ratelimit.New(),
	})

	srv := &http.Server{
		Addr:              net.JoinHostPort("", strconv.Itoa(cfg.Port)),
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		BaseContext:       func(net.Listener) context.Context { return ctx },
	}
	errCh := make(chan error, 1)
	go func() {
		log.Info("api listening", "port", cfg.Port, "env", cfg.Env)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
	}
	log.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}

type chainSetup struct {
	network *chain.Network
	client  *ethclient.Client
	// writes is used by the relayer and indexer; reads serves verify
	// fallbacks. They may point at different addresses (see setupChain).
	writes   *chain.Contract
	reads    *chain.Contract
	writeErr error
	// v2 is SupplementRegistryV2, used by the v2 relayer and indexer.
	v2           *chain.Contract
	v2Err        error
	v2StartBlock uint64
}

// setupChain resolves registry addresses the way the previous service did:
// writes and indexing use REGISTRY_ADDRESS, then the deployment for CHAIN_ID,
// then the ABI artifact address; verify fallbacks use REGISTRY_ADDRESS, then
// the artifact address.
func setupChain(ctx context.Context, cfg config.Config, log *slog.Logger) (chainSetup, error) {
	deployments := chain.LoadDeployments(cfg.DeploymentsPath)
	out := chainSetup{network: chain.NewNetwork(cfg.ActiveChainID(), cfg.RegistryAddress, deployments).WithRegistryV2(cfg.RegistryV2Address)}

	artifact, err := chain.LoadArtifact(cfg.RegistryABIPath)
	if err != nil {
		log.Warn("registry ABI unavailable; on-chain features disabled", "path", cfg.RegistryABIPath, "err", err)
	}
	artifactAddress := ""
	if artifact != nil {
		artifactAddress = strings.TrimSpace(artifact.Address)
	}

	if cfg.RPCURL == "" {
		out.writeErr = apperr.BadRequest("RPC_URL is not configured")
		out.v2Err = out.writeErr
		return out, nil
	}
	client, err := ethclient.DialContext(ctx, cfg.RPCURL)
	if err != nil {
		return out, fmt.Errorf("connect RPC_URL: %w", err)
	}
	out.client = client
	setupRegistryV2(&out, cfg, log)
	if artifact == nil {
		out.writeErr = fmt.Errorf("registry ABI unavailable at %s", cfg.RegistryABIPath)
		return out, nil
	}

	writeAddress := firstNonEmpty(out.network.RegistryAddress(), artifactAddress)
	switch {
	case writeAddress == "":
		out.writeErr = apperr.BadRequest("REGISTRY_ADDRESS is not configured")
	case !common.IsHexAddress(writeAddress):
		out.writeErr = apperr.BadRequest("REGISTRY_ADDRESS is not a valid address")
	default:
		out.writes = chain.NewContract(client, artifact.ABI, common.HexToAddress(writeAddress))
	}
	if readAddress := firstNonEmpty(strings.TrimSpace(cfg.RegistryAddress), artifactAddress); common.IsHexAddress(readAddress) {
		out.reads = chain.NewContract(client, artifact.ABI, common.HexToAddress(readAddress))
	}
	return out, nil
}

// setupRegistryV2 resolves SupplementRegistryV2: REGISTRY_V2_ADDRESS, then
// deployments.json, then the ABI artifact. Indexing starts at
// INDEXER_START_BLOCK when set, otherwise at the deploy block recorded for
// that same address.
func setupRegistryV2(out *chainSetup, cfg config.Config, log *slog.Logger) {
	artifact, err := chain.LoadArtifact(cfg.RegistryV2ABIPath)
	if err != nil {
		log.Warn("registry v2 ABI unavailable; protocol v2 writes disabled", "path", cfg.RegistryV2ABIPath, "err", err)
		out.v2Err = apperr.ServiceUnavailable("SupplementRegistryV2 ABI is not available")
		return
	}
	deployment := out.network.RegistryV2()
	address := firstNonEmpty(deployment.Address, strings.TrimSpace(artifact.Address))
	if !common.IsHexAddress(address) {
		out.v2Err = apperr.ServiceUnavailable("REGISTRY_V2_ADDRESS is not configured")
		return
	}
	switch {
	case cfg.IndexerStartBlock >= 0:
		out.v2StartBlock = uint64(cfg.IndexerStartBlock)
	case strings.EqualFold(address, deployment.Address):
		out.v2StartBlock = deployment.DeployBlock
	case strings.EqualFold(address, artifact.Address):
		out.v2StartBlock = artifact.DeployBlock
	}
	out.v2 = chain.NewContract(out.client, artifact.ABI, common.HexToAddress(address))
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// newLogger writes JSON logs; LOG_LEVEL accepts pino's names.
func newLogger(level string) *slog.Logger {
	var lvl slog.Level
	switch strings.ToLower(level) {
	case "trace", "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error", "fatal":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: lvl}))
}
