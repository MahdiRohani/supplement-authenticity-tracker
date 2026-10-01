package httpapi

import (
	"crypto/subtle"
	"net"
	"net/http"
	"regexp"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

// securityHeaders are helmet v8's defaults, which the previous API sent on
// every response.
var securityHeaders = map[string]string{
	"Content-Security-Policy":           "default-src 'self';base-uri 'self';font-src 'self' https: data:;form-action 'self';frame-ancestors 'self';img-src 'self' data:;object-src 'none';script-src 'self';script-src-attr 'none';style-src 'self' https: 'unsafe-inline';upgrade-insecure-requests",
	"Cross-Origin-Opener-Policy":        "same-origin",
	"Cross-Origin-Resource-Policy":      "same-origin",
	"Origin-Agent-Cluster":              "?1",
	"Referrer-Policy":                   "no-referrer",
	"Strict-Transport-Security":         "max-age=31536000; includeSubDomains",
	"X-Content-Type-Options":            "nosniff",
	"X-Dns-Prefetch-Control":            "off",
	"X-Download-Options":                "noopen",
	"X-Frame-Options":                   "SAMEORIGIN",
	"X-Permitted-Cross-Domain-Policies": "none",
	"X-Xss-Protection":                  "0",
}

func setBaseHeaders(h http.Header) {
	for k, v := range securityHeaders {
		h.Set(k, v)
	}
	h.Set("Access-Control-Allow-Origin", "*")
}

// writePreflight answers every OPTIONS request like the `cors` package with
// its default options.
func writePreflight(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE")
	h.Add("Vary", "Access-Control-Request-Headers")
	if requested := r.Header.Get("Access-Control-Request-Headers"); requested != "" {
		h.Set("Access-Control-Allow-Headers", requested)
	}
	h.Set("Content-Length", "0")
	w.WriteHeader(http.StatusNoContent)
}

var bearerPrefix = regexp.MustCompile(`(?i)^Bearer\s+`)

// authorize enforces API_WRITE_KEY on non-public mutating routes.
func (s *Server) authorize(r *http.Request, rt *route) error {
	if rt.public {
		return nil
	}
	switch r.Method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		return nil
	}
	expected := s.cfg.APIWriteKey
	if expected == "" {
		if s.cfg.IsProduction() {
			return apperr.Unauthorized("API_WRITE_KEY is required in production")
		}
		return nil
	}
	provided := r.Header.Get("X-Api-Key")
	if provided == "" {
		provided = bearerPrefix.ReplaceAllString(r.Header.Get("Authorization"), "")
	}
	if provided == "" || subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) != 1 {
		return apperr.Unauthorized("Invalid or missing write API key")
	}
	return nil
}

type limitClass int

const (
	noLimit limitClass = iota
	verifyLimit
	consumeLimit
)

func (s *Server) rateLimit(r *http.Request, rt *route) error {
	var key string
	var limit int
	switch rt.limit {
	case verifyLimit:
		key, limit = "verify:", s.cfg.VerifyRateLimit
	case consumeLimit:
		key, limit = "consume:", s.cfg.ConsumeRateLimit
	default:
		return nil
	}
	if !s.limiter.Allow(key+clientIP(r), limit, s.cfg.RateLimitWindow) {
		return apperr.TooManyRequests()
	}
	return nil
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (rec *statusRecorder) WriteHeader(status int) {
	if rec.status == 0 {
		rec.status = status
	}
	rec.ResponseWriter.WriteHeader(status)
}

func (rec *statusRecorder) Write(b []byte) (int, error) {
	if rec.status == 0 {
		rec.status = http.StatusOK
	}
	n, err := rec.ResponseWriter.Write(b)
	rec.bytes += n
	return n, err
}

// observe logs each request and turns panics into a 500 response.
func (s *Server) observe(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		defer func() {
			if p := recover(); p != nil {
				if p == http.ErrAbortHandler {
					panic(p)
				}
				s.log.ErrorContext(r.Context(), "panic serving request", "method", r.Method, "path", r.URL.Path, "panic", p)
				if rec.status == 0 {
					writeJSON(rec, r, http.StatusInternalServerError, bareErrorBody{
						StatusCode: http.StatusInternalServerError,
						Message:    "Internal server error",
					})
				}
			}
			s.log.InfoContext(r.Context(), "request",
				"method", r.Method,
				"path", r.URL.Path,
				"status", rec.status,
				"bytes", rec.bytes,
				"durationMs", time.Since(started).Milliseconds(),
				"ip", clientIP(r),
			)
		}()
		next.ServeHTTP(rec, r)
	})
}
