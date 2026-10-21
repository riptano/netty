# Publish to IBM TaaS Artifactory

## Overview

[`publish-to-taas.yml`](publish-to-taas.yml) publishes our internal Netty fork to IBM TaaS Artifactory:

```
https://na.artifactory.swg-devops.com/artifactory/datastax-team-dse-external-libs-maven-local
```

This is the same repository that holds the internal ehcache libraries. No Jenkins macOS instances are needed — macOS builds use free GitHub-hosted runners (`macos-15` and `macos-15-intel`).

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
│  • Computes version string (see below)        │
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
│  • Release only: git commit + tag push       │
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

This section explains every version-related decision the pipeline makes, how pom.xml versions are interpreted, and what developers must and must not do.

---

### How upstream Netty versions the pom

The upstream Netty project uses the Maven Release Plugin, which produces a predictable version lifecycle. Confirmed from the git history in `riptano/netty`:

```
4.1.136.Final released
    │
    │  commit: [maven-release-plugin] prepare for next development iteration
    ▼
pom = 4.1.137.Final-SNAPSHOT    ← weeks of development commits
    │
    │  commit: [maven-release-plugin] prepare release netty-4.1.137.Final
    ▼
pom = 4.1.137.Final              ← git tag: netty-4.1.137.Final
    │
    │  DSE merge: merge tag into DSE fork branch
    ▼
pom = 4.1.137.Final              ← this is what our pipeline normally sees
    │
    │  (pipeline sets version automatically — see below)
    ▼
pom = 4.1.137.1.dse              ← after a release publish
```

### What pom.xml versions the pipeline accepts

| pom version | Accepted | Notes |
|---|---|---|
| `4.1.137.1.dse` | ✅ | Normal state after a DSE release publish |
| `4.1.137.Final` | ✅ | Normal state right after DSE merge, before first publish |
| `4.1.137.Final-SNAPSHOT` | ❌ **HARD FAIL** | Mid-development upstream commit — wrong branch |

If the pom version ends in `-SNAPSHOT`, the pipeline stops immediately in Stage 0 with this error:

```
ERROR: pom version is '4.1.137.Final-SNAPSHOT' which ends in -SNAPSHOT.
       This means the pipeline was triggered on an upstream mid-development
       commit, not on a release tag or a DSE branch.
       Trigger the pipeline on a branch whose pom.xml is at a final version
       (e.g. 4.1.137.Final or 4.1.137.1.dse).
```

### Rule for developers: never manually change version numbers

**Do not run `mvn versions:set` or edit `<version>` in any pom.xml by hand.**

The pipeline manages all version numbers automatically:
- It reads the upstream triplet (`4.1.137`) from whatever version is in pom.xml
- It computes the next DSE build counter by querying TaaS
- It stamps the computed version into all pom.xml files during the build
- For release: it commits the stamped pom.xml files back to the branch

The only valid reason to have a specific DSE counter in pom.xml (e.g. `4.1.137.2.dse`) is because a previous release publish put it there.

---

### Release version: `<upstream>.<N>.dse`

The pipeline never reads the DSE build counter `N` from `pom.xml`. Instead:

1. Extracts the upstream triplet from the pom (`4.1.137.1.dse` → `4.1.137`, `4.1.137.Final` → `4.1.137`)
2. Queries TaaS Artifactory for all published `4.1.137.N.dse` version folders under `io/netty/netty-all/`
3. Finds the highest `N` already published and uses `N+1` (defaults to `1` if nothing published yet)
4. Stamps that version into all pom.xml files, builds, publishes to TaaS, commits pom.xml back, pushes a git tag

| Scenario | pom.xml | Already in TaaS | Pipeline computes |
|---|---|---|---|
| First ever publish | `4.1.137.Final` or `4.1.137.1.dse` | nothing | `4.1.137.1.dse` |
| Second publish of 4.1.137 | `4.1.137.1.dse` | `4.1.137.1.dse` | `4.1.137.2.dse` |
| New upstream merged (4.1.138) | `4.1.138.Final` | nothing for 4.1.138 | `4.1.138.1.dse` |
| Retry after partial failure | `4.1.137.1.dse` | 30 of 40 artifacts, no `netty-all` | `4.1.137.1.dse` (same — retries cleanly) |
| Patching old upstream line | `4.1.137.2.dse` | `4.1.137.1.dse`, `4.1.137.2.dse` | `4.1.137.3.dse` |

