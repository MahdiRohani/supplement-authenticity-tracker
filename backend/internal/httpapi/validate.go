package httpapi

import (
	"math"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

// Request DTO validation reproducing NestJS' ValidationPipe with
// class-validator (whitelist + forbidNonWhitelisted + implicit conversion),
// so clients keep receiving the same message arrays in the same order:
// unknown properties first, then each field in declaration order with its
// messages in reverse decorator order.

type fieldKind int

const (
	kindString fieldKind = iota
	kindNumber
)

type check struct {
	valid   func(v any) bool
	message func(field string) string
}

type field struct {
	name     string
	kind     fieldKind
	optional bool
	// checks are in decorator declaration order.
	checks []check
}

func stringField(name string) *field {
	return &field{name: name, kind: kindString, checks: []check{{
		valid:   func(v any) bool { _, ok := v.(string); return ok },
		message: func(f string) string { return f + " must be a string" },
	}}}
}

func intField(name string) *field {
	return &field{name: name, kind: kindNumber, checks: []check{{
		valid: func(v any) bool {
			f, ok := v.(float64)
			return ok && !math.IsInf(f, 0) && !math.IsNaN(f) && f == math.Trunc(f)
		},
		message: func(f string) string { return f + " must be an integer number" },
	}}}
}

func (f *field) optionalField() *field {
	f.optional = true
	return f
}

func (f *field) minLength(n int) *field {
	f.checks = append(f.checks, check{
		valid: func(v any) bool {
			s, ok := v.(string)
			return ok && jsLength(s) >= n
		},
		message: func(name string) string {
			return name + " must be longer than or equal to " + jsNumberString(float64(n)) + " characters"
		},
	})
	return f
}

func (f *field) min(n float64) *field {
	f.checks = append(f.checks, check{
		valid: func(v any) bool {
			x, ok := v.(float64)
			return ok && x >= n
		},
		message: func(name string) string { return name + " must not be less than " + jsNumberString(n) },
	})
	return f
}

func (f *field) max(n float64) *field {
	f.checks = append(f.checks, check{
		valid: func(v any) bool {
			x, ok := v.(float64)
			return ok && x <= n
		},
		message: func(name string) string { return name + " must not be greater than " + jsNumberString(n) },
	})
	return f
}

type schema []*field

// validate coerces and checks body, returning the whitelisted values.
func (s schema) validate(body jsonBody) (values, error) {
	known := make(map[string]bool, len(s))
	for _, f := range s {
		known[f.name] = true
	}
	var messages []string
	for _, k := range body.keys {
		if !known[k] {
			messages = append(messages, "property "+k+" should not exist")
		}
	}

	out := values{}
	for _, f := range s {
		raw, present := body.get(f.name)
		var v any
		if present {
			if f.kind == kindString {
				v = toStringValue(raw)
			} else {
				v = toNumberValue(raw)
			}
		}
		if f.optional && v == nil {
			continue
		}
		for i := len(f.checks) - 1; i >= 0; i-- {
			if !f.checks[i].valid(v) {
				messages = append(messages, f.checks[i].message(f.name))
			}
		}
		out[f.name] = v
	}
	if len(messages) > 0 {
		return nil, apperr.Validation(messages)
	}
	return out, nil
}

type values map[string]any

func (v values) str(name string) string {
	s, _ := v[name].(string)
	return s
}

func (v values) optStr(name string) *string {
	s, ok := v[name].(string)
	if !ok {
		return nil
	}
	return &s
}

func (v values) int(name string) int64 {
	f, _ := v[name].(float64)
	return saturateInt64(f)
}

func (v values) optInt(name string) *int64 {
	f, ok := v[name].(float64)
	if !ok {
		return nil
	}
	n := saturateInt64(f)
	return &n
}

func saturateInt64(f float64) int64 {
	switch {
	case f >= math.MaxInt64:
		return math.MaxInt64
	case f <= math.MinInt64:
		return math.MinInt64
	default:
		return int64(f)
	}
}
