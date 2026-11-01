# Handoff: Thai source repairs and upstream sync

## Repository state

- Repository: `D:\Playground\erdachi-extensions\extensions-source`, branch `main`.
- Upstream merge completed locally in commit `91148a645` (`Merge branch 'upstream/main' into main`).
- Merge conflicts were resolved while retaining the hub's publish/signing configuration; upstream's upload-artifact action bump and `FULL_REBUILD` argument were carried over. The removed `zizmor.yml` remains deleted.
- Merge has **not** been pushed. Do not regenerate or modify the signing key.

## Source changes in progress

- **BKKManga**: `versionCode` 2 → 3. Reader pages now parse the site's `chapter_preloaded_images` JSON array, which contains all pages even though only the first page is rendered in the document. Existing Madara AJAX chapter retrieval remains in use.
- **DoujinThai**: `versionCode` 1 → 2. Listing/search entries and old `/t<id>` URLs now normalize to `/forum/index.php?topic=<id>.0`, the reliable SMF topic route. Live probes showed forum requests can intermittently time out; successful topic responses contained page images.
- **Afterway**: `versionCode` 1 → 2. The source now uses the current `/api/project` and `/api/chapters` routes and current CDN image paths/data. BrowserOS successfully loaded the site after a human passed Cloudflare verification; plain HTTP requests were still blocked with 403. The Mihon/KeiSource Cloudflare interceptor is expected to handle challenges, but this has **not** been verified inside the reader app. README marks it as potentially blocked.
- **MangaThaiPlus**: after the upstream sync, the first CI run exposed stale `client.get(...)` calls from before the new required `Fetch` argument. Updated document/API requests with `Fetch.DOCUMENT`/`Fetch.XHR`, bumped `versionCode` 1 → 2, and ran targeted Spotless successfully.
- **DoujinTH**: the next CI pass exposed the same pre-migration `client.get(...)` issue in four document requests. Added `Fetch.DOCUMENT`, bumped `versionCode` 1 → 2, and queued this for another build.

## README

`README.md` catalog versions/status now reflect Afterway 1.6.2 (Cloudflare caveat), BKKManga 1.6.60 (Madara base 57 + module version 3), and DoujinThai 1.6.2. Other catalog entries are unchanged.

## Verification and next steps

- Ran targeted `spotlessApply` for BKKManga, DoujinThai, and Afterway successfully.
- Release lint has **not** been run. Next run:

  ```powershell
  ./gradlew.bat :src:th:bkkmanga:lintRelease :src:th:doujinthai:lintRelease :src:th:afterway:lintRelease
  ```

- Inspect/fix any compile or lint failures, then review `git diff` and `git status`.
- Post-sync CI first failed on MangaThaiPlus; its fix was pushed, and the next run then failed on DoujinTH for the same missing `Fetch` arguments. DoujinTH's fix is now local and needs a push/CI run.
- The Afterway endpoint migration needs an in-app check, especially Cloudflare challenge handling and images. If the app still gets blocked, keep that limitation documented rather than adding an unverified bypass.
- Commit source changes separately from the existing merge commit if appropriate; push only when ready. A push runs the repository build/publish workflow and expands the catalog due to the upstream sync.
- `afterway/` contains untracked probe artifacts from prior investigation; preserve them unless the user asks otherwise.
