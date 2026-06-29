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

### 5. Resolve conflicts if any

If there are merge conflicts, git will pause. Do **not** ask the user — resolve
them autonomously using the following rules:

1. **Read every conflicting file** using `read_file` to understand both sides
   of each conflict (the `<<<<<<< HEAD` / local side and the `>>>>>>> <TAG>` /
   upstream side).
2. **Keep the local (HEAD) change** whenever the conflict touches code that was
   introduced by a DSE-specific patch (i.e. it does not exist in the upstream
   tag at all, or it is a deliberate override of upstream behaviour).
3. **Keep the upstream change** only when the local side is identical to the
   previous upstream base and carries no DSE-specific intent (e.g. a plain
   version bump or a whitespace-only difference that the DSE branch never
   intentionally modified).
4. **Merge both sides** when the upstream adds new logic and the local side
   also adds independent logic to the same region — integrate them so neither
   is lost.
5. After editing each file to its resolved state, write it back with
   `apply_diff` or `write_file`, then stage it:

```bash
git add <resolved-file>
```

6. Once all conflicts are staged, complete the merge:

```bash
git merge --continue
```

### 6. Update the project version to the DSE version

After the merge the root `<version>` in every `pom.xml` will be set to the
upstream value (e.g. `4.1.132.Final`). Update it to the DSE version by
appending the `.1.dse` suffix:

```bash
mvn versions:set -DnewVersion=<VERSION>.1.dse -DgenerateBackupPoms=false
```

- `<VERSION>` — the numeric part of the tag without the `.Final` suffix
  (e.g. for `netty-4.1.133.Final` use `4.1.133`).
- `-DgenerateBackupPoms=false` avoids leaving `pom.xml.versionsBackup` files
  behind.

Commit the result:

```bash
git add -u
git commit -m "Update version to <VERSION>.1.dse"
```

### 7. Verify the result

```bash
git log --oneline --graph -10
```

Confirm the merge commit and the version-bump commit are both at the HEAD,
and that both parent lines of the merge are visible.

### 8. Push

```bash
git push origin <BRANCH>
```

Ask the user for confirmation before pushing.
