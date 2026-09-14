#!/bin/sh
# Render the Nginx configuration from its template and substitute only the deployment origins.
#
# The image keeps the stock Nginx entrypoint, which processes this directory before starting Nginx.
# The rendered file goes to /tmp because the Compose service runs this container with a read-only
# root filesystem; Nginx only has to read its configuration. Only MEDIA_ORIGIN (playback gateway)
# and MEDIA_STORAGE_ORIGIN (browser-facing source bucket) are substituted, so Nginx runtime
# variables such as $uri and $host stay untouched.
set -eu

template=/etc/nginx/templates/nginx.conf.template
rendered=/tmp/nginx.conf

envsubst '${MEDIA_ORIGIN} ${MEDIA_STORAGE_ORIGIN}' < "$template" > "$rendered"
nginx -t -c "$rendered"
