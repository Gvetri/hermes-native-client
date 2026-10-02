# syntax=docker/dockerfile:1
# =============================================================================
# Client-owned headless Hermes Gateway image for the exact-APK local live
# smoke gate (see docs/local-live-smoke.md).
#
# Build context: the audited Hermes source tree at the exact pinned revision.
# The revision is passed as HERMES_REVISION and stamped into the image as
# provenance. The smoke tool verifies the source revision, the tree digest
# and the built image identity; mutable refs (main/latest) are never accepted.
#
# Deliberate differences from the upstream production Dockerfile:
#   * no s6-overlay: the disposable fixture must not be supervised and never
#     restarts; the container exits with the gateway process;
#   * no Node/TUI/web assets; only the gateway runtime dependencies that the
#     frozen uv lockfile resolves for the `messaging` extra (aiohttp);
#   * no baked configuration and no host mounts: the smoke tool injects the
#     generated fixture config with `docker cp` before the container starts.
# =============================================================================

# ---------- Fixed SQLite ----------
# Debian 13 ships SQLite 3.46.1, which contains the upstream WAL-reset bug.
# The audited upstream image builds a fixed shared library from the same
# pinned tarball; mirror that runtime baseline instead of running the smoke
# gateway on the vulnerable system library.
FROM debian:13.4 AS sqlite_build
ARG SQLITE_AUTOCONF_VERSION=3530400
ARG SQLITE_SHA256=0e9483900e92cd5de8fd48d16bf9200145a61f7fd5be542a5ac81d8a9516eb9c
RUN apt-get -o Acquire::Retries=3 update && \
    apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
        build-essential ca-certificates curl && \
    rm -rf /var/lib/apt/lists/* && \
    (curl -fsSL --retry 1 --retry-all-errors --connect-timeout 15 --max-time 60 \
        -o /tmp/sqlite.tar.gz \
        "https://sqlite.org/2026/sqlite-autoconf-${SQLITE_AUTOCONF_VERSION}.tar.gz" || \
     curl -fsSL --retry 3 --retry-all-errors --connect-timeout 15 --max-time 120 \
        -o /tmp/sqlite.tar.gz \
        "https://sources.buildroot.net/sqlite/sqlite-autoconf-${SQLITE_AUTOCONF_VERSION}.tar.gz") && \
    printf '%s  %s\n' "${SQLITE_SHA256}" /tmp/sqlite.tar.gz > /tmp/sqlite.sha256 && \
    sha256sum -c /tmp/sqlite.sha256 && \
    tar -xzf /tmp/sqlite.tar.gz -C /tmp && \
    cd "/tmp/sqlite-autoconf-${SQLITE_AUTOCONF_VERSION}" && \
    CFLAGS="-O2 \
        -DSQLITE_ENABLE_FTS3 \
        -DSQLITE_ENABLE_FTS3_PARENTHESIS \
        -DSQLITE_ENABLE_FTS4 \
        -DSQLITE_ENABLE_FTS5 \
        -DSQLITE_ENABLE_RTREE \
        -DSQLITE_ENABLE_GEOPOLY \
        -DSQLITE_ENABLE_COLUMN_METADATA \
        -DSQLITE_ENABLE_UNLOCK_NOTIFY \
        -DSQLITE_ENABLE_DBSTAT_VTAB \
        -DSQLITE_ENABLE_DBPAGE_VTAB \
        -DSQLITE_ENABLE_MATH_FUNCTIONS \
        -DSQLITE_ENABLE_PREUPDATE_HOOK \
        -DSQLITE_ENABLE_SESSION \
        -DSQLITE_SECURE_DELETE \
        -DSQLITE_THREADSAFE=1 \
        -DSQLITE_MAX_VARIABLE_NUMBER=250000" \
        ./configure --prefix=/opt/sqlite-fixed --disable-static && \
    make -j"$(nproc)" && \
    make install

# ---------- Runtime image ----------
# Same audited base digest the upstream production image uses.
FROM ghcr.io/astral-sh/uv:0.11.6-python3.13-trixie@sha256:b3c543b6c4f23a5f2df22866bd7857e5d304b67a564f4feab6ac22044dde719b

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_PYTHON=3.13 \
    HERMES_HOME=/opt/data \
    HERMES_WRITE_SAFE_ROOT=/opt/data \
    HERMES_DISABLE_LAZY_INSTALLS=1

# Keep the base image PATH (the fixed sqlite install runs `ldconfig` from
# /usr/sbin) and only prepend the venv.
ENV PATH=/opt/hermes/.venv/bin:$PATH

RUN apt-get -o Acquire::Retries=3 update && \
    apt-get -o Acquire::Retries=3 install -y --no-install-recommends ca-certificates libc-bin && \
    rm -rf /var/lib/apt/lists/*

COPY --from=sqlite_build /opt/sqlite-fixed/lib/libsqlite3.so.3.53.4 /usr/local/lib/
RUN ln -sf libsqlite3.so.3.53.4 /usr/local/lib/libsqlite3.so.0 && \
    ln -sf libsqlite3.so.3.53.4 /usr/local/lib/libsqlite3.so && \
    printf '/usr/local/lib\n' > /etc/ld.so.conf.d/000-sqlite-fixed.conf && \
    ldconfig && \
    python3 -c "import sqlite3, sys; \
v = sqlite3.sqlite_version_info; \
sys.exit(f'linked SQLite {sqlite3.sqlite_version} still has the WAL-reset bug') if v < (3, 51, 3) else None; \
db = sqlite3.connect(':memory:'); \
db.execute(\"CREATE VIRTUAL TABLE docs USING fts5(content, tokenize='trigram')\"); \
db.execute(\"INSERT INTO docs VALUES ('hermes')\"); \
sys.exit('SQLite FTS5 trigram self-test failed') if db.execute(\"SELECT count(*) FROM docs WHERE docs MATCH 'erm'\").fetchone()[0] != 1 else None; \
db.close()"

WORKDIR /opt/hermes

# Layer-cached Python dependency install from the frozen lockfile. The project
# itself is installed after the full source copy so dependency layers survive
# source-only rebuilds.
COPY pyproject.toml uv.lock ./
RUN touch README.md && \
    uv sync --frozen --no-install-project --extra messaging --no-cache
COPY . .
RUN uv sync --frozen --extra messaging --no-cache && \
    .venv/bin/python -c "import sqlite3, sys; sys.exit('SQLite did not resolve to the fixed library') if sqlite3.sqlite_version_info < (3, 51, 3) else None"

# ---------- Provenance ----------
# Image identity is distinct from dependency/base provenance:
#   * revision/title/description describe the audited Hermes source built here;
#   * base.name/base.digest describe the pinned dependency base image.
# The stamps under /opt/hermes are verified against the runtime source tree by
# the smoke tool (see livesmoke/fixture.py verify_image_source).
ARG HERMES_REVISION
ARG HERMES_SOURCE_TREE=unverified
ARG HERMES_SOURCE_VERSION=unverified
LABEL org.opencontainers.image.title="hermes-live-smoke-gateway" \
      org.opencontainers.image.description="Disposable headless Hermes Gateway fixture for the hermes-native-client exact-APK local live smoke gate. Not a production image." \
      org.opencontainers.image.revision="${HERMES_REVISION}" \
      org.opencontainers.image.version="${HERMES_SOURCE_VERSION}" \
      org.opencontainers.image.source="https://github.com/NousResearch/hermes-agent" \
      org.opencontainers.image.base.name="ghcr.io/astral-sh/uv:0.11.6-python3.13-trixie" \
      org.opencontainers.image.base.digest="sha256:b3c543b6c4f23a5f2df22866bd7857e5d304b67a564f4feab6ac22044dde719b"

# Non-root runtime user (same uid the upstream image uses). The gateway
# refuses to run as root inside the official image, and the fixture must not
# weaken that guard. $HERMES_HOME lives in the writable container layer and is
# destroyed with the container.
ENV HOME=/opt/data
RUN test -n "${HERMES_REVISION}" && \
    useradd --home-dir /opt/data --uid 10000 --shell /usr/sbin/nologin hermes && \
    mkdir -p /opt/data && \
    chown -R 10000:10000 /opt/data && \
    chmod 700 /opt/data && \
    printf '%s\n' "${HERMES_REVISION}" > /opt/hermes/.hermes_build_sha && \
    printf '%s\n' "${HERMES_SOURCE_TREE}" > /opt/hermes/.hermes_source_tree && \
    printf '%s\n' "${HERMES_SOURCE_VERSION}" > /opt/hermes/.hermes_source_version

USER 10000

EXPOSE 8642

# Direct venv entrypoint: no s6, no supervisor, no restart. The smoke tool
# creates the container, copies the generated config to /opt/data/config.yaml,
# then starts it and treats any exit as fixture failure.
CMD ["/opt/hermes/.venv/bin/hermes", "gateway", "run", "--no-supervise"]
