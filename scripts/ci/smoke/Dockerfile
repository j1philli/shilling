FROM node:22.23.4-bookworm-slim AS node

FROM mcr.microsoft.com/playwright:v1.63.0-noble
COPY --from=node /usr/local/ /usr/local/
WORKDIR /smoke
COPY package.json package-lock.json ./
RUN --mount=type=bind,from=npm-cache,target=/root/.npm,rw npm ci --offline --no-audit
COPY . .
CMD ["npx", "playwright", "test"]
