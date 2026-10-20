#!/bin/bash
# ----------------------------------------------------------------------------
# Compute the artifact version to publish to IBM TaaS Artifactory.
#
# Usage:
#   compute_taas_version.sh <mode> <pom-version>
#
# Modes:
#   release   →  <upstream>.<N>.dse      e.g. 4.1.137.2.dse
#   snapshot  →  <upstream>-SNAPSHOT     e.g. 4.1.137-SNAPSHOT
#
# Environment (all required):
#   TAAS_API_URL    base URL  e.g. https://na.artifactory.swg-devops.com/artifactory
#   TAAS_REPO       repository name
#   TAAS_USERNAME / TAAS_PASSWORD
#
# Writes  version=<value>  to $GITHUB_OUTPUT (or stdout when not set).
#
# Version counter logic (release only):
#   Queries the netty-all folder under TAAS_REPO for the highest N already
#   published as <upstream>.N.dse and uses N+1. If the folder does not exist
#   yet (first-ever publish → 404) the counter starts at 1.
#   netty-all is the last artifact uploaded by deploy_maven_packages.sh, so
#   a partially published release is not counted and a retry will reuse the
#   same version rather than skipping to N+2.
# ----------------------------------------------------------------------------
set -euo pipefail

MODE="${1:?usage: $0 <mode> <pom-version>}"
POM_VERSION="${2:?usage: $0 <mode> <pom-version>}"
: "${TAAS_API_URL:?}" "${TAAS_REPO:?}" "${TAAS_USERNAME:?}" "${TAAS_PASSWORD:?}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Guard: refuse to run if the pom version still ends in -SNAPSHOT.
#
# This happens when the pipeline is triggered on a commit that sits between
# the upstream "prepare for next development iteration" commit (which sets
# the version to e.g. 4.1.137.Final-SNAPSHOT) and the upstream "prepare
# release" commit (which changes it to 4.1.137.Final).
#
# Confirmed from git history in riptano/netty:
#   commit 7163618573  [maven-release-plugin] prepare for next development iteration
#     - <version>4.1.136.Final</version>
#     + <version>4.1.137.Final-SNAPSHOT</version>
#   commit 5471f0a6ff  [maven-release-plugin] prepare release netty-4.1.137.Final
#     - <version>4.1.137.Final-SNAPSHOT</version>
#     + <version>4.1.137.Final</version>
#
# The correct action is always to trigger the pipeline on the upstream
# release tag commit (4.1.137.Final) or on a DSE branch that starts from it
# (4.1.137.1.dse). Never on a mid-development commit.
guard_no_upstream_snapshot() {
    local v="$1"
    if [[ "$v" == *-SNAPSHOT ]]; then
        echo "ERROR: pom version is '${v}' which ends in -SNAPSHOT." >&2
        echo "       This means the pipeline was triggered on an upstream mid-development" >&2
        echo "       commit, not on a release tag or a DSE branch." >&2
        echo "       Trigger the pipeline on a branch whose pom.xml is at a final version" >&2
        echo "       (e.g. 4.1.137.Final or 4.1.137.1.dse)." >&2
        echo "       See: .github/workflows/README-publish-to-taas.md — Version Scheme" >&2
        exit 1
    fi
}

# Extract the N.N.N upstream triplet from any pom version we may encounter:
#   4.1.137.1.dse  → 4.1.137
#   4.1.137.Final  → 4.1.137
#   4.1.137        → 4.1.137
upstream_of() {
    local v="$1"
    if [[ "$v" =~ ^([0-9]+\.[0-9]+\.[0-9]+)(\.[0-9]+\.dse|\.[A-Za-z][A-Za-z0-9]*)?$ ]]; then
        echo "${BASH_REMATCH[1]}"
    else
        echo "ERROR: cannot parse upstream triplet from pom version '$v'" >&2
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

# ---------------------------------------------------------------------------
# Release version: query Artifactory for highest N, return N+1
# ---------------------------------------------------------------------------
next_release_version() {
    local upstream="$1" body status max next
    body="$(mktemp)"
    status="$(curl -sS -o "$body" -w '%{http_code}' --max-time 30 --retry 2 \
        -u "${TAAS_USERNAME}:${TAAS_PASSWORD}" -H 'Accept: application/json' \
        "${TAAS_API_URL}/api/storage/${TAAS_REPO}/io/netty/netty-all")"

    if [ "$status" = "200" ]; then
        max="$(jq -r --arg up "$upstream" '
            .children[]?
            | select(.folder == true)
            | .uri | ltrimstr("/")
            | capture("^" + ($up | gsub("\\."; "\\.")) + "\\.(?<n>[0-9]+)\\.dse$")?
            | .n | tonumber' "$body" 2>/dev/null \
            | sort -n | tail -1)"
    elif [ "$status" = "404" ]; then
        max=""   # nothing published yet for any upstream version
    else
        echo "ERROR: HTTP $status listing netty-all versions" >&2
        exit 1
    fi

    next=$(( ${max:-0} + 1 ))
    echo "Highest published build for ${upstream}: ${max:-none} → next is ${next}" >&2
    echo "${upstream}.${next}.dse"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
guard_no_upstream_snapshot "$POM_VERSION"
check_access
UPSTREAM="$(upstream_of "$POM_VERSION")"

case "$MODE" in
    release)
        VERSION="$(next_release_version "$UPSTREAM")"
        ;;
    snapshot)
        # Include the short commit SHA so each commit produces a distinct
        # snapshot version. This lets developers identify exactly which
        # rework they are testing against in BDP.
        # Format: <upstream>-<sha>-SNAPSHOT  e.g. 4.1.137-a3f5b2c-SNAPSHOT
        #
        # Each commit produces a separate entry in TaaS — snapshots do NOT
        # overwrite each other. Periodic cleanup may be needed over time.
        GIT_SHA="$(git rev-parse --short HEAD)"
        VERSION="${UPSTREAM}-${GIT_SHA}-SNAPSHOT"
        ;;
    *)
        echo "ERROR: unsupported mode '$MODE' (expected: release | snapshot)" >&2
        exit 1 ;;
esac

echo "Computed version: $VERSION" >&2
echo "version=$VERSION" >> "${GITHUB_OUTPUT:-/dev/stdout}"
