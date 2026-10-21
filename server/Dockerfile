# Guardian server — image for any container host (Render, Railway, Fly, VPS)
FROM node:22-slim

WORKDIR /app

# Install the dependencies first so the layer is cached.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY src ./src
COPY public ./public
COPY scripts ./scripts

ENV NODE_ENV=production \
    PORT=3000 \
    DATA_DIR=/data \
    UPLOAD_DIR=/data/uploads \
    MEDIA_DRIVER=db

EXPOSE 3000

CMD ["node", "src/index.js"]
