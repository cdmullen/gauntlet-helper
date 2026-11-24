# Changelog

## 1.1.0

### New

- Room map (off by default; turn it on with Overlay → Map overlay). A 7x7 map of the dungeon in the top-left corner during prep. It shows only what you've seen: lit rooms, your room, home, Hunllef, and the resources and demi-bosses seen in lit rooms. Unlit rooms show only the fixed layout: the guaranteed demi rooms in red, the other edge and corner rooms in orange.
- Map settings section: Map style (Classic, which uses Gauntlet Map's tiles, or Simple), tile size, opacity, resource icons (with the option to hide completed ones), current room resources, current room highlight and the room map route.
- Guaranteed demi doors (Highlights, on by default). A soft-red "Demi room" label floats past each unlit doorway into a guaranteed demi room you haven't found yet. It shows only while a demi upgrade you selected is still missing.
- Now conflicts with Gauntlet Map, so having both enabled switches one of them off.

### Fixes

- Teleports home first after the demis when the last resource is closer to home.
- At home, every raw fish is cooked before the barrier, and cooking at the range finishes before the bowl. Cook paddlefish now shows ×n.
- The sceptre is kept while another trip out of home is still ahead, and is dropped before a teleport home.
- Returns home for the staff after a frame drops mid-hunt.
- During the demi hunt, freeing an inventory slot cancels a return home that a full inventory forced.
- When the demi sweep directions are tied, it goes the way that passes a known needed resource.
- Once you have a weapon's demi-boss drop, buying vials no longer spends the shards you need to attune that weapon.
- Clearer wording when a repeat demi kill offers another chance at the wanted drop.
- Singing bowl: no more yellow. Wanted recipes you won't craft this visit are shown plain.
- "Store fish" is now "drop fish / fish left at home" throughout. The wording for Craft T1 armor, Fish, Weapon upgrades and the tooltips is clearer.
- Boss tile and resource overlays now draw under overhead text. Room markers are outline only.
- Hunllef room tile markers are outline only, with no fill.
- The "Hide the game's Time Left box" config now also hides the box during the Hunllef fight.
- The top-left corner now stacks the map, then the timer, then the guide panel, then other plugins' infoboxes.
- The "Mapping" settings section is renamed "Minimap", and your saved settings are kept.
- Attempt recording stops for the rest of an attempt after a write failure, instead of starting new files and deleting older recordings.
- Less work every game tick, especially outside the Gauntlet.

## 1.0.0

First release: step-by-step prep guidance for the Corrupted and regular Gauntlet, with resource targets, routes and crafting cues.
