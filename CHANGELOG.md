## 1.18.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The protection of board frames (breaking, hitting and use) now denies before other plugins act.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.17.0 — 2026-11-16

- Four more features can be switched off on their own in `config.yml` (all on by default, shown as feature switches in Vystorm Core 0.60.0): the in-game clock (`world-clock.enabled`), personal page navigation (`page-navigation.enabled`), the page editor with private preview (`page-editor.enabled`) and photo slideshows (`snapshot-photos.enabled`).
- A switched-off feature answers "This feature is turned off on this server." in the player's language and does nothing else. Boards, pages and elements stay in `boards.yml` untouched and work again as soon as the switch is back on.
- With the clock off `{world_time}` stays blank, with navigation off every viewer follows the page rotation, with the editor off open drafts end unsaved and `/boards` shows the board list, and with photos off the slideshow images stay empty.

## 1.16.0 — 2026-11-15

- Rebuild the board editor's page overview and element controls as a Core Panel with selection, tabs, inline text and interval fields, and confirmation for destructive actions.
- Keep both original chest views as fallbacks for unavailable panels or older Core; no item-moving chest menus exist in this plugin.

## 1.15.0 — 2026-11-12

- Photo slideshow boards: `/boards photos <board> [gallery|newest|player:NAME]` turns a board into a slideshow of VystormSnapshot photos – the photos on show in the Snapshot gallery, the newest photos or one photographer's photos. Any image element can use the same sources as its file (`snapshot:gallery`, `snapshot:newest`, `snapshot:player:NAME`). New photos appear within a minute; time per photo and the number of photos under `snapshot-photos`. Parts of big photos are skipped.

## 1.14.0 — 2026-11-11

- Guestbook boards: a click area with `guestbook: ID` opens a dialog where players leave one short line (60 characters, plain text, 60 s cooldown). Show the newest entries with `{guestbook:ID:1}`, `{guestbook:ID:2:name}`, `{guestbook:ID:2:text}` and `{guestbook:ID:count}`. `/boards guestbook [ID] [remove N|clear]` lists and cleans up. Saved in `guestbooks.yml`.

## 1.13.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- /boards without arguments opens the board editor (console: the board list).

## 1.12.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.11.1 (2026-11-08)

- Protect saved boards, rankings and votes when writes fail; prevent duplicate board names from overwriting skipped boards.
- Restore entity maps and frames correctly when individual packet boards fall back, including after chunk loads.
- Prevent rewards from disabled, invalid or unreadable polls; preserve newly opened polls and resolve uppercase vote placeholders.
- Keep newer ranking samples and last-seen times, and apply reduced player limits correctly.
- Fix mixed-case board names, editor navigation bindings and preview page numbers; close editor inventories on disable.
- Handle invalid image paths and profile names safely, and respect GIF frame disposal and individual frame delays.
- Discard outdated skin downloads after reconnects and release expired cooldowns of disconnected players.
- Correct translated command hints and poll buttons across the bundled languages.

## 1.11.0 - Page editor with private preview

Administrators with `vystormboards.admin` can open `/boards edit NAME` to edit a detached draft. Add or duplicate pages, edit text elements, remove elements, reorder pages and set the rotation interval. Other fixed content and board metadata are preserved. Page-specific click bindings and numeric navigation targets follow reordered pages; bindings involving a deleted page are removed.

Preview gives the administrator a personal map containing the current draft page, scaled to fit while preserving its aspect ratio. It is visible only to that administrator while they retain permission, for two minutes. A full inventory prevents allocation; existing items are never replaced. `/boards edit` returns to the draft. Preview images are temporary, and `editor-previews.yml` stores reusable map IDs only (maximum 256 administrators).

Saving requires confirmation and refuses to overwrite any intervening edit to `boards.yml`. Discarding a draft leaves the live board intact. Drafts expire after 30 minutes and on logout; their limits are 32 pages and 45 elements per page, with up to 240 characters per edited text. Existing larger configurations remain usable, but must be edited in YAML. The editor is translated into all seven bundled languages.

## 1.10.0 - Spigot and CraftBukkit compatibility

