---
name: merge-upstream-tag
description: Use when the user wants to merge an upstream version tag into the current branch-specific fork branch (e.g. "merge netty-4.1.133.Final into dse-netty-4.1.133"), preserving existing branch-specific changes.
---

# Merge Upstream Version Tag into Fork Branch

This skill forward-ports a DSE netty fork branch to a new upstream release
tag. It fetches all tags from the upstream remote, then merges the target
version tag into the current branch while preserving all branch-specific
commits.

## Prerequisites

Confirm with the user before starting:
1. The **upstream remote name** — check with `git remote -v`. In this repo
   it is `upstream` (pointing to `github.com/netty/netty`).
2. The **version tag** to merge in (e.g. `netty-4.1.133.Final`).
3. The **current branch** name — use `git branch --show-current`.

## Steps

### 1. Verify the current state

```bash
git status
git branch --show-current
git remote -v
```

Make sure the working tree is clean (no uncommitted changes). If it is not,
ask the user to stash or commit before continuing.

### 2. Fetch all tags from the upstream remote

```bash
git fetch upstream --tags
```

This pulls every tag from the upstream remote (e.g. `github.com/netty/netty`)
without modifying any local branches.

### 3. Confirm the tag exists

```bash
git tag -l "<TAG>"
```

Replace `<TAG>` with the target tag (e.g. `netty-4.1.133.Final`). If it is
not listed, the fetch in step 2 may have failed or the tag name is wrong —
verify and retry.

### 4. Merge the tag into the current branch

```bash
git merge "<TAG>" --no-ff -m "Merge tag '<TAG>' into <BRANCH>

This forward-ports the DSE netty fork to the <TAG> release of netty.

[maven-release-plugin] copy for tag <TAG>"
```

- `<TAG>` — the upstream version tag (e.g. `netty-4.1.133.Final`).
- `<BRANCH>` — the current branch name (e.g. `dse-netty-4.1.133`).
- `--no-ff` preserves the merge commit so the upstream history remains
  traceable.

The commit message format must follow the established convention used in
previous merges on this repository (e.g. commit `f22f5ae6aa` for
`netty-4.1.132.Final`).

**Expect the merge to stop with conflicts every time.** This is normal — every
merge of a new upstream tag into a DSE fork branch will produce conflicts
because of DSE-specific patches and version numbers. Proceed directly to
step 5.

### 5. Resolve all conflicts

Run the following to see every conflicting file:

```bash
git diff --name-only --diff-filter=U
```

Work through each file. Do **not** ask the user — resolve them autonomously
using the rules below.

#### 5a. `pom.xml` conflicts — always keep the DSE version

Every `pom.xml` file will conflict on the `<version>` element because upstream
uses a plain `.Final` version while the DSE branch uses the `.1.dse` suffix.

**Rule:** In every `pom.xml` conflict, always accept the **HEAD (DSE) side**
for the `<version>` element. The resolved value must be the DSE version string
(e.g. `4.1.133.1.dse`), **never** the bare upstream value (e.g. `4.1.133.Final`).

For all other content in the same `pom.xml` (dependency versions, plugin
config, etc.) apply the general rules from §5b below.

After resolving each `pom.xml`, stage it:

```bash
git add <path/to/pom.xml>
```

#### 5b. Code file conflicts — always preserve DSE changes

For every non-`pom.xml` conflict:

1. **Read the conflicting file** using `read_file` to understand both sides
   (`<<<<<<< HEAD` = local/DSE side, `>>>>>>> <TAG>` = upstream side).
2. Apply the following priority rules in order:
   - **Keep the DSE (HEAD) change** whenever the conflict region contains code
     that was introduced or modified by a DSE-specific patch (i.e. it does not
     exist in the upstream tag at all, or it is a deliberate override of
     upstream behaviour). DSE changes must never be silently dropped.
   - **Keep the upstream change** only when the local side is byte-for-byte
     identical to the previous upstream base and carries no DSE-specific intent
     (e.g. an import that was moved, a purely mechanical refactor, or a
     whitespace-only difference that the DSE branch never intentionally touched).
   - **Merge both sides** when the upstream adds new logic _and_ the local side
     also adds independent logic to the same region — integrate them carefully
     so neither is lost.
3. After resolving, write the file back with `apply_diff` or `write_file` so
   it contains no conflict markers, then stage it:

```bash
git add <resolved-file>
```

#### 5c. Complete the merge

Once every conflicting file has been staged, finalize the merge commit:

```bash
git merge --continue
```

If `git merge --continue` opens an editor, the pre-filled message is already
correct (it was passed in step 4); just accept it as-is.

### 6. Verify pom.xml versions are consistent

After the merge commit is created, double-check that all `pom.xml` files carry
the correct DSE version and not the upstream version:

```bash
grep -r "<version>" --include="pom.xml" . | grep -v "\.1\.dse" | grep -v "target/"
```

If any `pom.xml` still contains the bare upstream version (e.g. `4.1.133.Final`
instead of `4.1.133.1.dse`), amend them and stage + commit the fix:

```bash
# Fix remaining pom.xml files that slipped through conflict resolution
mvn versions:set -DnewVersion=<DSE_VERSION> -DgenerateBackupPoms=false
git add -u
git commit -m "Update version to <DSE_VERSION>"
```

- `<DSE_VERSION>` — the full DSE version string, e.g. `4.1.133.1.dse`.
- `-DgenerateBackupPoms=false` avoids leaving `pom.xml.versionsBackup` files
  behind.

### 7. Verify the result

```bash
git log --oneline --graph -10
```

Confirm the merge commit is at the HEAD and that both parent lines of the
merge are visible.

### 8. Push

```bash
git push origin <BRANCH>
```

Ask the user for confirmation before pushing.
