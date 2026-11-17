FROM golang:1.27.3-alpine AS caddy-build
WORKDIR /src
RUN go mod init finance.shilling/security-caddy \
    && go get github.com/caddyserver/caddy/v2/cmd/caddy@v2.11.7 golang.org/x/net@v0.60.0 \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /usr/bin/caddy github.com/caddyserver/caddy/v2/cmd/caddy \
    && mkdir -p /data/caddy /config/caddy /srv

FROM cgr.dev/chainguard/static:latest
COPY --from=caddy-build /usr/bin/caddy /usr/bin/caddy
COPY --from=caddy-build --chown=65532:65532 /data /data
COPY --from=caddy-build --chown=65532:65532 /config /config
COPY --from=caddy-build --chown=65532:65532 /srv /srv
ENV XDG_CONFIG_HOME=/config XDG_DATA_HOME=/data
USER 65532:65532

ARG VERSION
LABEL org.opencontainers.image.source="https://github.com/j1philli/shilling" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.description="Shilling self-hosted web app"

COPY Caddyfile /etc/caddy/Caddyfile
COPY . /srv

EXPOSE 80

ENTRYPOINT ["/usr/bin/caddy"]
CMD ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
