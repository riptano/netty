# Build and Publish Workflow

## Overview

This GitHub Actions workflow (`build-and-publish.yml`) builds the Netty library across multiple platforms and publishes the artifacts to GitHub Packages.

## Workflow Architecture

The workflow consists of 5 stages. Stages 2, 3, and 4 run in parallel after Stage 1 completes:

```
 Stage 1: Linux x86_64 Full Build
 - Builds all Netty modules
 - Uses Docker with CentOS 6 for compatibility
 - Produces complete JAR artifacts

             +------------------+------------------+
             |                  |                  |
 Stage 2:             Stage 3:           Stage 4:
 Linux aarch64        macOS Intel        macOS ARM aarch64
 Native Libs          x86_64             Native Libs
                      Native Libs
 - transport-         - resolver-dns     - resolver-dns
   native-epoll         -native-macos      -native-macos
 - transport-         - transport-       - transport-
   native-unix-         native-unix-       native-unix-
   common               common             common
                      - transport-       - transport-
 ubuntu-24.04-arm       native-kqueue      native-kqueue
 runner
                      GitHub Intel Mac   GitHub Apple
                                         Silicon Mac
             +------------------+------------------+
             |
 Stage 5: Merge and Publish
 - Downloads all artifacts from previous stages
 - Merges staging repositories
 - Generates netty-all module
 - Publishes to GitHub Packages
```

## Triggers

The workflow can be triggered in two ways:

1. **Manual Trigger**: Via the GitHub Actions UI (workflow_dispatch)
2. **Tag Push**: Automatically when pushing version tags:
   - Tags starting with `v*` (e.g., `v4.1.100`)
   - Tags starting with `netty-*` (e.g., `netty-4.1.100.Final`)

## Prerequisites

### Repository Configuration

1. **GitHub Packages**: Ensure GitHub Packages is enabled for your repository
2. **Permissions**: The workflow requires the following permissions:
   - `contents: read` - To checkout the repository
   - `packages: write` - To publish to GitHub Packages

### Required Files

The workflow depends on these existing files:
- `docker/Dockerfile.centos6` - Docker image for Linux builds
- `.github/scripts/local_staging_install_release.sh` - Script to merge staging artifacts
- `.github/actions/thread-dump-jvms/action.yml` - Action for debugging cancelled jobs
- `Brewfile` - macOS dependencies (optional, continues on error)

### Secrets

The workflow uses the built-in `GITHUB_TOKEN` secret, which is automatically provided by GitHub Actions. No additional secrets need to be configured.

## Usage

### Manual Trigger

1. Go to the **Actions** tab in your GitHub repository
2. Select **Build and Publish to GitHub Packages** workflow
3. Click **Run workflow**
4. Select the branch to run from
5. Click **Run workflow** button

### Tag-based Trigger

```bash
# Create and push a version tag
git tag v4.1.100.Final
git push origin v4.1.100.Final
```

The workflow will automatically start building and publishing.

## Artifacts

### Intermediate Artifacts

Each build stage uploads its artifacts to GitHub Actions:
- `linux-x86_64-local-staging` - Linux x86_64 build artifacts
- `linux-aarch64-local-staging` - Linux aarch64 native libraries
- `macos-x86_64-local-staging` - Intel Mac native libraries
- `macos-aarch64-local-staging` - ARM Mac native libraries
- `merged-local-staging` - Final merged artifacts (for debugging)

These artifacts are retained for 90 days (GitHub default) and can be downloaded from the workflow run page.

### Published Artifacts

Final artifacts are published to GitHub Packages Maven registry at:
```
https://maven.pkg.github.com/OWNER/REPOSITORY
```

## Consuming Published Artifacts

To use the published artifacts in your Maven project:

### 1. Configure Maven Settings

Add to your `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

### 2. Add Repository to pom.xml

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/OWNER/REPOSITORY</url>
  </repository>
</repositories>
```

### 3. Add Netty Dependencies

```xml
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-all</artifactId>
  <version>YOUR_VERSION</version>
</dependency>
```

## Build Times

Approximate build times (may vary):
- **Stage 1 (Linux x86_64)**: 15-25 minutes
- **Stages 2-4 run in parallel after Stage 1 completes**
- **Stage 2 (Linux aarch64)**: 10-15 minutes
- **Stage 3 (macOS Intel)**: 10-15 minutes
- **Stage 4 (macOS ARM)**: 10-15 minutes
- **Stage 5 (Merge & Publish)**: 5-10 minutes
- **Total**: ~30-50 minutes

## Troubleshooting

### Build Failures

1. **Check the logs**: Click on the failed job to see detailed logs
2. **Download artifacts**: Failed builds may still produce partial artifacts for debugging
3. **Thread dumps**: If a job is cancelled, thread dumps are automatically captured

### Common Issues

**Docker build fails on Linux**:
- Check if `docker/Dockerfile.centos6` exists and is valid
- Verify Docker daemon is accessible

**macOS native build fails**:
- Check if Brewfile dependencies are correct
- Verify JDK 8 is properly installed
- Check native compilation toolchain (Xcode Command Line Tools)

**Publishing fails**:
- Verify `GITHUB_TOKEN` has `packages:write` permission
- Check if GitHub Packages is enabled for the repository
- Ensure the repository URL in the workflow matches your repository

### Re-running Failed Jobs

You can re-run individual failed jobs without re-running the entire workflow:
1. Go to the workflow run page
2. Click on the failed job
3. Click **Re-run jobs** → **Re-run failed jobs**

## Caching

The workflow uses Maven repository caching to speed up builds:
- Linux builds cache: `~/.m2/repository`
- macOS Intel builds cache: `~/.m2/repository` (separate cache key)
- macOS ARM builds cache: `~/.m2/repository` (separate cache key)

Caches are automatically invalidated when `pom.xml` files change.

## Maintenance

### Updating Dependencies

- **Java Version**: Modify the `java-version` in the `setup-java` steps
- **Docker Image**: Update `docker/Dockerfile.centos6`
- **macOS Dependencies**: Update `Brewfile`

### Adding New Platforms

To add support for additional platforms:
1. Add a new job in the workflow (e.g., `build-windows-x64`)
2. Configure the appropriate runner (e.g., `runs-on: windows-latest`)
3. Add the job to the `needs` array in `publish-to-github-packages`
4. Update the artifact download and merge steps

## Security Considerations

- The workflow uses minimal permissions (read contents, write packages)
- Secrets are not exposed in logs
- Docker containers run with volume mounts but no privileged access
- All dependencies are cached and verified via checksums

## Support

For issues with this workflow:
1. Check the [GitHub Actions documentation](https://docs.github.com/en/actions)
2. Review the [Netty build documentation](BUILD-DATASTAX.md)
3. Open an issue in the repository with workflow run logs