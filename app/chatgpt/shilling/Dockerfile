FROM node:22-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
COPY assets ./assets
COPY dist ./dist
ENV SHILLING_CHATGPT_BIND=0.0.0.0
ENV SHILLING_CHATGPT_PORT=8787
USER node
EXPOSE 8787
CMD ["node", "src/server.mjs"]
