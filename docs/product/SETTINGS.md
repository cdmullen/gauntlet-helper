# Settings reference

Every setting of Gauntlet Helper, its saved key where it matters, and its default. Defaults apply when no explicit saved value exists. A new default does not overwrite a custom saved setting.

Each saved key is the setting's name written as a config method, as in RuneLite's own plugins. An on/off key starts with a verb (show, highlight, hide, outline, use, block, reorder, log). Colors, numbers and tracker modes are nouns.

Settings are saved in the `gauntlethelper` config group. Attempt recordings go to `.runelite/plugin-data/gauntlet-helper`.

## Layout

**Setup** is the only section open by default. Every other section has a working default and starts closed. The sections, in order: Setup, Guide, Overlay, Highlights, Inventory and crafting, Trackers, Mapping, Colors, Hunllef, Diagnostics.

Every prep color, the demi-boss colors included, is in Colors. The Hunllef tile colors stay in the Hunllef section.

## Setup

Setup holds the goals for both Gauntlets. The regular Hunllef hits less hard, so a lower Paddlefish target (about 12) is usually enough there.

| UI setting | Default | Meaning |
| --- | --- | --- |
| Weapon pair | Staff + either | Which two weapons to prepare: staff+bow, staff+halberd, bow+halberd, staff+either or any two. The adaptive choices commit to the upgrades you actually obtain |
| Armor goal (key armorGoal) | T1 | The armor to prepare. **None**: no armor; no ore, bark or linum tirinum is gathered and no shards are kept for armor. **T1**: basic helm, body and legs. **T2**: attuned helm, body and legs (a perfected piece you own counts for its slot). T2 needs 200 more shards and 4 more ore, bark and linum tirinum than T1: 350 shards and 7 of each material for the armor from nothing. **T3**: perfected helm, body and legs: 650 shards and 13 of each material from nothing, 500 and 10 more than T1. Above T1 the upgrades come after the vials at the singing bowl, normally on the final home visit, and the panel's armor row reads "Armor tiers n/6" (n/9 for T3). Changing it mid-run replans from what you hold and wear. The Paddlefish setting is never lowered for T2 or T3 |
| Paddlefish | 20 | Cooked paddlefish required at readiness, 0-26. Potions win over fish: the plan caps the target at 28 slots minus the carried second weapon (staff wielded, armor worn) minus one slot per potion, so 2 leaves allow 25 fish and 1 leaf allows 26. When the cap lowers the target, the overlay says e.g. "Paddlefish capped at 22 (5 potions)" |
| Grym leaf | 2 | Grym leaves to gather, one Egniol potion each, 0-8. Drinking a potion does not undo its craft credit. **0**: no potions; no vials, dust or leaves are planned, and a held pestle, vial or Grym leaf gets a drop cue |

The plan counts potions crafted, one per Grym leaf. It never counts doses.

## Guide

| UI setting | Default | Meaning |
| --- | --- | --- |
| Next task (key showNextTask) | On | The next task's master switch. On: a thin Next-color path from the end of the current path (the minimap draws no Next path), the Next captions (with Task captions), the overlay's Next: line and the Next highlight (with Highlights → Tasks clickboxes). Off: none of them. The next task is worked out either way |
| Task captions (key showTaskCaptions) | On | Every task caption in the scene: the current task's, the Defeat captions, the bowl keys label and, while Next task is on, the "Next: ..." captions on the next object or demi-boss. Off keeps highlights, paths and overlay text |
| Time-aware return guidance | On | Return advice from the visible prep timer |
| Return warning seconds | 90 (0–600) | Timer threshold for late return guidance |

The current walking route always draws in the world view as a line with no end-tile marker. It also draws on the minimap with Minimap route line.

## Overlay

| UI setting | Default | Meaning |
| --- | --- | --- |
| Tasks overlay (key showTasksOverlay) | On | The prep checklist overlay. Its Next: line follows Guide → Next task |
| Timers overlay (key showTimersOverlay) | Off | The Gauntlet plugin's movable timer: Preparation time from entering, then grayed Preparation, a running Boss time and a running Total once Hunllef's fight starts. It resets on leaving. Its chat timer is not copied. While on, it also shows Time left under Preparation during prep and sits at the top of the snapped top-left stack. It counts game ticks, so Preparation + Time left = 7:30 in the Corrupted Gauntlet and 10:00 in the regular one |
| Hide the game's Time Left box (key hideTimeLeftBox) | Off | Hides the game's own Time Left box while you are in the Gauntlet, so the time shows once when the Timers overlay is on. The box comes back when this is off, the plugin stops or you leave. Off, the box stays whatever the Timers overlay does |