- Require Vystorm Core 0.51.0 or newer.
- Preserve live player skins through Core profiles and use portable ray tracing and last-played lookups.
- Validation: 75 automated checks (0 skipped), plus combined startup, listener registration and Core diagnostics/help on official Spigot26.3 and CraftBukkit26.3.
- Platform detail: Core0.51 uses the existing actual item-frame backend on Spigot/CraftBukkit when client-only packet displays are unavailable; forced packet mode cannot be used there.

## 1.9.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.8.0 (2026-11-05)

- Add `{world_time}` for the viewer's in-game clock (`HH:mm`, tick zero = 06:00). It follows world time changes and the existing board refresh interval, without an extra scheduler or chunk loading. `{time}` remains the real-world clock.

# Changelog

## 1.7.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode and `api-version: '1.20.6'`; use the Java runtime required by your server. Core minimum stays 0.45.0; on 1.20.6 use a compatible Core build, such as 0.48.0.
- Older servers use real item frames and maps when Core cannot provide packet rendering. All board features remain available; ranking sources referring to materials, mobs or statistics missing on the server are skipped with a diagnostic. Existing behavior on current servers is unchanged.
- Added the offline `compat1206` compilation check to `check` and regression tests for Core capability detection, packet selection and entity fallback. No newer Paper API calls require a separate adapter module.

## 1.6.0 (2026-11-02)

- **Personal page navigation**: click areas with `navigate: next|previous|auto` or a 1-based page number let each player browse a board independently. Selections stay pinned until automatic rotation is resumed, the player disconnects or boards reload. Page-bound clicks follow the selected page, permissions and cooldowns still apply, and GIFs keep animating. Works in packet and entity mode. New `{page}` / `{pages}` placeholders, localized feedback in English, German and Simplified Chinese, and unit tests for navigation, rendering and viewer isolation. Existing boards keep rotating without changes; no new permissions or commands.

## 1.5.0 (2026-11-02)

- **Poll deadlines**: `/boards deadline ID [TIME|clear]` shows, sets or removes an automatic closing time (server time zone). Deadlines are saved with votes and enforced on every vote, including after a restart. Poll results show the deadline; opening a poll clears it, resetting votes keeps it. Uses the existing admin permission and Core commands with English, German and Simplified Chinese text.

## 1.4.0 (unreleased)

- **Countdowns on boards**: the new placeholder `{countdown:2026-12-24T18:00}` shows the time left until that moment (server time zone), as `04:12:09` or `3d 04:12:09`, live on any text or progress element. After the moment it shows the text after a `|` – `{countdown:2026-12-24T18:00|Merry Christmas!}` – or `00:00:00`. A date alone (`{countdown:2026-12-31}`) counts to midnight at its start. The day word comes from the language files (`countdown-days`).

## 1.3.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- `/boards` now runs through Vystorm Core's command system: subcommands, keywords (`close`, `open`, `reset`, `confirm`), help, tab completion and error messages in the player's language (`/boards liste`, `/boards umfrage ID schließen`, `/boards 排行` work next to the English names for everyone), a clickable `/boards help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/anzeigetafeln` (German) and `/看板` (Chinese) from the language files (`commands.boards.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). All English commands, the console form of `/boards create` and the click actions on boards work as before. The command is no longer declared in plugin.yml.

## 1.2.0 (2026-10-30)

