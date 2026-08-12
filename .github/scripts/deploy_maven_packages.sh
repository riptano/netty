#!/bin/bash
# ----------------------------------------------------------------------------
# Deploy pre-built artifacts from a local Maven repository layout directory
# to a remote Maven registry using deploy:deploy-file.
#
# Usage:
#   deploy_maven_packages.sh <staging-dir> <repository-url> <repository-id>
#
# Arguments:
#   staging-dir     - Path to a directory in standard Maven repository layout
#                     (e.g. ~/local-staging containing io/netty/...)
#   repository-url  - Full URL of the target Maven registry
#                     (e.g. https://maven.pkg.github.com/org/repo
#                           https://your-org.jfrog.io/artifactory/libs-release-local)
#   repository-id   - Server ID matching an entry in ~/.m2/settings.xml
#                     that holds the credentials for the target registry
#
# Each .pom file found under <staging-dir> is treated as one artifact.
# The corresponding .jar (and any classified jars) alongside it are
# uploaded together. Files that are themselves classifiers of a pom
# already handled are skipped to avoid double-uploading.
# ----------------------------------------------------------------------------
set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <staging-dir> <repository-url> <repository-id>"
    exit 1
fi

STAGING_DIR="$1"
REPO_URL="$2"
REPO_ID="$3"

if [ ! -d "$STAGING_DIR" ]; then
    echo "Error: staging directory '$STAGING_DIR' does not exist"
    exit 1
fi

echo "Deploying artifacts from '$STAGING_DIR' to '$REPO_URL' (repositoryId=$REPO_ID)"

# Find every .pom in the staging directory. Each .pom represents one
# artifact coordinate (groupId:artifactId:version[:classifier]).
find "$STAGING_DIR" -name "*.pom" | sort | while read -r POM_FILE; do
    DIR="$(dirname "$POM_FILE")"
    BASENAME="$(basename "$POM_FILE" .pom)"

    # Derive the main jar alongside this pom (same basename, no classifier).
    MAIN_JAR="$DIR/$BASENAME.jar"

    # Build the -Dfiles= and -Dclassifiers= and -Dtypes= lists for any
    # additional classified artifacts sitting next to this pom.
    EXTRA_FILES=""
    EXTRA_CLASSIFIERS=""
    EXTRA_TYPES=""

    for EXTRA in "$DIR/$BASENAME"-*.jar; do
        [ -f "$EXTRA" ] || continue
        # Extract the classifier from the filename: strip prefix "<basename>-" and suffix ".jar"
        CLASSIFIER="${EXTRA#$DIR/$BASENAME-}"
        CLASSIFIER="${CLASSIFIER%.jar}"
        if [ -n "$EXTRA_FILES" ]; then
            EXTRA_FILES="$EXTRA_FILES,$EXTRA"
            EXTRA_CLASSIFIERS="$EXTRA_CLASSIFIERS,$CLASSIFIER"
            EXTRA_TYPES="$EXTRA_TYPES,jar"
        else
            EXTRA_FILES="$EXTRA"
            EXTRA_CLASSIFIERS="$CLASSIFIER"
            EXTRA_TYPES="jar"
        fi
    done

    # Build the deploy:deploy-file command.
    DEPLOY_ARGS=(
        --batch-mode
        deploy:deploy-file
        "-Durl=$REPO_URL"
        "-DrepositoryId=$REPO_ID"
        "-DpomFile=$POM_FILE"
        "-DgeneratePom=false"
    )

    if [ -f "$MAIN_JAR" ]; then
        DEPLOY_ARGS+=("-Dfile=$MAIN_JAR")
    else
        # pom-only artifact (e.g. parent pom, BOM)
        DEPLOY_ARGS+=("-Dfile=$POM_FILE" "-Dpackaging=pom")
    fi

    if [ -n "$EXTRA_FILES" ]; then
        DEPLOY_ARGS+=("-Dfiles=$EXTRA_FILES")
        DEPLOY_ARGS+=("-Dclassifiers=$EXTRA_CLASSIFIERS")
        DEPLOY_ARGS+=("-Dtypes=$EXTRA_TYPES")
    fi

    echo "--- Deploying: $BASENAME"
    if ! mvn "${DEPLOY_ARGS[@]}"; then
        echo "WARNING: Failed to deploy $BASENAME (may already exist in registry — skipping)"
    fi
done

echo "Deployment complete."