The time-low hint reads "Time low: finish at home", with "Suggested food target 12" on the next line; with the T2 or T3 Armor goal and an armor upgrade left, "Skip armor upgrades if short" comes between them, and at home the upgrades wait until cooking and the final food are done. The 12 is fixed and only a suggestion. Once the plan is ready for Hunllef (status "Ready for Hunllef"), the time-low hint is no longer shown; the stored-fish expiry hints are unaffected. Teleport break-even estimates use a fixed three-tick crystal activation against two running tiles per tick plus arrival-to-bowl walking.

During the opening, resources stay the primary task until the room's needed nodes are satisfied or exhausted. Required doors become current-color Light room tasks only when needed. Other doors get no highlight. Brief boundary movements do not abandon the loop, but a committed move into the center of home or an outside room counts as leaving on purpose. Room resource tours are automatic: they include the known exit or service in the resource order, and the next-task highlight follows the tour.

Current objective labels are Defeat for ordinary mobs and Light room for required doors. Task captions controls the captions. The prep status line (first loop, home prep, demi hunting, final prep) is always shown on the overlay.

## Highlights

| UI setting | Default | Meaning |
| --- | --- | --- |
| Tasks clickboxes (key highlightTasksClickboxes) | On | The current task's object highlight (door, station or tool storage clickbox; resource nodes are outlined by Resource nodes) and, while Guide → Next task is on, the Next-color highlight on the next task's object, enemy or demi-boss clickbox. That includes the room tour's exit door while the last resource is gathered. Paths and captions stay when it is off. The current kill objective and needed demis follow Targeted enemies and Targeted demi-bosses clickboxes instead |
| Resource nodes (key highlightResourceNodes) | On | Outline all observed resource nodes in their resource color. Off: no outlines, but the current and Next resource nodes keep their caption in the node's resource color |
| Hide after resource target (key hideAfterResourceTarget) | On | Stop highlighting a resource when held materials and crafted equipment satisfy the plan. It reappears if needed again |
| Targeted enemies (key highlightTargetedEnemies) | On | Teal model clickbox, optional Defeat caption and route for the current kill objective after resources: rats, spiders and bats, plus unicorns, scorpions and wolves once T1 armor and both perfected weapons are done and over 25 shards are missing, or, with the T2 or T3 Armor goal, once the attuned staff is owned and more than 80 shards (one large kill) are missing beyond what the remaining demis are forecast to drop. Without one in the current room, it routes to the nearest known room with one. Suppressed when the remaining demi kills cover the shards and frames. While a needed resource is the routed task, the room's eligible kill objective also gets the teal clickbox and Defeat caption (the caption follows Task captions), and the overlay shows "Additional: Defeat <enemy>" under Current. There is no route to it and resources keep priority. This side highlight is always on, with the same eligibility and demi suppression |
| Enemies true tiles (key highlightEnemiesTrueTiles) | On | Gray true tiles for all ordinary prep mobs, including the active kill objective. Kept during teleport advice and demi shard coverage |
| Targeted demi-bosses clickboxes (key highlightTargetedDemiBossesClickboxes) | On | Teal clickbox (2px outline, no fill), with a Defeat caption when Task captions is on, on a living demi whose drop is still needed. None shows while every missing upgrade is on the ground waiting to be picked up. No room floor border, hint arrow, minimap marker or fight information |
| Demi-bosses true tiles (key highlightDemiBossesTrueTiles) | On | The true tile outline (no fill, 1 pixel) of every living demi in view, needed or not, in its type color, after The Gauntlet plugin's demi outlines. The type colors are in Colors |
| Needed ground items (key highlightNeededGroundItems) | On | Teal item names above live needed piles, with standard stack counts. There are no extra pickup captions |
| Inventory actions (key highlightInventoryActions) | On | Colored interaction outlines, one color per action (see Colors). Empty vials also show Fill. Also the teleport crystal box while a return home is advised |
| Droppable items (key highlightDroppableItems) | On | Translucent red filled squares for drops, fish stored at home included. Exact amounts stay in the guide |
| Singing bowl crafting (key highlightSingingBowlCrafting) | On | Singing bowl menu states. Teal: every recipe this home visit will craft, with its click count under it at home (one affordable needed recipe away from home). Yellow: recipes still wanted but not makeable yet. Dim gray: recipes never or no longer needed. A wanted recipe that is makeable now but left for later by the shard budget stays plain. Nothing is disabled |

Model and tile outlines are 1 pixel, with a tile fill opacity of 50/255. These are fixed. Node colors apply to observed enabled nodes, not just the selected current task. Crafted progress and observed home-food credit affect remaining requirements.