- **Rankings on boards**: new element `type: top` draws a live leaderboard with rank, medal colours for the first three, player faces, names and values. Sources: any Minecraft statistic (`stat:PLAY_ONE_MINUTE`, `stat:MINE_BLOCK:DIAMOND_ORE`, `stat:KILL_ENTITY:ZOMBIE` …), any PlaceholderAPI number (`placeholder:%vault_eco_balance%`) or a scoreboard objective (`objective:kills`). Values of online players are sampled every `leaderboards.sample-seconds` and on quit and stored in `leaderboards.yml`, so offline players stay on the board; the first time a statistic is used the saved statistics of offline players are read once. Options: `rows`, `format` (number, decimal, time, seconds, distance), `order: lowest`, `heads`, `medals`, `highlight` (marks the viewer and adds their own place under the list), `label`. `/boards top` lists the rankings in use (clickable), `/boards top SOURCE` shows the first ten, `/boards top SOURCE reset` forgets stored values. With Vystorm Core 0.43.0 players see someone's places in the player menu (sneak + right-click a player, permission `vystormboards.rankings`, default everyone). Config: `leaderboards.*`.
- **Polls on boards**: a click area with `vote: "event:pvp"` becomes a poll button. Every player has one vote per poll (moving it to another option is allowed unless `polls.allow-change: false`), votes are stored in `polls.yml`. Results go straight onto the board with `{votes:event:pvp}`, `{votes:event:pvp:%}`, `{votes:event}` and `{vote:event}` (the viewer's own choice) – also as progress bars. A `command` on a vote button runs only for the first vote (e.g. a small reward). `/boards poll` lists polls, `/boards poll ID` shows the results with clickable buttons to close, reopen or reset (with confirmation). Config: `polls.*`.

- **Packet mode** (needs Vystorm Core 0.44.0): new `render-mode: auto|packets|entities` (default `auto`). In packet mode every board is a Vystorm Core client-only map wall: players within `packet-view-distance` (48 blocks) get the frames and maps as packets, the server keeps no item frame entities and no maps and renders nothing per tick. `auto` uses packets when Core supports them on the server (Paper/Purpur 1.21.8 to 26.2) and real frames otherwise; `packets` does the same but warns when it falls back; `entities` is the previous behaviour. Everything keeps working: GIFs, pages, per-player pictures, click areas with page/permission/cooldown, rankings and polls (clicks have the vanilla reach). Migration is safe: a board's real frames are removed only after its packet wall was created, and switching back to `entities` hangs them again (boards created in packet mode get their maps then). `/boards render` shows the active mode and Core's packet counters.
- Measured with 200 boards (2757 frames) and 5 protocol bots on Paper 1.21.8: 0 instead of 2757 entities, live heap 366 instead of 865 MB, average tick 7-12 instead of 10-23 ms, about 36 instead of 57 KiB/s per player after compression.
- Faster drawing for every mode: the colour matching now uses a flat lookup table instead of a boxed hash map, and the board picture is read straight from the image buffer. On the 200-board test this took the average tick with 5 players from about 30 ms down to about 10 ms.

Fixed (before release):

- The first use of a `stat:` ranking read the saved statistics of offline players on the server thread (20 players per tick through the Bukkit API, each loading its stats file), which caused a lag spike on servers with many players. The stats files (`<world>/players/stats`, older worlds `<world>/stats`) are now read and parsed on an async thread and only the results are put into the ranking on the server thread.
- A YAML error in `boards.yml` was read as an empty file, and the next `/boards create` or `/boards remove` then rewrote `boards.yml` with only that one change, so every other board was lost. An unreadable `boards.yml` is now reported in the console, the file is left untouched and `/boards create|remove` are refused until it is fixed (`/boards reload`).

## 1.1.0 (2026-10-29)

- Click areas can be tied to a page (`page: 2`) and a permission (`permission: vip` or `'!vip'`). On boards with rotating pages a button now only reacts while its page is shown, and areas a player does not match let the click fall through to the area underneath.
- Click areas can have a per-player cooldown in seconds (`cooldown: 86400` for a daily reward button). Players clicking too early get "try again in N s"; the cooldown survives relogging.

## 1.0.1 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 1.0.0 (2026-10-28)

- First release: wall boards from glow item frames and maps, images with dithering, MiniMessage texts with fonts, alignment and shadow, player heads, built-in and PlaceholderAPI placeholders, per-player rendering, change-based refresh, click areas with commands and messages, protected frames, console create, English and German messages.
- Built on Vystorm Core: Core language system (`lang/en.yml`, `lang/de.yml`), in-game config editor (`/vadmin config`), Core task groups and atomic file writes.
- Rotating pages, animated GIFs, progress bars, permission-gated elements, texts that shrink to fit, `/boards info`.
