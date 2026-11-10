#!/bin/bash
# ----------------------------------------------------------------------------
# Normalise SNAPSHOT file names inside local staging directories (in place).
#
# `mvn deploy -DaltDeploymentRepository=...file://...` writes SNAPSHOTs with
# timestamped names and a per-version maven-metadata.xml:
#     netty-buffer-4.1.137.1.dse-20261021.082047-1.jar
# Two problems with that:
#   1. ~/.m2/repository only resolves  netty-buffer-4.1.137.1.dse-SNAPSHOT.jar,
#      so the netty-all build would fall back to Sonatype and fail.
#   2. Every platform build has a different timestamp, so merging the staging
#      dirs leaves several unrelated timestamped copies of the same artifact.
#
# This renames timestamped files to the plain -SNAPSHOT name (the form
# deploy:deploy-file expects; it re-timestamps on upload) and removes the
# generated metadata/checksums. Directories whose version is not a SNAPSHOT
# (release builds) are left untouched.
#
# Usage: snapshot_staging_normalize.sh <staging-dir> [<staging-dir> ...]
# ----------------------------------------------------------------------------
set -e
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <staging-dir> [<staging-dir> ...]"
  exit 1
fi

for root in "$@"; do
  [ -d "$root" ] || { echo "Error: '$root' is not a directory"; exit 1; }
  find "$root" -type d -name '*-SNAPSHOT' | while read -r dir; do
    base="$(basename "$dir")"          # 4.1.137.1.dse-SNAPSHOT
    ver="${base%-SNAPSHOT}"            # 4.1.137.1.dse
    rm -f "$dir"/maven-metadata.xml* "$dir"/*.md5 "$dir"/*.sha1
    find "$dir" -maxdepth 1 -type f | while read -r f; do
      name="$(basename "$f")"
      new="$(echo "$name" | sed -E "s/-${ver//./\\.}-[0-9]{8}\.[0-9]{6}-[0-9]+/-${base}/")"
      [ "$new" = "$name" ] || mv -f "$f" "$dir/$new"
    done
  done
done
