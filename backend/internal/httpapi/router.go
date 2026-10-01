package httpapi

import (
	"net/http"
	"net/url"
	"strings"
)

// The router reproduces Express' default matching, which clients relied on:
// static segments are case-insensitive, one trailing slash is optional, HEAD
// is served by GET routes, routes match in registration order, and a path
// that matches no route for the method is a 404 (never 405).

type request struct {
	*http.Request
	params map[string]string
	body   jsonBody
}

func (r *request) param(name string) string {
	return r.params[name]
}

type handlerFunc func(w http.ResponseWriter, r *request) error

type segment struct {
	literal string
	param   string
}

type route struct {
	method   string
	segments []segment
	public   bool
	limit    limitClass
	handle   handlerFunc
}

type routeOption func(*route)

func public(rt *route) { rt.public = true }

func limited(class limitClass) routeOption {
	return func(rt *route) { rt.limit = class }
}

func (s *Server) handle(method, pattern string, h handlerFunc, opts ...routeOption) {
	rt := &route{method: method, handle: h}
	for _, part := range strings.Split(strings.Trim(pattern, "/"), "/") {
		if name, ok := strings.CutPrefix(part, ":"); ok {
			rt.segments = append(rt.segments, segment{param: name})
		} else {
			rt.segments = append(rt.segments, segment{literal: part})
		}
	}
	for _, opt := range opts {
		opt(rt)
	}
	s.routes = append(s.routes, rt)
}

func (s *Server) match(r *http.Request) (*route, map[string]string) {
	path := r.URL.EscapedPath()
	if len(path) > 1 {
		path = strings.TrimSuffix(path, "/")
	}
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")

	method := r.Method
	if method == http.MethodHead {
		method = http.MethodGet
	}
	for _, rt := range s.routes {
		if rt.method != method || len(rt.segments) != len(parts) {
			continue
		}
		if params, ok := rt.matchParts(parts); ok {
			return rt, params
		}
	}
	return nil, nil
}

func (rt *route) matchParts(parts []string) (map[string]string, bool) {
	var params map[string]string
	for i, seg := range rt.segments {
		part := parts[i]
		if seg.param == "" {
			if !strings.EqualFold(seg.literal, part) {
				return nil, false
			}
			continue
		}
		if part == "" {
			return nil, false
		}
		if decoded, err := url.PathUnescape(part); err == nil {
			part = decoded
		}
		if params == nil {
			params = map[string]string{}
		}
		params[seg.param] = part
	}
	return params, true
}
