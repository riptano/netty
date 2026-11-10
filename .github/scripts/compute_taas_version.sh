#!/bin/bash
# ----------------------------------------------------------------------------
# Compute the artifact version to publish to IBM TaaS Artifactory.
#
# Usage:
#   compute_taas_version.sh <mode> <pom-version>
#
# Modes:
#   release   →  <pom-version>             e.g. 4.1.137.1.dse
#   snapshot  →  <pom-version>-SNAPSHOT    e.g. 4.1.137.1.dse-SNAPSHOT
#
# The pom.xml version is the single source of truth. To publish a new release
# version, bump the version in pom.xml first (as for any other fork).
#
# Environment (all required):
#   TAAS_API_URL    base URL  e.g. https://na.artifactory.swg-devops.com/artifactory
#   TAAS_REPO       repository name
#   TAAS_USERNAME / TAAS_PASSWORD
#
# Writes  version=<value>  to $GITHUB_OUTPUT (or stdout when not set).
#
# Release builds are immutable: if netty-all is already published at the
# release version the script fails instead of overwriting it.
# ----------------------------------------------------------------------------
set -euo pipefail

MODE="${1:?usage: $0 <mode> <pom-version>}"
POM_VERSION="${2:?usage: $0 <mode> <pom-version>}"
: "${TAAS_API_URL:?}" "${TAAS_REPO:?}" "${TAAS_USERNAME:?}" "${TAAS_PASSWORD:?}"

# Guard: refuse to run if the pom version already ends in -SNAPSHOT.
#
# This happens on upstream mid-development commits, e.g.
#   [maven-release-plugin] prepare for next development iteration
#     <version>4.1.137.Final-SNAPSHOT</version>
# Release mode would publish a SNAPSHOT as a release, and snapshot mode would
# produce a "-SNAPSHOT-SNAPSHOT" version. Trigger the pipeline on a branch whose
# pom.xml is at a final version (e.g. 4.1.137.Final or 4.1.137.1.dse).
guard_no_upstream_snapshot() {
    local v="$1"
    if [[ "$v" == *-SNAPSHOT ]]; then
        echo "ERROR: pom version is '${v}' which already ends in -SNAPSHOT." >&2
        echo "       Trigger the pipeline on a branch whose pom.xml is at a final version" >&2
        echo "       (e.g. 4.1.137.Final or 4.1.137.1.dse)." >&2
        exit 1
    fi
}

# Lightweight connectivity + auth check. Fails fast with a clear message so
# the runner doesn't waste 40 minutes building before hitting a 401.
check_access() {
    local status body
    body="$(mktemp)"
    status="$(curl -sS -o "$body" -w '%{http_code}' --max-time 30 --retry 2 \
        -u "${TAAS_USERNAME}:${TAAS_PASSWORD}" -H 'Accept: application/json' \
        "${TAAS_API_URL}/api/storage/${TAAS_REPO}/")"
    case "$status" in
        200) ;;
        401|403)
            echo "ERROR: HTTP $status from TaaS — check TAAS_USERNAME / TAAS_PASSWORD" >&2
            exit 1 ;;
        404)
            echo "ERROR: repository '${TAAS_REPO}' not found at ${TAAS_API_URL}" >&2
            exit 1 ;;
        000)
            echo "ERROR: could not reach ${TAAS_API_URL} — is the host reachable from this runner?" >&2
            exit 1 ;;
        *)
            echo "ERROR: unexpected HTTP $status from ${TAAS_API_URL}" >&2
            exit 1 ;;
    esac
}

# Release versions must not be overwritten.
guard_release_not_published() {
    local v="$1" status
    status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 --retry 2 \
        -u "${TAAS_USERNAME}:${TAAS_PASSWORD}" \
        "${TAAS_API_URL}/api/storage/${TAAS_REPO}/io/netty/netty-all/${v}")"
    case "$status" in
        404) ;;
        200)
            echo "ERROR: release ${v} is already published to ${TAAS_REPO}." >&2
            echo "       Bump the version in pom.xml, or publish a snapshot instead." >&2
            exit 1 ;;
        *)
            echo "ERROR: unexpected HTTP $status checking for existing release ${v}" >&2
            exit 1 ;;
    esac
}

guard_no_upstream_snapshot "$POM_VERSION"
check_access

case "$MODE" in
    release)
        VERSION="$POM_VERSION"
        guard_release_not_published "$VERSION"
        ;;
    snapshot)
        VERSION="${POM_VERSION}-SNAPSHOT"
        ;;
    *)
        echo "ERROR: unsupported mode '$MODE' (expected: release | snapshot)" >&2
        exit 1 ;;
esac

echo "Computed version: $VERSION" >&2
echo "version=$VERSION" >> "${GITHUB_OUTPUT:-/dev/stdout}"
