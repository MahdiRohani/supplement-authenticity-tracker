package httpapi

import (
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
)

const jsonContentType = "application/json; charset=utf-8"

var statusLabels = map[int]string{
	http.StatusBadRequest:            "Bad Request",
	http.StatusUnauthorized:          "Unauthorized",
	http.StatusForbidden:             "Forbidden",
	http.StatusNotFound:              "Not Found",
	http.StatusConflict:              "Conflict",
	http.StatusRequestEntityTooLarge: "Payload Too Large",
	http.StatusTooManyRequests:       "Too Many Requests",
	http.StatusServiceUnavailable:    "Service Unavailable",
}

// Error bodies keep NestJS' key order: labelled exceptions serialize as
// message/error/statusCode, bare ones as statusCode/message.
type errorBody struct {
	Message    any    `json:"message"`
	Error      string `json:"error"`
	StatusCode int    `json:"statusCode"`
}

type bareErrorBody struct {
	StatusCode int    `json:"statusCode"`
	Message    string `json:"message"`
}

func (s *Server) writeError(w http.ResponseWriter, r *http.Request, err error) {
	var appErr *apperr.Error
	if !errors.As(err, &appErr) {
		s.log.ErrorContext(r.Context(), "request failed", "method", r.Method, "path", r.URL.Path, "err", err)
		writeJSON(w, r, http.StatusInternalServerError, bareErrorBody{
			StatusCode: http.StatusInternalServerError,
			Message:    "Internal server error",
		})
		return
	}
	if appErr.Bare {
		writeJSON(w, r, appErr.Status, bareErrorBody{StatusCode: appErr.Status, Message: appErr.Message})
		return
	}
	label, ok := statusLabels[appErr.Status]
	if !ok {
		label = http.StatusText(appErr.Status)
	}
	var message any = appErr.Message
	if len(appErr.Details) > 0 {
		message = appErr.Details
	}
	writeJSON(w, r, appErr.Status, errorBody{Message: message, Error: label, StatusCode: appErr.Status})
}

// created is the default status for successful handlers: 201 for POST and
// 200 otherwise, as in NestJS.
func created(r *http.Request) int {
	if r.Method == http.MethodPost {
		return http.StatusCreated
	}
	return http.StatusOK
}

func writeJSON(w http.ResponseWriter, r *http.Request, status int, v any) {
	body, err := jsonx.Marshal(v)
	if err != nil {
		status = http.StatusInternalServerError
		body = []byte(`{"statusCode":500,"message":"Internal server error"}`)
	}
	writeBody(w, r, status, jsonContentType, body)
}

// writeBody sends body with an Express-compatible weak ETag and answers
// matching conditional GETs with 304.
func writeBody(w http.ResponseWriter, r *http.Request, status int, contentType string, body []byte) {
	h := w.Header()
	etag := weakETag(body)
	h.Set("ETag", etag)
	if (r.Method == http.MethodGet || r.Method == http.MethodHead) && status >= 200 && status < 300 && etagMatches(r.Header.Get("If-None-Match"), etag) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	h.Set("Content-Type", contentType)
	h.Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	if r.Method != http.MethodHead {
		_, _ = w.Write(body)
	}
}

func weakETag(body []byte) string {
	sum := sha1.Sum(body)
	hash := base64.StdEncoding.EncodeToString(sum[:])[:27]
	return fmt.Sprintf(`W/"%x-%s"`, len(body), hash)
}

func etagMatches(header, etag string) bool {
	if header == "" {
		return false
	}
	if strings.TrimSpace(header) == "*" {
		return true
	}
	bare := strings.TrimPrefix(etag, "W/")
	for _, candidate := range strings.Split(header, ",") {
		if strings.TrimPrefix(strings.TrimSpace(candidate), "W/") == bare {
			return true
		}
	}
	return false
}
