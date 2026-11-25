#!/bin/sh
# Build-time helper (sourced inside Dockerfile RUN steps): makes Maven, npm and pip work behind an HTTP(S) proxy and
# with an extra CA certificate (corporate TLS inspection). Does nothing when neither is configured.
#   docker build --build-arg HTTPS_PROXY=http://proxy:3128 --secret id=ca_cert,src=/path/ca.pem ...
P="${HTTPS_PROXY:-${https_proxy:-}}"
if [ -n "$P" ]; then
  HOSTPORT=$(echo "$P" | sed -E 's#^[a-zA-Z]+://##; s#/.*$##; s#^.*@##')
  PH="${HOSTPORT%:*}"; PP="${HOSTPORT##*:}"
  export MAVEN_OPTS="${MAVEN_OPTS:-} -Dhttps.proxyHost=$PH -Dhttps.proxyPort=$PP -Dhttp.proxyHost=$PH -Dhttp.proxyPort=$PP"
fi
if [ -f /run/secrets/ca_cert ]; then
  export NODE_EXTRA_CA_CERTS=/run/secrets/ca_cert PIP_CERT=/run/secrets/ca_cert REQUESTS_CA_BUNDLE=/run/secrets/ca_cert
  if command -v keytool >/dev/null 2>&1; then
    # keytool imports one certificate per call: split the PEM bundle and import each one
    _d=$(mktemp -d)
    awk -v d="$_d" '/-----BEGIN CERTIFICATE-----/{n++} n{print > (d "/cert-" n ".pem")}' /run/secrets/ca_cert
    for _c in "$_d"/cert-*.pem; do
      [ -f "$_c" ] && keytool -importcert -noprompt -cacerts -storepass changeit -alias "build-extra-$(basename "$_c" .pem)" -file "$_c" >/dev/null 2>&1 || true
    done
    rm -rf "$_d"
  fi
fi
