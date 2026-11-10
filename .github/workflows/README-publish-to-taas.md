# Publish to IBM TaaS Artifactory

## Overview

[`publish-to-taas.yml`](publish-to-taas.yml) publishes our internal Netty fork to IBM TaaS Artifactory. The target repository depends on the mode:

| Mode | Repository |
|---|---|
| `release` | `https://na.artifactory.swg-devops.com/artifactory/datastax-team-nosql-releases-maven-local` |
| `snapshot` | `https://na.artifactory.swg-devops.com/artifactory/datastax-team-nosql-snapshots-maven-local` |

No Jenkins macOS instances are needed — macOS builds use free GitHub-hosted runners (`macos-15` and `macos-15-intel`).

---

## Workflow Architecture

```
You (Actions tab → Run workflow)
        │ branch, mode (release | snapshot)
        ▼
┌──────────────────────────────────────────────┐
│  Stage 0 · validate  (ubuntu)                │
│  • Checks TaaS credentials before building   │
│  • Reads pom.xml version                     │
│  • Computes version string (see below)       │
│  • Outputs: version, deploy_url               │
└──────────────┬───────────────────────────────┘
               │ (version passed to all stages)
    ┌──────────┼──────────┬─────────────┐
    ▼          ▼          ▼             ▼
 Stage 1    Stage 2    Stage 3       Stage 4
 Linux      Linux      macOS         macOS
 x86_64     aarch64    x86_64        aarch64
 Docker/    ARM        Intel         Apple
 CentOS6    runner     runner        Silicon
 Full build  epoll,     kqueue,       kqueue,
 all JARs   unix-common dns-native    dns-native
    │          │          │             │
    └──────────┴──────────┴─────────────┘
               │ (GitHub artifact storage — no TaaS auth)
               ▼
┌──────────────────────────────────────────────┐
│  Stage 5 · publish  (ubuntu)                 │
│  • Downloads all 4 platform artifacts        │
│  • Merges into single staging directory      │
│  • Builds netty-all (aggregation JAR) last   │
│  • Deploys to TaaS Artifactory               │
│  • Release only: git tag push       │
└──────────────────────────────────────────────┘
```

Stages 1–4 run in parallel after Stage 0 completes.

---

## Trigger

Manual dispatch only (`workflow_dispatch`). To trigger:

1. Go to the **Actions** tab → **Publish DataStax Netty to IBM TaaS Artifactory**
2. Click **Run workflow**
3. Select `branch` and `mode`
4. Click **Run workflow**

| Parameter | Required | Values |
|---|---|---|
| `branch` | Yes | Branch to build from, e.g. `dse-netty-4.1.137.2.dse` |
| `mode` | Yes | `release` or `snapshot` |

---

## Version Scheme

The version always comes from `pom.xml` — the pipeline never invents a version number.

| Mode | Published version | Example (pom = `4.1.137.1.dse`) | Git tag |
|---|---|---|---|
| `release` | `<pom-version>` | `4.1.137.1.dse` | `4.1.137.1.dse` |
| `snapshot` | `<pom-version>-SNAPSHOT` | `4.1.137.1.dse-SNAPSHOT` | none |

- **Release** is immutable. If `io/netty/netty-all/<version>` already exists in TaaS, Stage 0 fails with a clear message. To publish a new release, bump the version in `pom.xml` first (same as for the other forks), then run the workflow.
- **Snapshot** is for testing in BDP before cutting a release. Re-running it overwrites the previous snapshot of the same pom version; TaaS keeps timestamped copies, and consumers resolve the latest.
- The pipeline stamps the computed version into the poms **inside the build only** (`versions:set`). Nothing is committed back to the branch.

### Accepted pom versions

| pom version | Accepted | Notes |
|---|---|---|
| `4.1.137.1.dse` | yes | Normal DSE version |
| `4.1.137.Final` | yes | Upstream release tag |
| `4.1.137.Final-SNAPSHOT` | **hard fail** | Upstream mid-development commit; would produce `-SNAPSHOT-SNAPSHOT` (or a SNAPSHOT published as a release) |

For the failing case, Stage 0 stops with:

```
ERROR: pom version is '4.1.137.Final-SNAPSHOT' which already ends in -SNAPSHOT.
       Trigger the pipeline on a branch whose pom.xml is at a final version
       (e.g. 4.1.137.Final or 4.1.137.1.dse).
```

### SNAPSHOT file names

`mvn deploy` to a `file://` repository writes SNAPSHOTs with timestamped names, which a local `~/.m2` cannot resolve. Before building `netty-all`, Stage 5 runs [`snapshot_staging_normalize.sh`](../scripts/snapshot_staging_normalize.sh) to rename them to plain `-SNAPSHOT` names. It does nothing for release versions.

---

## Required Secrets

Both secrets are already set on `riptano/netty`. The values are in **1Password → DSE-Shared → "IBM TaaS JFrog: datastax-enterprise-automation Identity Token"**.

| Secret | Value |
|---|---|
| `TAAS_USERNAME` | `datastax-enterprise-automation@ibm.com` |
| `TAAS_PASSWORD` | Identity token from 1Password |

No SSH deploy key is needed. The release git tag push uses the workflow's built-in `GITHUB_TOKEN` with `contents: write` permission.

---

## Consuming Published Artifacts

BDP resolves from the IBM virtual repo. In `~/.gradle/gradle.properties` (not in the BDP repo), point it at IBM Artifactory:

```properties
datastaxArtifactRepositoryUrls = https://na.artifactory.swg-devops.com/artifactory/datastax-team-nosql-dse-maven-virtual
datastaxPluginRepositoryUrls = https://na.artifactory.swg-devops.com/artifactory/datastax-team-nosql-dse-maven-virtual
datastaxRepositoryUsername = <TaaS username>
datastaxRepositoryPassword = <TaaS identity token>
```

Remove the legacy `datastaxArtifactRepositoryUrl` setting if present (see `BUILDING.md` in BDP).

Then set the version in BDP's `build.properties`:

```properties
nettyVersion = 4.1.137.1.dse            # release
# nettyVersion = 4.1.137.1.dse-SNAPSHOT  # snapshot, for testing
```

If a snapshot is re-published, run Gradle with `--refresh-dependencies` so it doesn't use a cached copy.

Alternatively, download the `merged-local-staging` GitHub artifact from a completed run and add it to BDP with `-DextraRepositoriesConfig=<file>` containing `url = 'file:///path/to/merged-local-staging'`.

---

## Build Times (approximate)

| Stage | Duration |
|---|---|
| Stage 0 (validate) | ~1 min |
| Stage 1 (Linux x86_64) | 15–25 min |
| Stage 2 (Linux aarch64) | 10–15 min |
| Stage 3 (macOS Intel) | 10–15 min |
| Stage 4 (macOS ARM) | 10–15 min |
| Stage 5 (merge + publish) | 5–10 min |
| **Total wall-clock** | **~30–45 min** |

Stages 1–4 run in parallel.

---

## Troubleshooting

**Stage 0 fails with HTTP 401/403:** The `TAAS_USERNAME` / `TAAS_PASSWORD` secrets are wrong or the token has expired. Rotate in 1Password and update the GitHub secret.

**macOS build fails:** Check that the `Brewfile` installs `autoconf`, `automake`, `libtool`. These are already in the repo.

**Deploy step warns about a failed artifact:** The script tolerates individual failures so a duplicate upload doesn't abort the run. Check the warning message — if it is a network error rather than "already exists", re-run the workflow.

**Re-running a release after a partial failure:** `deploy_maven_packages.sh` uploads artifacts in alphabetical order, so `netty-all` goes **first**. If a release run dies partway through the deploy, `netty-all/<version>` already exists and Stage 0 will refuse a re-run ("already published"). Either delete the partially uploaded `io/netty/*/<version>` folders in TaaS and re-run, or bump the pom version. Snapshot runs are not affected, because they can be re-run freely.

**Stage 0 fails with "release ... is already published":** That version is in TaaS. Bump `pom.xml` or publish a snapshot.

---

## Deferred: DataStax Artifactory Sync

Once publishing is working, the artifacts must be reachable from the DataStax public-facing repo. The release and snapshot repos above are used because they keep the artifacts accessible the way the old internal Artifactory did. How the existing sync is moved to IBM Artifactory is still being settled with Erik Merkle.