Live observed NPCs get their tile without waiting for room-discovery metadata. Active objective tiles follow Targeted enemies. The passive gray tiles have their own control.

A living demi whose drop is still needed gets a teal clickbox under Targeted demi-bosses clickboxes. There is no room floor border. The route still holds the active useful demi room through confirmed death and needed-drop collection, and a short observation grace handles death arriving before ground items. No unseen boss, kill or acquired item is inferred. Urgent and full-inventory home returns still take priority.

## Inventory and crafting

| UI setting | Default | Meaning |
| --- | --- | --- |
| Reorder ground items (key reorderGroundItems) | On | While teal names are drawn, the Take of an item with a teal name sits above the other Takes, so on a tile with several items a left click takes a needed one first. Every ground item keeps its left-click Take above Walk here, so with only unneeded items (or stored fish before the final step) left, a left click still takes one. Nothing changes outside prep, during a teleport home or with Needed ground items off |
| Keys label for singing bowl recipes (key showKeysLabelForSingingBowlRecipes) | Off | A second line under the singing bowl's scene caption while the bowl is the current task: the bowl menu key for each click this visit, shown from the moment the bowl becomes the task. A key is written once per click, in number order as far as the free inventory slots allow, e.g. "Keys: 2, 2, 3, 4, 5, 7, 7" (two vials, helm, body, legs, basic and attuned staff); a craft with no free slot waits for a later one that frees it. The keys are the menu's fixed layout, never item names, and each weapon and armor piece keeps its key at every tier. Shown only with Task captions on |

Needed item names use Current action color (teal by default). Repeated items share one name per pile, and different item names are spaced vertically. With RuneLite Ground Items enabled, each name is drawn over that item's own Ground Items line, following its spawn order and its hidden, highlighted and Show highlighted items only settings. An item Ground Items hides gets its name above the stack. The helper reads but does not change RuneLite Ground Items settings. Drop squares are red with opacity 80/255, fish stored at home included. Each other inventory interaction has its own outline color, and any unlisted cue uses Current action color. Empty vials are the explicit Fill-caption exception.

Vial purchases and dust grinding preserve the full missing attuned-staff and armor shard budget, even while armor materials are missing. The initial gear reserve is 200 shards, reduced by completed held or equipped gear. Dust also preserves the cost of missing vials and requires a complete ten-shard grinding batch. This is automatic.

Every stored (home) raw or cooked ground-fish pickup waits for the final step and requires the total fish quota to be met. Time and expiry warnings do not permit an early pickup. Fish dropped by an enemy is picked up while the quota is short. Droppable items marks the items to drop at home.

## Trackers

Every tracker starts as **Infobox**, so needed resources are not listed on the live overlay. Each can independently be overlay, infobox, both or hidden:

- Ore tracker, Bark tracker, Linum tirinum tracker, Shards tracker, Frames tracker and Upgrades tracker (distinct upgrades).
- Grym leaves tracker and Fish tracker (total fish).

| UI setting | Default | Meaning |
| --- | --- | --- |
| Hide completed trackers (key hideCompletedTrackers) | On | Hide each satisfied tracker's overlay row and infobox. They return if required again |

Vials, dust, cooked fish, potions, armor and perfected weapon types have no infobox and no overlay row. Overlay values show have/requirement. Infobox numbers show the missing quantity. Hiding a tracker does not alter the plan.

## Mapping

