## Spigot/CraftBukkit release 1.10.0

Requires Core0.64.0+. Official Spigot26.3 and CraftBukkit26.3 combined startup and registration tests passed. Existing automated tests and configured older-Paper compilation checks passed. This is not a claim that every gameplay scenario was exercised on each platform.

Core0.51 uses the existing actual item-frame backend on Spigot/CraftBukkit when client-only packet displays are unavailable; forced packet mode cannot be used there.

## 1.11.0 feature validation

Exact release JAR SHA-256: `46012dfc92052f47568c5263d3d80016ac3bb5b368dbea7ef1083febac704c9b`. Automated checks: test: 82 tests, 0 failures, 0 skipped. Older Paper API compilation/compatibility tasks also passed.

Connected-player acceptance on official Spigot 26.3 and CraftBukkit 26.3, including a complete server restart: Nonadmin editor denied; actual inventory editing and portable prompts; detached draft unchanged live file until explicit confirmation; owner rendered preview vs visitor black contextual renderer, concurrent file conflict refused, saved page and private renderer survive restart. MapCanvas records renderer pixels while actual sendMap executes against connected player.

All seven new language namespaces passed key and placeholder checks. Independent review found no unresolved concrete defects. Combined acceptance harness and evidence are secured in `vystorm-chat/integration/free-features-2026-11`. These are targeted tests of the new feature, not exhaustive replay of every existing plugin feature or third-party integration.
