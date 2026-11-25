# syntax=docker/dockerfile:1.7
# ChangeImpact AI web frontend (React/Vite) served by nginx, which also proxies /api to the backend.
#   docker build -f frontend/Dockerfile -t changeimpact-frontend .      (context: repository root)

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY docker/proxy-env.sh /usr/local/bin/proxy-env.sh
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm --mount=type=secret,id=ca_cert,required=false \
    . /usr/local/bin/proxy-env.sh && npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- runtime ----------
FROM nginx:1.30-alpine
# Alpine security updates (e.g. libxml2 CVE-2026-86144, fixed after the nginx 1.30 image was published). Needs
# dl-cdn.alpinelinux.org; APPLY_OS_UPDATES=false skips it. A build CA (proxy) is used for this step only.
ARG APPLY_OS_UPDATES=true
RUN --mount=type=secret,id=ca_cert,required=false set -eu; \
    if [ "$APPLY_OS_UPDATES" = "true" ]; then \
      if [ -f /run/secrets/ca_cert ]; then cat /etc/ssl/certs/ca-certificates.crt /run/secrets/ca_cert > /tmp/build-ca.pem; \
        export SSL_CERT_FILE=/tmp/build-ca.pem; fi; \
      apk upgrade --no-cache; \
      rm -f /tmp/build-ca.pem; \
    fi
COPY frontend/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY frontend/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
ENV BACKEND_UPSTREAM=backend:8080 \
    CLIENT_MAX_BODY_SIZE=56m
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
