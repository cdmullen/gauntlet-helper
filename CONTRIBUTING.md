# Contributing to Gauntlet Helper

Bug reports and pull requests are welcome. This page covers where things live, the rules a change has to respect and what happens to a pull request.

## Code map

Production code is under `src/main/java/com/gauntlethelper`.

| Package | What lives there |
| --- | --- |
| `com.gauntlethelper` | `GauntletHelperPlugin` (the entry point named in `runelite-plugin.properties`) with its helpers `TickHandler` (per-tick observation) and `MenuHandler` (drop clicks, menu order) |
| `.config` | The settings: `GauntletHelperConfig`, `ResourceSettings` and the setting enums (`ArmorGoal`, `WeaponPair`, `ResourceDisplay`) |
| `.game` | Which Gauntlet this is and the shared vocabulary: `GauntletMode`, `GauntletIds`, `Tier`, `ArmorSlot`, `EnemyType`, `ObjectKind` |
| `.plan` | What is still needed and how to get it: `Goals`, `Requirements`, `Instructions`, `Inventory`, `HomeCraftPlan`, `TripPlanner`, `Simulation`, `GroundLoot` and the potion, weapon and demi-boss planning classes |
| `.map` | The 7x7 dungeon model: `GauntletMap`, `GauntletRoom`, `OpeningRoute`, `PerimeterSearch` |
| `.guidance` | Live guidance built on `.plan` and `.map`: `Guidance` (the facade and the coordinator of one tick), `TaskPriorities` (the ordered task steps), `NextTask`, `Routing`, `LocalPathfinder`, `RoomRoutePlanner` and the observers |
| `.overlay` | Everything drawn: `TasksOverlay`, `WorldOverlay`, `ResourceOverlay`, `InventoryOverlay`, `CraftOverlay`, `MinimapOverlay`, `GroundLootOverlay`, `BossTileOverlay`, `TrackerInfoBox` |
| `.boss` | The boss-fight modes: `HunllefProjectiles`, `HunllefProjectileStyle`, `PrayerTileMode`, `TornadoTileMode` |
| `.record` | The attempt recorder behind the Log attempts setting: `AttemptCapture`, `AttemptFrame`, `AttemptRecorder` |

Where to start:

- **A setting:** add the `@ConfigItem` method to `GauntletHelperConfig` (its method name is the saved key), read it where it is used, list it in `docs/product/SETTINGS.md` and add its line to `AttemptFrame.readConfigValues`, which lists the settings by hand for the recording header.
- **Something drawn:** the matching class in `.overlay`. Overlays only read state the plugin has already built, and each restores the `Graphics2D` state it changes.
- **A guidance or route rule:** the numbers (what is still needed) come from `.plan`, starting at `Requirements`; the choice of the current task is a step in `TaskPriorities`, and the Next preview is in `NextTask`. Read `Guidance.update` for the order things run in.
- **What the plugin knows about the dungeon:** `MapObserver` and `ObjectObserver` write it into `GauntletMap`.

## Rules

- **Everything stays manual.** The plugin observes the game and draws highlights. No attack prediction, prayer-switch advice or safe-spot routing.
- **Saved names are frozen.** Every `@ConfigItem` key name, every setting enum constant, the config group `gauntlethelper`, the simple names `GauntletHelperPlugin`, `TasksOverlay` and `GauntletTimerOverlay` and the internal name `gauntlet-helper` are stored in users' RuneLite profiles and data folders. Renaming one silently resets that state.
- **`.guidance` sits above the model.** `.plan` and `.map` never import `.guidance`.
- **Threads.** Game observation and mutable planning stay on the client thread; rendering only reads state.
- **Config helpers.** Keep parameterized helper methods out of `GauntletHelperConfig`: RuneLite's config proxy can return null for them.
- **Plans copy state.** A new field in the plan has to carry through the simulation copies and the planner's cache keys.

## Pull requests

- Keep each one small and about one change. Say what in the game it fixes or adds.
- For a guidance bug, turn on **Log attempts** and attach the recording of the attempt. It holds what the plugin saw and said at every tick, which makes the bug reproducible.
- New or changed settings go in `docs/product/SETTINGS.md`.

This repository's CI only checks that the code compiles. The full automated test suite, including checks of guidance against recorded runs, runs in the maintainer's private repository, because it depends on recordings and tools kept there. A guidance change therefore can't be proven here.

So a pull request is reviewed here, and once it is accepted the maintainer applies it in the private repository, runs the full suite and publishes it with the next release commit. That commit can fold in several changes, so your pull request may be closed with a note pointing to the release rather than merged directly. You are credited as a co-author on the commit that carries your change. If the suite turns up a problem, the maintainer will say so on the pull request.
