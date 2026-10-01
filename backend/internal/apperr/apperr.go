// Package apperr defines client-facing errors. They are rendered in NestJS'
// exception body format (`statusCode`, `message`, `error`), which the Android
// client and admin panel already parse.
package apperr

import (
	"net/http"
	"strings"
)

// Error is an expected failure with an HTTP status and a message safe to show
// to API clients. Any other error type is reported as a 500.
type Error struct {
	Status  int
	Message string
	// Details carries per-field validation messages; when present it replaces
	// Message in the response body.
	Details []string
	// Bare omits the `error` label, like Nest's HttpException(string, status).
	Bare bool
}

func (e *Error) Error() string {
	if len(e.Details) > 0 {
		return strings.Join(e.Details, "; ")
	}
	return e.Message
}

func New(status int, message string) *Error {
	return &Error{Status: status, Message: message}
}

func BadRequest(message string) *Error { return New(http.StatusBadRequest, message) }

func Unauthorized(message string) *Error { return New(http.StatusUnauthorized, message) }

func NotFound(message string) *Error { return New(http.StatusNotFound, message) }

func Conflict(message string) *Error { return New(http.StatusConflict, message) }

func PayloadTooLarge(message string) *Error { return New(http.StatusRequestEntityTooLarge, message) }

func ServiceUnavailable(message string) *Error { return New(http.StatusServiceUnavailable, message) }

func Validation(details []string) *Error {
	return &Error{Status: http.StatusBadRequest, Message: "Bad Request", Details: details}
}

func TooManyRequests() *Error {
	return &Error{Status: http.StatusTooManyRequests, Message: "Too Many Requests", Bare: true}
}
