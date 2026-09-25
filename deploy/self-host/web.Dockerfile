FROM caddy:2-alpine

ARG VERSION
LABEL org.opencontainers.image.source="https://github.com/j1philli/shilling" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.description="Shilling self-hosted web app"

COPY Caddyfile /etc/caddy/Caddyfile
COPY . /srv

EXPOSE 80
