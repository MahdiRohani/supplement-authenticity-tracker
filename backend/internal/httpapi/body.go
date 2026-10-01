package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"mime"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

const maxBodyBytes = 100 * 1024

// jsonBody is a decoded top-level JSON object with its keys in JavaScript
// Object.keys order (array indices ascending, then insertion order).
type jsonBody struct {
	keys   []string
	values map[string]any
}

func (b jsonBody) get(key string) (any, bool) {
	v, ok := b.values[key]
	return v, ok
}

var errBodyTooLarge = &apperr.Error{
	Status:  http.StatusRequestEntityTooLarge,
	Message: "request entity too large",
	Bare:    true,
}

// readBody mirrors Express' JSON body parser: only application/json bodies are
// parsed (anything else yields an empty object), the limit is 100kb, an empty
// body is {}, and only objects or arrays are accepted at the top level.
func readBody(r *http.Request) (jsonBody, error) {
	empty := jsonBody{values: map[string]any{}}
	if r.Body == nil || r.Body == http.NoBody || !isJSONRequest(r) {
		return empty, nil
	}
	if r.ContentLength > maxBodyBytes {
		return empty, errBodyTooLarge
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil {
		return empty, err
	}
	if len(raw) > maxBodyBytes {
		return empty, errBodyTooLarge
	}
	return parseJSONBody(raw)
}

func isJSONRequest(r *http.Request) bool {
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	return err == nil && mediaType == "application/json"
}

func parseJSONBody(raw []byte) (jsonBody, error) {
	body := jsonBody{values: map[string]any{}}
	src := string(raw)
	start := strings.IndexFunc(src, func(r rune) bool { return !isJSONSpace(r) })
	if start < 0 {
		return body, nil
	}
	if first := src[start]; first != '{' && first != '[' {
		return body, syntaxError(src, start)
	}

	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var root any
	if err := dec.Decode(&root); err != nil {
		return body, jsonDecodeError(src, err)
	}
	if off := int(dec.InputOffset()); strings.TrimFunc(src[off:], isJSONSpace) != "" {
		pos := off + strings.IndexFunc(src[off:], func(r rune) bool { return !isJSONSpace(r) })
		return body, apperr.BadRequest(fmt.Sprintf("Unexpected non-whitespace character after JSON at position %d", utf16Index(src, pos)))
	}

	switch root.(type) {
	case []any:
		for i, v := range root.([]any) {
			key := strconv.Itoa(i)
			body.keys = append(body.keys, key)
			body.values[key] = normalizeJSON(v)
		}
		return body, nil
	}

	keys, err := objectKeys(raw)
	if err != nil {
		return body, jsonDecodeError(src, err)
	}
	obj := root.(map[string]any)
	for k, v := range obj {
		body.values[k] = normalizeJSON(v)
	}
	body.keys = jsKeyOrder(keys)
	return body, nil
}

// objectKeys returns the top-level keys of a JSON object in document order,
// keeping the first position of duplicated keys.
func objectKeys(raw []byte) ([]string, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	if _, err := dec.Token(); err != nil {
		return nil, err
	}
	var keys []string
	seen := map[string]bool{}
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, _ := tok.(string)
		if !seen[key] {
			seen[key] = true
			keys = append(keys, key)
		}
		var skip json.RawMessage
		if err := dec.Decode(&skip); err != nil {
			return nil, err
		}
	}
	return keys, nil
}

func jsKeyOrder(keys []string) []string {
	var indices, named []string
	for _, k := range keys {
		if _, ok := isArrayIndex(k); ok {
			indices = append(indices, k)
		} else {
			named = append(named, k)
		}
	}
	slices.SortFunc(indices, func(a, b string) int {
		x, _ := isArrayIndex(a)
		y, _ := isArrayIndex(b)
		return int(int64(x) - int64(y))
	})
	return append(indices, named...)
}

// normalizeJSON converts json.Number to float64 the way JSON.parse does,
// including overflow to ±Infinity.
func normalizeJSON(v any) any {
	switch t := v.(type) {
	case json.Number:
		f, err := strconv.ParseFloat(t.String(), 64)
		if err != nil && !errors.Is(err, strconv.ErrRange) {
			return math.NaN()
		}
		return f
	case []any:
		for i := range t {
			t[i] = normalizeJSON(t[i])
		}
		return t
	case map[string]any:
		for k := range t {
			t[k] = normalizeJSON(t[k])
		}
		return t
	default:
		return v
	}
}

// jsonDecodeError approximates the V8 JSON.parse message that Express
// returned for malformed bodies.
func jsonDecodeError(src string, err error) error {
	if errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, io.EOF) {
		return apperr.BadRequest("Unexpected end of JSON input")
	}
	var syntax *json.SyntaxError
	if errors.As(err, &syntax) {
		if strings.Contains(syntax.Error(), "unexpected end of JSON input") {
			return apperr.BadRequest("Unexpected end of JSON input")
		}
		return syntaxError(src, max(0, int(syntax.Offset)-1))
	}
	return apperr.BadRequest(err.Error())
}

// syntaxError formats V8's "Unexpected token" message, which quotes the whole
// source when it is short and ~10 characters of context around the error
// otherwise.
func syntaxError(src string, bytePos int) error {
	const context = 10
	units := []rune(src)
	pos := utf8.RuneCountInString(src[:min(bytePos, len(src))])
	if pos >= len(units) {
		return apperr.BadRequest("Unexpected end of JSON input")
	}
	token := string(units[pos])
	if len(units) < context*2+1 {
		return apperr.BadRequest(fmt.Sprintf("Unexpected token '%s', \"%s\" is not valid JSON", token, src))
	}
	from, to := max(0, pos-context), min(len(units), pos+context)
	snippet := string(units[from:to])
	prefix, suffix := "", ""
	if from > 0 {
		prefix = "..."
	}
	if to < len(units) {
		suffix = "..."
	}
	return apperr.BadRequest(fmt.Sprintf("Unexpected token '%s', %s\"%s\"%s is not valid JSON", token, prefix, snippet, suffix))
}

func utf16Index(src string, bytePos int) int {
	return utf8.RuneCountInString(src[:bytePos])
}

func isJSONSpace(r rune) bool {
	return r == ' ' || r == '\t' || r == '\n' || r == '\r'
}
