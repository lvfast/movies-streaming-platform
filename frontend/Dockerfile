# syntax=docker/dockerfile:1.7

FROM node:26.8-alpine AS build
WORKDIR /workspace

# The media gateway is a separate origin, so the deployed bundle has to know it: an unset value
# leaves protected playback unable to resolve its manifest, and the player reports that instead of
# sending the media token to the API origin.
ARG VITE_MEDIA_BASE_URL=""
ENV VITE_MEDIA_BASE_URL=${VITE_MEDIA_BASE_URL}

COPY frontend/package.json frontend/package-lock.json ./frontend/
WORKDIR /workspace/frontend
RUN --mount=type=cache,target=/root/.npm npm ci

COPY docs/api /workspace/docs/api
COPY frontend /workspace/frontend
RUN npm run build

FROM nginx:1.28-alpine AS runtime
ARG APP_VERSION=dev
ARG VCS_REF=unknown
ARG VCS_URL=""
LABEL org.opencontainers.image.title="LVFAST media streaming frontend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="${VCS_URL}"

RUN apk del --no-cache \
        nginx-module-acme \
        nginx-module-geoip \
        nginx-module-image-filter \
        nginx-module-njs \
        nginx-module-xslt \
    && sed -E -i 's/^(nginx[^=]*)=.*/\1/' /etc/apk/world \
    && apk upgrade --no-cache \
    && rm -rf /usr/share/nginx/html/* \
    && mkdir -p /usr/share/nginx/media /etc/nginx/templates \
    && chown -R nginx:nginx /usr/share/nginx
COPY --from=build --chown=nginx:nginx /workspace/frontend/dist /usr/share/nginx/html
COPY --chown=nginx:nginx media /usr/share/nginx/media
# Nginx cannot ship a static CSP for managed playback: the media gateway origin is a deployment
# value. The template is rendered at container start by the script below, which substitutes only
# MEDIA_ORIGIN and writes the result to /tmp so the Compose service can stay read-only.
COPY --chown=nginx:nginx frontend/nginx.conf.template /etc/nginx/templates/nginx.conf.template
COPY --chown=nginx:nginx frontend/docker-entrypoint.d/ /docker-entrypoint.d/
RUN chmod 0555 /docker-entrypoint.d/05-render-nginx-config.sh

USER nginx
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;", "-c", "/tmp/nginx.conf"]
