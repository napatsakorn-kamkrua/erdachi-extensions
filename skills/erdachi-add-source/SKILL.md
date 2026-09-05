---
name: erdachi-add-source
description: Add a new manga/manhwa/manhua site as a source extension to the user's personal extension hub (github.com/napatsakorn-kamkrua/erdachi-extensions, a Keiyoushi extensions-source fork) so it appears automatically in Tachimanga/Mihon. Use this skill whenever the user mentions adding a manga site, a new source, "another site to my extensions repo", a manga site URL to turn into an extension, fixing/extending an existing source in erdachi-extensions, or any task touching src/<lang>/ in the erdachi-extensions repo — even if they just paste a site URL with no other explanation.
---

# Add a manga source to erdachi-extensions

Turn a manga website into a reader-app extension in the user's personal hub. The hub is a fork
of Keiyoushi's `extensions-source` with CI that builds changed extensions, signs them with the
user's own key, and publishes them to a repo branch that manga reader apps (Tachimanga on the
user's iPhone) consume as an extension repository. The user reads Thai manga but future sites
may be any language.

## Fixed facts (do not re-derive, do not re-ask)

| Thing | Value |
|---|---|
| Local repo | `D:\Playground\erdachi-extensions\extensions-source` (git, branch `main`) |
| GitHub repo | `napatsakorn-kamkrua/erdachi-extensions` (public) |
| `origin` | the user's repo; `upstream` = keiyoushi/extensions-source (for pulling upstream fixes) |
| Reader app | Tachimanga (iOS). Repo URL configured once; new sources appear automatically |
| App repo URL | `https://raw.githubusercontent.com/napatsakorn-kamkrua/erdachi-extensions/repo/index.json` |
| Signing | Secrets `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `SIGNING_KEY_SHA256` already set. CI signs automatically. Never commit any `*.jks` |
| CI | `.github/workflows/build_push.yml` on push to main: builds only changed modules, publishes release + updates `repo` branch `index.json` |
| Target app install path | nothing to do per-source; the app picks the source up from `index.json` |

## Step 0 — Read the conventions

Read `CONTRIBUTING.md` and `AGENTS.md` in the repo root before writing any code. They override
stale Tachiyomi knowledge. Non-negotiables as of writing:

- Non-multisrc sources extend `KeiSource` with `libVersion = "1.6"`, never raw `HttpSource`.
- Never declare `name`, `lang`, `id`, `baseUrl` in the Kotlin class — they are injected via KSP
  from the `source {}` block in `build.gradle.kts`.
- No `SourceFactory`. Multiple sources = multiple `source {}` blocks.
- No manual SharedPreferences for mirrors; use `baseUrl { mirrors(...) }` in the DSL if needed.
- DTOs are plain classes, not `data class`. Use `keiyoushi.utils` helpers (`parseAs`, etc.).
- Check `lib/` for an existing helper before hand-rolling anything.
- Comments only for non-obvious *why*. No over-engineering, no speculative options.

## Step 1 — Decide the identity (ask the user only what's missing)

Three decisions. The user is not an expert — explain simply and always recommend a choice.

1. **Name**: display name + folder/package derived from it (folder = lowercase alphanumerics).
   Default: the site's own spelling, e.g. "BKKManga" → `src/th/bkkmanga/`.
2. **Language**: from the site's content (`th`, `en`, ...). Usually obvious from the site.
3. **Content warning**: `SAFE` / `MIXED` / `NSFW` in `build.gradle.kts`. If unsure, recommend
   `MIXED` — it only controls whether the app hides the source behind a NSFW setting; nothing breaks.

## Step 2 — Detect the site's engine (never skip)

Fetch the homepage with a browser User-Agent and fingerprint it. Write temp files to the OS
temp directory, never the repo root (probe junk committed into the repo pollutes the diff).

```bash
curl -sL -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0" "https://SITE.com/" -o "$TMPDIR/site.html"
grep -oiE "wp-content/themes/[a-z0-9_-]+|generator.{0,80}" "$TMPDIR/site.html" | sort -u
```

If curl gets a 403 or a Cloudflare/JS challenge instead of HTML, retry once with a full
browser header set; if still blocked, ask the user to open the site in their desktop browser,
save the page (Ctrl+S, HTML only) into the temp dir, and fingerprint that file. Real browser
HTML is equivalent for fingerprinting since the extension's own HTTP client will face the same
protection later — note which protection it was, because the source may need headers/cookies
handling in code.

Pick the template from the match:

| Fingerprint | multisrc theme | base class |
|---|---|---|
| `themes/madara`, "Powered by Madara" | `madara` | `Madara` or `MadaraNoAjax` (see Step 3) |
| `themes/madara` old builds | `madaralegacy` | `MadaraLegacy` |
| `themes/mangastream` / `themesia` | `mangathemesia` | `MangaThemesia` |
| Anything else | search `ls lib-multisrc/` for a matching theme (60+ exist: `foolslide`, `gigaviewer`, `mangabox`, ...) and read that theme's README/main class | |
| No match | scaffold without `-m` and implement a `KeiSource` subclass; biggest job | |

Most Thai aggregators are Madara or MangaThemesia. If the grep is inconclusive, fetch a series
page and look for the theme's known CSS classes (e.g. Madara: `page-item-detail`,
`reading-content`, `wp-manga-chapter`).

## Step 3 — Verify endpoints against the template (never skip)

The template's defaults only apply if the site actually responds the way the template expects.
Probe with curl before writing code; every probe that differs from the default becomes a
one-line override. Madara example probes (adapt paths for other engines):

```bash
UA="Mozilla/5.0 ... Chrome/120.0"
# Browse (popular): expects div.page-item-detail with data-post-id + nav-previous for pagination
curl -sL -A "$UA" "https://SITE.com/manga/?m_orderby=views" | grep -c "data-post-id"