**Why `netty-all` is the counter anchor:** `netty-all` is always uploaded last by the deploy script. A partial publish that aborted before `netty-all` was uploaded leaves the counter unchanged, so a retry uses the same version rather than skipping to `N+2`.

After a release publish the branch looks like:
```
git log:
  a7f3c91  Set version to 4.1.137.2.dse    ← automated commit from pipeline
  ...previous commits...

git tag:
  4.1.137.2.dse → a7f3c91                  ← automated tag from pipeline
```

---

### Snapshot version: `<upstream>-<sha>-SNAPSHOT`

Snapshot mode is for developer testing — to build and publish a Netty artifact to TaaS so it can be consumed in BDP before committing to a full release.

Format: `4.1.137-a3f5b2c-SNAPSHOT`

- The short git SHA of the triggering commit is embedded in the version
- The `-SNAPSHOT` suffix is at the end (Maven convention — resolvers treat this as a snapshot)
- No version is stamped back to pom.xml
- No git commit, no git tag

| pom.xml | Commit SHA | Snapshot version published |
|---|---|---|
| `4.1.137.1.dse` | `a3f5b2c` | `4.1.137-a3f5b2c-SNAPSHOT` |
| `4.1.137.1.dse` | `d9e1f0a` (new commit) | `4.1.137-d9e1f0a-SNAPSHOT` |
| `4.1.138.Final` | `f1e2d3c` | `4.1.138-f1e2d3c-SNAPSHOT` |

**Each commit produces a distinct snapshot.** They do not overwrite each other. A developer testing two different reworks can have both `4.1.137-a3f5b2c-SNAPSHOT` and `4.1.137-d9e1f0a-SNAPSHOT` in TaaS simultaneously and switch between them in BDP by changing `nettyVersion` in `build.properties`. Periodic cleanup of old snapshots may be needed over time.

---

## Required Secrets

Both secrets are already set on `riptano/netty`. The values are in **1Password → DSE-Shared → "IBM TaaS JFrog: datastax-enterprise-automation Identity Token"**.

| Secret | Value |
|---|---|
| `TAAS_USERNAME` | `datastax-enterprise-automation@ibm.com` |
| `TAAS_PASSWORD` | Identity token from 1Password |

No SSH deploy key is needed. The release git push uses the workflow's built-in `GITHUB_TOKEN` with `contents: write` permission.

---

## Consuming Published Artifacts

Until the DataStax Artifactory → TaaS sync is configured (deferred — see DSP-25338), you can resolve the artifacts directly from TaaS by adding a repository to your BDP build using the `extraRepositoriesConfig` escape hatch:

**1. Create a file `taas-repo.gradle`:**
```groovy
repositories {
    maven {
        url = 'https://na.artifactory.swg-devops.com/artifactory/datastax-team-dse-external-libs-maven-local'
        credentials {
            username = System.getProperty('taasUsername')
            password = System.getProperty('taasPassword')
        }
    }
}
```

**2. Build BDP with:**
```bash
./gradlew <task> \
  -DextraRepositoriesConfig=/path/to/taas-repo.gradle \
  -DtaasUsername=datastax-enterprise-automation@ibm.com \
  -DtaasPassword=<token>
```

**3. Update `build.properties`:**
```properties
nettyVersion = 4.1.137.2.dse
```

Alternatively, download the `merged-local-staging` GitHub artifact from a completed run and point `extraRepositoriesConfig` at the local folder using `url = 'file:///path/to/merged-local-staging'`.

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

**Re-running a release after a partial failure:** Re-trigger the workflow on the same branch. The version counter queries what netty-all is already in TaaS. Because `netty-all` is uploaded last, a partial run leaves the counter unchanged and the retry uses the same version number rather than skipping to the next one.

---

## Deferred: DataStax Artifactory Sync

Once publishing is working, a sync from `datastax-team-dse-external-libs-maven-local` into the DataStax public-facing repo needs to be configured — similar to how ehcache libraries are synced. Erik Merkle will help with this step. Until then, use the `extraRepositoriesConfig` approach above to consume the artifacts in BDP.
