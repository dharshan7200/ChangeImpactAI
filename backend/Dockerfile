# syntax=docker/dockerfile:1.7
# ChangeImpact AI backend: Spring Boot 4 on Java 21.
#   docker build -f backend/Dockerfile -t changeimpact-backend .      (context: repository root)
# Behind a proxy: --build-arg HTTPS_PROXY=... [--secret id=ca_cert,src=ca.pem]. Smaller image: --build-arg INSTALL_SEMGREP=false

# ---------- build ----------
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY docker/proxy-env.sh /usr/local/bin/proxy-env.sh
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=ca_cert,required=false \
    . /usr/local/bin/proxy-env.sh && mvn -B -q -DskipTests dependency:go-offline
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=ca_cert,required=false \
    . /usr/local/bin/proxy-env.sh && mvn -B -q -DskipTests package \
    && cp target/changeimpact-ai-*.jar /app.jar

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS jre

# Runtime: Python (for the optional Semgrep CLI) + the Temurin JRE copied in. No OS package manager is needed,
# so the image builds wherever PyPI and Docker Hub are reachable. Debian (glibc) rather than Alpine (musl): the
# tree-sitter natives used for Python analysis (P6-7) are built for glibc.
FROM python:3.13-slim AS runtime
ARG INSTALL_SEMGREP=true
ARG SEMGREP_VERSION=1.182.1
# Debian security updates for the base image; fails the build if the mirrors cannot be reached (false skips them)
ARG APPLY_OS_UPDATES=true
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY docker/proxy-env.sh /usr/local/bin/proxy-env.sh
RUN --mount=type=secret,id=ca_cert,required=false set -eu; . /usr/local/bin/proxy-env.sh; \
    # Debian security updates of the base image (HTTPS mirrors; the optional build CA only for this call)
    sed -i 's#http://#https://#g' /etc/apt/sources.list.d/debian.sources; \
    if [ "$APPLY_OS_UPDATES" = "true" ]; then \
      # apt downloads as the unprivileged _apt user, which cannot read the root-only secret: use a temporary copy
      APT_CA=""; if [ -f /run/secrets/ca_cert ]; then \
        cp /run/secrets/ca_cert /tmp/apt-build-ca.pem; chmod 644 /tmp/apt-build-ca.pem; \
        APT_CA="-o Acquire::https::CAInfo=/tmp/apt-build-ca.pem"; fi; \
      apt-get $APT_CA -o APT::Update::Error-Mode=any update -qq; \
      DEBIAN_FRONTEND=noninteractive apt-get $APT_CA upgrade -y -qq --no-install-recommends; \
      apt-get clean; rm -rf /var/lib/apt/lists/* /tmp/apt-build-ca.pem; \
    fi; \
    if [ "$INSTALL_SEMGREP" = "true" ]; then \
      python3 -m venv /opt/semgrep; \
      /opt/semgrep/bin/pip install --no-cache-dir --only-binary=:all: "semgrep==${SEMGREP_VERSION}"; \
      # security fixes over Semgrep's resolved transitive versions (Trivy, Nov 2026); pip check guards compatibility
      /opt/semgrep/bin/pip install --no-cache-dir --only-binary=:all: --upgrade \
        "urllib3>=2.8.0" "msgpack>=1.2.3" "setuptools>=78.1.1"; \
      /opt/semgrep/bin/pip check; \
      semgrep_ok=$(/opt/semgrep/bin/semgrep --version); echo "semgrep $semgrep_ok"; \
      # pip (and its vendored urllib3/msgpack) is not needed at runtime
      /opt/semgrep/bin/python -m pip uninstall -y -q pip; \
      ln -s /opt/semgrep/bin/semgrep /usr/local/bin/semgrep; \
    fi; \
    keytool -delete -cacerts -storepass changeit -alias build-extra-ca >/dev/null 2>&1 || true; \
    for a in $(keytool -list -cacerts -storepass changeit 2>/dev/null | grep -o '^build-extra-[^,]*'); do \
      keytool -delete -cacerts -storepass changeit -alias "$a" >/dev/null; done; \
    python3 -m pip uninstall -y -q pip || true; \
    rm -f /usr/local/bin/proxy-env.sh; \
    groupadd --system --gid 10001 app; \
    useradd --system --uid 10001 --gid app --home-dir /var/lib/changeimpact --no-create-home --shell /usr/sbin/nologin app; \
    mkdir -p /var/lib/changeimpact/workspaces /var/lib/changeimpact/libraries /opt/app; chown -R app:app /var/lib/changeimpact

COPY --from=build /app.jar /opt/app/app.jar
USER app
WORKDIR /var/lib/changeimpact
ENV APP_PROFILE=prod \
    ANALYSIS_LANGUAGES=all \
    INGESTION_WORKSPACE_PATH=/var/lib/changeimpact/workspaces \
    LIBRARY_CACHE_PATH=/var/lib/changeimpact/libraries \
    SEMGREP_SEND_METRICS=off \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME ["/var/lib/changeimpact/workspaces", "/var/lib/changeimpact/libraries"]
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
    CMD python3 -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/actuator/health' % os.environ.get('SERVER_PORT', '8080'), timeout=4)" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/app/app.jar"]