| UI setting | Default | Meaning |
| --- | --- | --- |
| Minimap markers | On | Master control for minimap resource markers (each resource's gathering skill icon), a teal crystal at the spawn room center, a red one at Hunllef's room center, and the route line |
| Hide completed resource minimap icons | On | Hide a resource's minimap icons once enough of it is held or crafted for the plan (the same test as Hide after resource target, but a separate switch). They reappear if it is needed again |
| Minimap route line | On | Current walking path clipped to the minimap. Also requires Minimap markers |
| Demi-boss minimap icons | On | A combat skill icon on each living demi in view (bear attack, dark beast ranged, dragon magic), as in The Gauntlet plugin. Independent of Minimap markers |

## Colors

| UI setting | Default | Meaning |
| --- | --- | --- |
| Current action color | RGB 0,220,220 | Current instruction, needed ground item names, the teleport crystal while a return home is advised, unlisted inventory cues |
| Next action color | RGBA 155,130,105,180 | Next instruction and next-task highlight (a faint gray-orange) |
| Tracker color | White | Unfinished tracker counts on the overlay and infoboxes. Finished ones turn green |
| Drop square color | Red 255,0,0, opacity 80 | Finished tools, surplus items and fish stored at home |
| Equip outline color | Green 0,220,0 | Gear to equip |
| Fill outline color | Light blue 120,190,255 | Empty vials to fill at the water. Also the Fill caption |
| Mix outline color | Yellow 255,230,0 | Grym leaf + water vial, unfinished potion + dust |
| Grind outline color | Orange 255,140,0 | Pestle and shards to grind |
| Ore outline color | Red | Ore nodes |
| Phren roots outline color | Green | Bark nodes |
| Linum tirinum outline color | White | Linum tirinum nodes |
| Grym root outline color | Yellow | Grym roots, the nodes Grym leaves are picked from |
| Fishing spot outline color | Cyan 0,255,255 | The Gauntlet plugin's default |
| Dark beast color / Dragon color / Bear color | Green / Blue / Red | Tile color per demi type, as in The Gauntlet plugin |

## Hunllef

| Setting | Default | Meaning |
| --- | --- | --- |
| Block Hunllef entry with raw fish | On | Moves Pass and Quick-pass on Hunllef's barrier below Walk here while raw paddlefish are held, so a left click walks and entering takes a right click |
| Outline Hunllef tile | On | Hunllef's tile outline |
| Color tile by prayer | On | On: current protection, melee red, ranged green, magic blue, transition white. Inverted: green and blue swapped. Off: the Hunllef outline color. Matches The Gauntlet plugin |
| Hunllef tile outline width | 2 | 1 to 5 pixels |
| Hunllef outline color | White | Used while Color tile by prayer is Off |
| Hunllef fill color | Transparent | Tile fill color and opacity |
| Outline tornado tile | True Tile | Off / On (animated local tile) / True Tile (server world tile) |
| Tornado tile outline width | 1 pixel | 1–2 |
| Tornado outline color | Yellow | Tornado tile outline |
| Tornado fill color | Yellow, alpha 50/255 | Translucent tile fill; color and opacity configurable |
| Hunllef room tile markers | Off | A Ground Markers tile pack built in: the two middle tiles of each side of Hunllef's room (16 template tiles, regions 7512 and 7768, plane 1), 2px outline with Ground Markers' default fill |
| Hunllef room tile marker color | Yellow 241,255,0 | The pack's color (#FFF1FF00). The fill uses 50/255 of its opacity |
| Prayer-disable projectile | Original | Draws the orb of Hunllef's prayer-disable attack (Crystalline and Corrupted) as another graphic: Original (the game's own), Crystalline (teal), Corrupted (magenta), Inferno (orange splitter orb), CoX (purple Olm orb), ToB (red Sotetseg orb) or ToA (Wardens bomb). Only the look changes, not the attack. The raid choices reuse those bosses' own projectiles, so the Projectile Override plugin redraws them too when its entry for that boss (Inferno, Olm, Sotetseg or Wardens) is set to anything but Default. Adapted from the Hunllef Projectiles plugin |

The tornado polygon renderer matches The Gauntlet BossOverlay. The original plugin defaults its mode to Off. This helper defaults to True Tile.

These belong to a separate boss-only overlay. Prep routes and checklists stay hidden during Hunllef.

## Diagnostics

| Setting | Default | Meaning |
| --- | --- | --- |
| Log attempts (key logAttempts) | Off | Writes each attempt, in either Gauntlet, to `.runelite/plugin-data/gauntlet-helper/attempts/<gauntlet>/<tier>/attempt-<date>-<time>.jsonl` (gauntlet `corrupted-gauntlet` or `gauntlet`, tier `none`, `t1`, `t2` or `t3` from the Armor goal when the attempt starts): one line per tick holding only what changed (observations and the chosen guidance). For bug reports. It changes nothing in game or in the guidance |
| Keep recordings (key keepRecordings) | 50 | How many attempt recordings stay on disk. Starting a new recording deletes the oldest ones beyond this number, counted across all the gauntlet and tier folders, the new one included. 0 keeps every recording |

A recording starts at the first prep tick with the setting on (switching it on mid-prep starts one there), continues through the boss, and ends when the attempt resets, you leave the Gauntlet, the setting is switched off or the plugin stops. Only `attempt-*.jsonl` files count toward Keep recordings; nothing else in the folder is deleted. A typical attempt is well under 1 MB, so the default 50 is about 25 MB. The client log gets a "Gauntlet attempt recorder:" line with the file name at start and finish.

## Automatic route policies

Opening kills target the first staff frame and first-home funding rather than the entire run deficit. Opening food uses projected slot capacity, with no new fixed fish count. Conditional home visits, travel-food cooking and stable perimeter hunting are automatic. Needed material stacks are not marked for impossible partial drops. Non-staff final pairs account for an extra temporary prep staff where needed.
