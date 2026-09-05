---
name: erdachi-sync-upstream
description: Sync the user's manga extension hub fork (napatsakorn-kamkrua/erdachi-extensions) with upstream Keiyoushi, preserving the personal-hub CI customizations. Use when a published source stops working and may already be fixed upstream, when the user says "sync", "update my fork", "pull upstream", "keiyoushi fixed it", "my source is broken/empty chapters/images fail", or wants a dormant source that upstream just improved — always try this before hand-writing any fix.
---

# Sync erdachi-extensions with upstream Keiyoushi

The hub is a full fork of Keiyoushi's `extensions-source`. Git never syncs by itself; this
procedure pulls upstream fixes in safely. Two standing facts shape everything below:

- **Keep the full tree.** The ~1,400 dormant extensions exist so merges stay trivial and any
  source is one push away. Never delete them to "clean up".
- **A sync grows the catalog.** CI rebuilds every extension upstream changed and the publish
  job adds each one to `repo` branch `index.json`. That is expected behavior for a fork — warn
  the user once, not every time.

## Fixed facts

| Thing | Value |
|---|---|
| Local repo | `D:\Playground\erdachi-extensions\extensions-source` (branch `main`) |
| `origin` | `napatsakorn-kamkrua/erdachi-extensions` (the user's hub) |
| `upstream` | `keiyoushi/extensions-source` (the original) |
| CI | `build_push.yml` on push: builds only changed modules, publishes release + `index.json` |
| Signing | automatic from repo secrets; never touch, never regenerate |

## When to sync

1. A source in the catalog broke (empty chapters, failed images, dead search) — check if
   upstream already fixed it before writing any code.
2. The break is in a shared template (`lib-multisrc/<theme>`), where an upstream fix repairs
   every site using that theme, including the user's.
3. The user wants a dormant source that upstream just improved.

## When NOT to sync

Everything works and the catalog is as personal as the user wants. A frozen fork keeps working
forever. Syncing is also wrong if the user explicitly wants the catalog to never grow — say so
and offer trimming the tree instead (destructive, kills easy merges; get explicit confirmation).

## Procedure

```bash
cd D:/Playground/erdachi-extensions/extensions-source

# 1. Fetch upstream (no changes yet)
git fetch upstream

# 2. Check whether a fix exists for the affected source or theme
git log upstream/main --oneline -20 -- src/th/<name>
git log upstream/main --oneline -20 -- lib-multisrc/<theme>

# 3. Preview what a merge would bring
git log HEAD..upstream/main --oneline | head -30

# 4. Merge (merge, never rebase — published history is already on GitHub and CI
#    keys "last successful run" off existing SHAs)
git merge upstream/main
```

If conflicts appear they will almost certainly be in the files customized for the personal
hub: `.github/workflows/build_push.yml`, `.github/scripts/github_utils.py`,
`.github/scripts/publish-repo.py`, and `README.md` (replaced by the hub's own landing page).
Resolve by keeping YOUR versions:

```bash
git checkout --ours .github/workflows/build_push.yml .github/scripts/github_utils.py .github/scripts/publish-repo.py README.md
git add .github/ README.md
git commit
```

Those customizations are load-bearing: the keiyoushi-only upload gate was removed,
`REPO_NAME`/icons read `GITHUB_REPOSITORY`, the index `signingKey` comes from
`SIGNING_KEY_SHA256`, and `README.md` is the hub's own landing page with its source catalog.
Taking upstream's version here silently re-points publishing at Keiyoushi's repos and restores
their README. Only if upstream's version contains an important new step, port that step into
yours afterwards — never swap the whole file. A conflict anywhere else is unexpected:
stop, show the user the file, and decide together.

```bash
# 5. Push; CI rebuilds the changed extensions (~minutes) and publishes
git push origin main
gh run list --repo napatsakorn-kamkrua/erdachi-extensions --limit 1
gh run watch <run-id> --repo napatsakorn-kamkrua/erdachi-extensions
```

## Verify

```bash
# Index reflects the synced/updated sources; signingKey hash unchanged
curl -s "https://raw.githubusercontent.com/napatsakorn-kamkrua/erdachi-extensions/repo/index.json" | python -m json.tool | grep -E "<name>|signingKey"
```

Report to the user: which sources got updated, that Tachimanga picks the new versions up on
its next extension refresh, and the new catalog size if it grew.

## Gotchas

- If a push produces no CI run, dispatch manually:
  `gh workflow run build_push.yml --repo napatsakorn-kamkrua/erdachi-extensions --ref main`.
- Never force-push `main`, never regenerate the signing key — installed extensions would stop
  updating for every user of this repo. A local backup of the key exists in a folder outside
  and next to the repo; locate it (ask the user) before any destructive key operation.
- If the source is still broken after a successful sync, only then fall back to hand-fixing:
  probe the failing endpoint with curl, diff against the template selectors, add the override,
  bump `versionCode`, push (see the `erdachi-add-source` skill).
