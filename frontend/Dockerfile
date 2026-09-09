# syntax=docker/dockerfile:1.7

FROM node:22.22.2-alpine AS build
WORKDIR /workspace

COPY frontend/package.json frontend/package-lock.json ./frontend/
WORKDIR /workspace/frontend
RUN --mount=type=cache,target=/root/.npm npm ci

COPY docs/api /workspace/docs/api
COPY frontend /workspace/frontend
RUN npm run build

FROM nginx:1.28-alpine AS runtime
ARG APP_VERSION=dev
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="LVFAST media streaming frontend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="https://github.com/lvfast/media-streaming-platform"

RUN apk del --no-cache \
        nginx-module-acme \
        nginx-module-geoip \
        nginx-module-image-filter \
        nginx-module-njs \
        nginx-module-xslt \
    && sed -E -i 's/^(nginx[^=]*)=.*/\1/' /etc/apk/world \
    && apk upgrade --no-cache \
    && rm -rf /usr/share/nginx/html/* \
    && mkdir -p /usr/share/nginx/media \
    && chown -R nginx:nginx /usr/share/nginx
COPY --from=build --chown=nginx:nginx /workspace/frontend/dist /usr/share/nginx/html
COPY --chown=nginx:nginx media /usr/share/nginx/media
COPY --chown=nginx:nginx frontend/nginx.conf /etc/nginx/nginx.conf

USER nginx
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT []
CMD ["nginx", "-g", "daemon off;"]
