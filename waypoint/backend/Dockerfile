# syntax=docker/dockerfile:1.7

FROM golang:1.23-alpine AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux GOTOOLCHAIN=local
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -trimpath -ldflags="-s -w" -o /out/waypoint-api ./cmd/server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/waypoint-api /usr/local/bin/waypoint-api
USER nonroot:nonroot
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=4s --start-period=20s --retries=3 \
    CMD ["/usr/local/bin/waypoint-api", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/waypoint-api"]
