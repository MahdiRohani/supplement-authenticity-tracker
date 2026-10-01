FROM golang:1.26-alpine AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/api ./cmd/api

FROM gcr.io/distroless/static-debian12:nonroot

WORKDIR /app
COPY --from=build /out/api /app/api

EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 CMD ["/app/api", "healthcheck"]
# Migrations are embedded and applied on start (AUTO_MIGRATE=true by default).
ENTRYPOINT ["/app/api"]
