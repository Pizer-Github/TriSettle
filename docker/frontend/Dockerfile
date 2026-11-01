# ─────────────────────────────────────────────────────────────────────────────
# Frontend Dockerfile — Angular dev server
# For production, replace with nginx + static build (Milestone 2+).
# ─────────────────────────────────────────────────────────────────────────────

FROM node:20-alpine

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy package files first (layer cache)
COPY package.json package-lock.json ./

# Install dependencies
RUN npm ci --prefer-offline

# Copy source
COPY . .

# Ensure non-root ownership
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 4200

CMD ["npx", "ng", "serve", "--host", "0.0.0.0", "--port", "4200", "--disable-host-check"]
