package config

import "strings"

// Flags are the feature toggles exposed at GET /v1/flags. Field order is the
// JSON order clients already see.
type Flags struct {
	ReportsEnabled        bool `json:"reportsEnabled"`
	ScanEnabled           bool `json:"scanEnabled"`
	LabelsPDFEnabled      bool `json:"labelsPdfEnabled"`
	AnalyticsEnabled      bool `json:"analyticsEnabled"`
	EIP712MetadataEnabled bool `json:"eip712MetadataEnabled"`
	MetaTxConsumeEnabled  bool `json:"metaTxConsumeEnabled"`
	SubgraphPreferred     bool `json:"subgraphPreferred"`
}

func loadFlags(lookup Lookup) Flags {
	flag := func(key string, fallback bool) bool {
		raw, ok := lookup(key)
		if !ok || raw == "" {
			return fallback
		}
		return raw == "1" || strings.ToLower(raw) == "true"
	}
	return Flags{
		ReportsEnabled:        flag("FF_REPORTS", true),
		ScanEnabled:           flag("FF_SCAN", true),
		LabelsPDFEnabled:      flag("FF_LABELS_PDF", true),
		AnalyticsEnabled:      flag("FF_ANALYTICS", true),
		EIP712MetadataEnabled: flag("FF_EIP712_METADATA", true),
		MetaTxConsumeEnabled:  flag("FF_META_TX_CONSUME", true),
		SubgraphPreferred:     flag("FF_SUBGRAPH", false),
	}
}