# Madara ajax browse (Madara class): empty body means use MadaraNoAjax instead
curl -s -A "$UA" -X POST "https://SITE.com/wp-admin/admin-ajax.php" \
  -d "action=madara_load_more&page=0" -o - | head -c 100

# Chapters: if series HTML lacks wp-manga-chapter items, try the ajax endpoint
curl -s -A "$UA" -H "X-Requested-With: XMLHttpRequest" -X POST \
  "https://SITE.com/manga/<slug>/ajax/chapters/" | grep -c "wp-manga-chapter"

# Date format: look inside chapter-release-date spans (e.g. "2026-09-01" needs an override)
# Reader images: expect .reading-content img with direct src URLs
```

Known BKK-style Madara variant (applies to `bkkmanga.com`): browse/search are plain HTML pages
(`MadaraNoAjax`), chapters come from `/manga/<slug>/ajax/chapters/` (`ChapterMode.MangaAjax`),
dates are ISO `yyyy-MM-dd`. If a new site matches all probes the same way, copy the pattern
from `src/th/bkkmanga/`.

Also check the search page's markup (fetch `/?s=query&post_type=wp-manga`): Madara's
`MadaraNoAjax` falls back to RSS/shortlink ID resolution when cards lack `data-post-id`.

## Step 4 — Scaffold and write the code

Ordering: scaffold with the family's `-m` theme first (`madara` for any Madara-family site —
`Madara` vs `MadaraNoAjax` is not a scaffold choice), then pick the exact base class while
editing the `.kt` based on the Step 3 probes.

```bash
cd D:/Playground/erdachi-extensions/extensions-source
python ext-bootstrap.py -n "SiteName" -l th -u https://site.com -c MIXED -m madara --path .
```

Then **fix the indent immediately** — the scaffold writes TABS into `build.gradle.kts` (and
into the stub `.kt` for custom non-multisrc sources), but Spotless enforces 4 spaces and the
CI build WILL fail without this:

```bash
sed -i 's/\t/    /g' src/th/<name>/build.gradle.kts src/th/<name>/src/eu/kanade/tachiyomi/extension/th/<name>/*.kt
```

Edit the generated `<Name>.kt`:

- Multisrc source: `abstract class X : <BaseClass>()` plus only the overrides Step 3 showed are
  needed (e.g. `override val chapterMode = ChapterMode.MangaAjax`,
  `override val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")`).
- `versionCode = 0` is correct for a first release of a multisrc-based source (the theme adds
  its base). Bump it by 1 for every subsequent change to that source — the version is what
  makes apps update.
- Custom source: implement the `KeiSource` stub methods; keep requests/responses in the
  template style used by neighboring sources in `src/th/`.

## Step 5 — Commit, push, watch CI

Update the source catalog table in the repo's `README.md` in the same commit: add a row for
the new source (name, language, site URL, status, version from the published index). For a fix
to an existing source, update its row's status/version instead. This keeps the hub's landing
page truthful without extra work.

```bash
git add src/<lang>/<name> && git commit -m "Add <Name> (<lang>) source" && git push origin main
```

CI builds only the changed modules (~7 min for one source), then the publish job creates a
GitHub release with the signed APK/JAR and updates `repo` branch `index.json`. Watch with
`gh run list --repo napatsakorn-kamkrua/erdachi-extensions --limit 1` then
`gh run view <id> --repo ... --json status,conclusion,jobs`.

- If a push produces no CI run (happens occasionally), dispatch manually:
  `gh workflow run build_push.yml --repo napatsakorn-kamkrua/erdachi-extensions --ref main`.
- Format violations show up as `spotlessKotlinCheck` failures — fix indent/EOL and push again.
- Never hand-edit `.github/` publishing logic; it is already adapted (upload gates removed,
  `REPO_NAME`/icons read `GITHUB_REPOSITORY`, index `signingKey` comes from `SIGNING_KEY_SHA256`).

## Step 6 — Verify (do not declare done before this)

```bash
# 1. Index lists the new source with the user's signing key hash
curl -s "https://raw.githubusercontent.com/napatsakorn-kamkrua/erdachi-extensions/repo/index.json" | python -m json.tool | grep -A3 <name>

# 2. The APK in the index's apkUrl downloads with HTTP 200
curl -sIL "<apkUrl from index>" | grep -E "^HTTP|Content-Length"
```

Then tell the user: if their Tachimanga already has the repo URL added (it does), the new
source appears in Extensions after the app refreshes — install and open the site. There is
nothing to install manually per source.

If the user reports a broken source in the app: **before hand-fixing anything**, check whether
upstream Keiyoushi already fixed that source or its template — use the `erdachi-sync-upstream`
skill for that. Only if no upstream fix exists, re-probe the failing endpoint with curl
(details page, chapter list, or reader page), diff against the template's selectors, add the
override, bump `versionCode`, push.

## Gotchas learned the hard way

- Scaffold tabs → Spotless failure is the #1 repeat offender. Always run the sed fix.
- GitHub secrets set via `gh secret set --body` must contain the bare value; a file line like
  `KEY_STORE_PASSWORD=xyz` pasted whole makes signing fail with "keystore password was incorrect".
- The first CI run on a fresh fork builds EVERY extension (prepare diffs against the last
  successful run; none exists → empty tree). This repo already had its first run; incremental
  pushes are cheap.
- The `repo` branch must exist with an `index.json` (`{}` is enough) or the publish job fails.
  It already exists; recreating it is only needed if someone force-deletes the branch.
- Android/iOS signing is sticky: once users install a source, its APKs must keep the same key
  forever. The key lives only in GitHub secrets — do not regenerate, do not re-run any
  keystore-generating workflow.
- `contentWarning` values: `SAFE`, `MIXED`, `NSFW` (import `io.github.keiyoushi.gradle.api.ContentWarning`).
