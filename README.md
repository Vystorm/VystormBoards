# Vystorm Boards

**Live billboards for your server.** Hang a board of any size on a wall with one command and fill it with images, styled texts, live placeholders, player faces and buttons. Every player can see their own version: "Hello Steve", their rank, their balance.

- `/boards create <name> <width> <height>` while looking at a wall: invisible glow frames with maps appear, the block you look at is the top-left corner. Up to 16x16 frames.
- Content in `boards.yml`: rectangles, images (PNG/JPG from `images/`, fitted and dithered for the map palette), MiniMessage texts in any font and size with alignment and shadow, player heads.
- Placeholders `{player}` `{world}` `{online}` `{max}` `{time}` `{date}`, countdowns `{countdown:2026-12-24T18:00|Merry Christmas!}` (time left, then the text after `|`), plus every PlaceholderAPI placeholder when installed.
- Per-player rendering only where needed; content is recomposed only when it actually changes, at most every `refresh-seconds`.
- Click areas: run a command as the player or the console, or send a message. Left and right click both work. Areas can be tied to a page (`page: 2`) or a permission (`permission: vip` / `'!vip'`) and can have a per-player cooldown (`cooldown: 86400` for a daily reward).
- Pages: a board can rotate through several pages (`pages`, `page-seconds`), e.g. news, rules and a shop.
- Animated GIFs play on the board (frame rate capped by `animation-fps`).
- Progress bars (`type: progress`) fed by placeholders, e.g. a server goal or the player's balance.
- Elements with `permission: vip` (or `!vip`) show only to players with (or without) that permission.
- Texts shrink to fit their box, `/boards info NAME` shows size, pages, mode and map ids.
- Frames cannot be broken, rotated or emptied; the board survives restarts (maps are reattached on load).
- **Lightweight packet mode** (`render-mode: auto`): boards are sent to nearby players as client-only frames and maps, so the server has no item frame entities, no map ticking and no map rendering for them. With 200 boards and 5 players this saved about 500 MB of memory and cut the tick time and bandwidth by roughly 40 % compared to real frames. Falls back to real frames on servers Core cannot support; switching modes removes or re-hangs the frames automatically.
- **Rankings** (`type: top`): live leaderboards for playtime, kills, mined diamonds, balance or any scoreboard – with faces and medals. See below.
- **Polls**: board buttons with `vote:` let players vote with one click; results show live on the board. See below.
- `/boards create … <world> <x> <y> <z> <facing>` from the console, `/boards remove`, `/boards list`, `/boards reload`.
- Texts in seven languages (`lang/en.yml`, `de`, `fr`, `it`, `pt_br`, `ru` and `zh_cn`) through the Vystorm Core language system (player language, `/vadmin language audit`); `config.yml` is editable in game with `/vadmin config`.
- Needs [Vystorm Core](https://www.spigotmc.org/resources/139848/) 0.65.0 or newer (free); PlaceholderAPI optional. What the build checks: the plugin is compiled for Java 21 (`--release 21`) against the Paper API 1.21.11 and, in `compat1206`, against Paper 1.20.6; packet mode needs a server where Core supports client-only displays (Paper/Purpur 1.21.8 to 26.2), elsewhere boards use real item frames. Use the Java version your server needs.
- Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.
- On older servers, packet rendering uses real item frames and maps when Core's runtime capability probe cannot support client displays. Board content, GIFs, personal pages, clicks, rankings and polls remain available. Ranking sources for materials, entities or statistics absent on that server are skipped and logged; use sources available on that server.

## In-game clock

Use `{world_time}` in a text element to show the viewer's Minecraft world time as `HH:mm` (tick 0 = `06:00`, tick 18000 = `00:00`). It follows sleep, `/time` and a paused daylight cycle, and refreshes with the board's existing refresh interval. `{time}` continues to show the server's real-world clock. No PlaceholderAPI, extra task or world/chunk load is required.

```yaml
- {type: text, x: 16, y: 16, text: "In-game time: {world_time}", size: 20}
```

## Rankings

Hang a "Top 10" on your spawn wall. A `top` element draws a ranking: place (gold, silver and bronze for the first three), the player's face, name and value.

```yaml
elements:
  - {type: top, source: "stat:PLAY_ONE_MINUTE", x: 16, y: 48, width: 480, rows: 10, size: 18, format: time, label: Playtime}
  - {type: top, source: "placeholder:%vault_eco_balance%", rows: 5, format: decimal, highlight: true}
  - {type: top, source: "objective:kills", rows: 5, order: highest}
```

- **Sources:** `stat:NAME` for any Minecraft statistic (`PLAY_ONE_MINUTE`, `PLAYER_KILLS`, `DEATHS`, `JUMP`, `MINE_BLOCK:DIAMOND_ORE`, `KILL_ENTITY:ZOMBIE` …), `placeholder:%…%` for any PlaceholderAPI number, `objective:NAME` for a scoreboard objective (live).
- Statistic and placeholder values are read from online players every `sample-seconds` and when they leave, and kept in `leaderboards.yml`, so the ranking also lists offline players. The first time a statistic ranking is used, the saved statistics of offline players are read once (spread over several ticks), so playtime rankings are complete right away.
- **Options:** `rows` (1-50), `size`, `color`, `value-color`, `format` (`number`, `decimal`, `time` for statistic ticks, `seconds`, `distance` for centimetre statistics), `order: lowest` (smallest value first), `heads`, `medals`, `font`, `label` (name in chat and menus) and `highlight: true` (the viewer's row is yellow and their own place is added below the list; makes the board per player).
- `/boards top` lists the rankings on your boards (click one), `/boards top SOURCE` shows the first ten in chat, `/boards top SOURCE reset` forgets the stored values.
- **Player menu**: sneak and right-click a player to see their places in all rankings.

## Guestbooks (1.14.0)

A click area with `guestbook: ID` lets players leave a short line; texts show the newest entries with `{guestbook:ID:1}` (newest), `{guestbook:ID:N:name}`, `{guestbook:ID:N:text}` and `{guestbook:ID:count}`. Admins clean up with `/boards guestbook ID`.

## Polls

Let players decide the next event, map or build right on the board. A click area with `vote: "poll:option"` is a poll button; texts and progress bars show the result live.

```yaml
elements:
  - {type: text, x: 0, y: 8, size: 22, align: center, text: "<b>Next event?</b><br><gray>You voted: {vote:event}"}
  - {type: text, x: 16, y: 80, size: 18, text: "PvP tournament – {votes:event:pvp} votes"}
  - {type: progress, x: 16, y: 104, width: 220, height: 10, value: "{votes:event:pvp}", max: "{votes:event}"}
  - {type: text, x: 272, y: 80, size: 18, text: "Build contest – {votes:event:build:%} %"}
  - {type: progress, x: 272, y: 104, width: 220, height: 10, value: "{votes:event:build}", max: "{votes:event}"}
clicks:
  - {x: 0, y: 64, width: 256, height: 64, vote: "event:pvp"}
  - {x: 256, y: 64, width: 256, height: 64, vote: "event:build", command: "eco give {player} 10", as: console}
```

- One vote per player and poll, stored in `polls.yml`. Clicking another option moves the vote (turn off with `polls.allow-change: false`).
- Placeholders: `{votes:POLL:OPTION}` votes, `{votes:POLL:OPTION:%}` percent, `{votes:POLL}` all votes, `{vote:POLL}` the viewer's choice.
- A `command` on a vote button runs only for a player's first vote, so it can be a small thank-you reward. `message` replaces the default "You voted for …".
- Vote buttons still respect `page`, `permission` and `cooldown`.
- `/boards poll` lists all polls, `/boards poll ID` shows the results with clickable **Close**, **Open**, **Reset** buttons (reset asks for confirmation).

- **Automatic closing:** `/boards deadline ID 2026-12-24T18:00` sets a deadline in the server time zone (a date alone means midnight at its start). Only future dates are accepted. `/boards deadline ID` shows it; `/boards poll ID` also includes it. Deadlines survive restarts and reject new or changed votes at the exact deadline, including after server downtime.
- `/boards deadline ID clear` removes the deadline, leaving votes and the manual closed state intact. `/boards poll ID open` reopens the poll and clears its deadline; resetting votes keeps it. Setting a deadline does not reopen a manually closed poll. Uses `vystormboards.admin`; no new config keys.

## Personal page navigation

Turn a rotating board into a browsable rules wall, guide or catalogue. Add `navigate` to a click area in `boards.yml`:

```yaml
elements:
  - {type: text, x: 8, y: 8, text: "{page}/{pages}"}
pages:
  - [{type: text, x: 8, y: 40, text: "Welcome!"}]
  - [{type: text, x: 8, y: 40, text: "Server rules"}]
clicks:
  - {x: 0, y: 96, width: 40, height: 32, navigate: previous}
  - {x: 40, y: 96, width: 48, height: 32, navigate: auto}
  - {x: 88, y: 96, width: 40, height: 32, navigate: next}
```

Draw your own button labels in `elements`. `next` and `previous` wrap around; a quoted number such as `navigate: "2"` opens that page (1-based). The selected page stays pinned for that player until they click `auto`, disconnect, or boards reload. Other viewers keep their own selection or automatic rotation. Both packet and entity modes support navigation; GIFs keep animating on pinned pages.

`{page}` and `{pages}` show the current page and total (both 1 without pages). The existing `page`, `permission` and `cooldown` click options apply to navigation too, and page-bound buttons follow the viewer's selected page. A navigation button only navigates: `command`, `vote` and `message` on the same area are ignored. Invalid targets leave the selection unchanged and show a localized error.

The optional `clicks[].navigate` key defaults to empty (ordinary click action). Existing boards require no migration or edits and continue rotating. No new commands or permissions; restrict individual buttons with their `permission` option as needed.

## Commands

`/boards create NAME WIDTH HEIGHT`, `/boards info NAME`, `/boards list`, `/boards remove NAME`, `/boards reload`, `/boards top [SOURCE] [reset]`, `/boards poll [ID] [close|open|reset]`, `/boards deadline ID [TIME|clear]`, `/boards render` (render mode and packet counters).

## Permissions

`vystormboards.admin` (op): all `/boards` commands. Clicking boards needs no permission; the commands behind a click check their own permissions when run as the player.
`vystormboards.rankings` (everyone): the "Rankings" entry in the Vystorm Core player menu.

## Config

Every feature has an on/off switch in `config.yml` (all on by default, listed in Vystorm Core's `/vadmin features`, applied without a restart except `tutorial.enabled`): `boards.enabled` (the whole plugin), `frame-protection.enabled`, `images.enabled`, `animated-images.enabled`, `text.enabled`, `rectangles.enabled`, `player-heads.enabled`, `progress-bars.enabled`, `placeholders.enabled`, `countdowns.enabled`, `placeholderapi`, `per-player.enabled`, `pages.enabled`, `click-areas.enabled`, `packet-mode.enabled`, `render-diagnostics.enabled`, `leaderboards.enabled`, `leaderboards.player-menu`, `leaderboards.seed-offline-stats`, `polls.enabled`, `polls.allow-change`, `polls.deadlines.enabled`, `guestbooks.enabled`, `snapshot-photos.enabled`, `world-clock.enabled`, `page-navigation.enabled`, `page-editor.enabled`, `tutorial.enabled`, `defaults.glow`. A switched-off feature answers "This feature is switched off on this server."; saved boards, rankings, votes and guestbooks are kept.

`render-mode` (auto, packets or entities), `packet-view-distance` (48), `animation-fps`, `placeholderapi`, `defaults.glow`, `defaults.background`;
`leaderboards.enabled` (true), `leaderboards.sample-seconds` (60), `leaderboards.seed-offline-stats` (true), `leaderboards.max-players` (1000), `leaderboards.player-menu` (true);
`polls.enabled` (true), `polls.allow-change` (true).

## Placeholders

With Vystorm Core 0.65.0 the plugin registers the namespace `boards` (server thread; empty while the feature is switched off): `votes`, `votes_percent`, `vote`, `poll_open`, `guestbook_count`, `guestbook_name`, `guestbook_text`, `rank`, `rank_value`, `top_name`, `top_value`, `board_count`. Examples: `%vystorm_boards_votes_event_pvp%` (votes for option pvp of poll event, also `{boards:votes:event:pvp}`) and `%vystorm_boards_rank_play_one_minute%` (the viewer's place in the playtime ranking). `/vadmin placeholders boards` lists them. In the percent form an id with an underscore splits into arguments; use the brace form for such ids.

## Building

Build with JDK 25; all Java sources compile with `--release 21`. Supply Core with `-PvystormCoreJar=<path-to-core.jar>` (default `libs/Vystorm_Core.jar`).

`check` includes `compat1206`, which separately compiles all main sources against the offline Paper 1.20.6 library tree, without the current Paper API on its classpath. Set `-Ppaper1206Libraries=<libraries-directory>` to override `<home>/source/tools/smoke-1206/libraries`. The check fails with setup instructions if those libraries are missing. No additional modern API adapter is needed by Boards; Core already provides runtime detection and fallback for client displays.

`./gradlew build` → `build/libs/VystormBoards-<version>.jar`

## Page editor with private preview

Administrators with `vystormboards.admin` can open `/boards edit NAME` to edit a detached draft. Add or duplicate pages, edit text elements, remove elements, reorder pages and set the rotation interval. Other fixed content and board metadata are preserved. Page-specific click bindings and numeric navigation targets follow reordered pages; bindings involving a deleted page are removed.

Preview gives the administrator a personal map containing the current draft page, scaled to fit while preserving its aspect ratio. It is visible only to that administrator while they retain permission, for two minutes. A full inventory prevents allocation; existing items are never replaced. `/boards edit` returns to the draft. Preview images are temporary, and `editor-previews.yml` stores reusable map IDs only (maximum 256 administrators).

Saving requires confirmation and refuses to overwrite any intervening edit to `boards.yml`. Discarding a draft leaves the live board intact. Drafts expire after 30 minutes and on logout; their limits are 32 pages and 45 elements per page, with up to 240 characters per edited text. Existing larger configurations remain usable, but must be edited in YAML. The editor is translated into all seven bundled languages.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.17.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.17.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.17.0.txt](LICENSE-MIT-until-1.17.0.txt)) and stay available under those terms.
