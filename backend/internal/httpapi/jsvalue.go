package httpapi

import (
	"math"
	"math/big"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// The API contract was defined by a NestJS service using class-transformer's
// implicit conversion, so request values are coerced with JavaScript's
// String() and Number() semantics.

var (
	decimalLiteral = regexp.MustCompile(`^[+-]?(Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)$`)
	radixLiteral   = regexp.MustCompile(`^0([xX][0-9a-fA-F]+|[oO][0-7]+|[bB][01]+)$`)
)

// jsNumberString formats f like Number.prototype.toString().
func jsNumberString(f float64) string {
	switch {
	case math.IsNaN(f):
		return "NaN"
	case math.IsInf(f, 1):
		return "Infinity"
	case math.IsInf(f, -1):
		return "-Infinity"
	case f == 0:
		return "0"
	}
	abs := math.Abs(f)
	if abs >= 1e21 || abs < 1e-6 {
		s := strconv.FormatFloat(f, 'e', -1, 64)
		mantissa, exp, _ := strings.Cut(s, "e")
		sign, digits := exp[:1], strings.TrimLeft(exp[1:], "0")
		return mantissa + "e" + sign + digits
	}
	return strconv.FormatFloat(f, 'f', -1, 64)
}

// jsStringToNumber implements Number(string).
func jsStringToNumber(s string) float64 {
	s = strings.TrimFunc(s, func(r rune) bool { return unicode.IsSpace(r) || r == '\uFEFF' })
	if s == "" {
		return 0
	}
	if decimalLiteral.MatchString(s) {
		switch strings.TrimLeft(s, "+-") {
		case "Infinity":
			if strings.HasPrefix(s, "-") {
				return math.Inf(-1)
			}
			return math.Inf(1)
		}
		f, _ := strconv.ParseFloat(s, 64)
		return f
	}
	if radixLiteral.MatchString(s) {
		n, ok := new(big.Int).SetString(s, 0)
		if ok {
			f, _ := new(big.Float).SetInt(n).Float64()
			return f
		}
	}
	return math.NaN()
}

// jsToString implements String(value) for decoded JSON values. absent stands
// for undefined.
func jsToString(v any, present bool) string {
	if !present {
		return "undefined"
	}
	switch t := v.(type) {
	case nil:
		return "null"
	case string:
		return t
	case float64:
		return jsNumberString(t)
	case bool:
		return strconv.FormatBool(t)
	case []any:
		parts := make([]string, len(t))
		for i, e := range t {
			if e != nil {
				parts[i] = jsToString(e, true)
			}
		}
		return strings.Join(parts, ",")
	default:
		return "[object Object]"
	}
}

// toStringValue is class-transformer's conversion for a `string` property:
// null stays null and arrays are converted element-wise (and later fail
// IsString).
func toStringValue(v any) any {
	switch t := v.(type) {
	case nil, string:
		return t
	case []any:
		out := make([]any, len(t))
		for i, e := range t {
			out[i] = toStringValue(e)
		}
		return out
	default:
		return jsToString(t, true)
	}
}

// toNumberValue is class-transformer's conversion for a `number` property.
func toNumberValue(v any) any {
	switch t := v.(type) {
	case nil, float64:
		return t
	case string:
		return jsStringToNumber(t)
	case bool:
		if t {
			return 1.0
		}
		return 0.0
	case []any:
		out := make([]any, len(t))
		for i, e := range t {
			out[i] = toNumberValue(e)
		}
		return out
	default:
		return math.NaN()
	}
}

// jsLength is validator.js' isLength count: UTF-16 length minus surrogate
// pairs and variation selectors, i.e. code points excluding U+FE0E/U+FE0F.
func jsLength(s string) int {
	return utf8.RuneCountInString(s) - strings.Count(s, "\uFE0E") - strings.Count(s, "\uFE0F")
}

// isArrayIndex reports whether key is a canonical array index, which
// JavaScript orders before other object keys.
func isArrayIndex(key string) (uint32, bool) {
	if key == "0" {
		return 0, true
	}
	if key == "" || key[0] < '1' || key[0] > '9' {
		return 0, false
	}
	n, err := strconv.ParseUint(key, 10, 32)
	if err != nil || n == math.MaxUint32 {
		return 0, false
	}
	return uint32(n), true
}
