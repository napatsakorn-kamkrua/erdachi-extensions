# Erdachi Extensions

[![CI](https://github.com/napatsakorn-kamkrua/erdachi-extensions/actions/workflows/build_push.yml/badge.svg)](https://github.com/napatsakorn-kamkrua/erdachi-extensions/actions/workflows/build_push.yml)

Personal manga extension hub for Tachiyomi-family reader apps (Tachimanga, Mihon, and forks),
built on a fork of [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).
Every push to `main` is built and signed automatically by GitHub Actions, and published as an
extension repository that reader apps consume directly.

## Add this repo to your reader app

In your app: **Settings → Extensions → extension repositories (puzzle icon) → Add repository**,
then paste:

```
https://raw.githubusercontent.com/napatsakorn-kamkrua/erdachi-extensions/repo/index.json
```

New sources appear in the app automatically after each release — no manual installation.

## Source catalog

Sources maintained in this hub:

| Source | Language | Site | Status | Version |
|---|---|---|---|---|
| BKKManga | Thai | [bkkmanga.com](https://bkkmanga.com) | ✅ Working | 1.6.56 |

> The table above lists personally maintained sources. Sources pulled in from upstream syncs
> also become available through the same repository URL; the full machine-readable catalog is
> always [`repo/index.json`](https://github.com/napatsakorn-kamkrua/erdachi-extensions/blob/repo/index.json).

**Planned:** more Thai sites — will be added to this table as they land.

## Agent skills

This repo is maintained with the help of two AI-agent skills (copies live in
[`skills/`](./skills), canonical versions in the owner's `~/.agents/skills/`):

| Skill | Purpose |
|---|---|
| `erdachi-add-source` | Full procedure for turning a new manga site into an extension here: detect the site's engine, verify endpoints, scaffold with `ext-bootstrap.py`, publish, verify |
| `erdachi-sync-upstream` | Safely pull fixes from upstream Keiyoushi (broken source? check there first), keeping this hub's CI customizations intact |

Any AI agent that can read these files can operate this repo with them.

## How this repo works

- `src/<lang>/<name>/` — one extension per folder; most sites reuse a shared site-engine
  template from `lib-multisrc/` (e.g. WordPress "Madara" sites → `madara`).
- CI (`.github/workflows/build_push.yml`) builds only the extensions changed by a push, signs
  them with the hub's own key (stored in GitHub secrets), creates a GitHub release, and updates
  the `repo` branch index.
- **Signing key:** lives in GitHub secrets, with backups in a folder next to this repo on the
  owner's machine and in the **private** repo
  [`napatsakorn-kamkrua/erdachi-backup`](https://github.com/napatsakorn-kamkrua/erdachi-backup)
  (must never be made public). Never regenerate it — installed extensions would stop updating.
  If the secrets are ever lost, restore them from a backup instead.

## Syncing with upstream

This fork tracks upstream for template and source fixes. Sync is on-demand (see
`erdachi-sync-upstream` skill), not automatic. On merge conflicts in `.github/` files or
`README.md`, always keep this repo's versions.

## License & Disclaimer

This project is based on [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

This project is not affiliated with the content providers available through the sources, nor
with Mihon/Tachiyomi. All credits for the codebase go to the original contributors.
