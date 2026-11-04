# Third-party notices and credits

Gauntlet Helper adapts code from, and borrows ideas from, other RuneLite plugins. This page lists the code adapted
into this plugin, with its license, and the plugins whose ideas shaped it.

## Code adapted into this plugin

### The Gauntlet, by rdutta

[The Gauntlet](https://github.com/LlemonDuck/the-gauntlet) (reference commit
`bf0246abf6dc04ce5541264c10e663536f9864c2`) is the foundation for a good part of this plugin's visuals:

- **Resource node outlines.** ResourceOverlay.java adapts the model outline rendering and the default
  ore/bark/fiber/herb/fish palette from
  [MazeOverlay.java](https://github.com/LlemonDuck/the-gauntlet/blob/main/src/main/java/ca/gauntlet/module/maze/MazeOverlay.java)
  and [TheGauntletConfig.java](https://github.com/LlemonDuck/the-gauntlet/blob/main/src/main/java/ca/gauntlet/TheGauntletConfig.java).
- **Hunllef and tornado tiles.** BossTileOverlay.java adapts the protection-prayer tile colors and copies
  `renderTornadoes` (with local setting names, an observed NPC-list adapter and the current WorldView lookup) from
  `module/boss/BossOverlay.java`, and takes Hunllef and tornado NPC identification from `module/boss/BossModule.java`.

Both files keep the original copyright notice and license in their headers, and the same notice ships inside the
plugin jar as META-INF/gauntlet-helper/THIRD_PARTY_NOTICES.txt.

~~~text
BSD 2-Clause License

Copyright (c) 2023, rdutta <https://github.com/rdutta>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
~~~

### Hunllef Projectiles, by neonsulfur (from Vardorvis Projectiles, by InfernoStats)

[Hunllef Projectiles](https://github.com/neonsulfur/hunllef-projectiles) (Plugin Hub: `hunllef-projectiles`, reference
commit `789940ebf93dc1d55c921725758f9d8c71aa5b22`) redraws the orb of Hunllef's prayer-disable attack as another boss's
projectile. It is built on InfernoStats'
[Vardorvis Projectiles](https://github.com/InfernoStats/vardorvis-projectiles), and its license carries InfernoStats'
copyright.

- **Prayer-disable projectile.** HunllefProjectiles.java adapts `replaceProjectile` from
  `HunllefProjectilesPlugin.java` (copy the orb with a new graphic, keep its path, timing and target, hide the
  original), moved to RuneLite's WorldPoint projectile API and extended to the Corrupted orb.
  HunllefProjectileStyle.java takes its Inferno, CoX, ToB and ToA graphics from `HunllefProjectilesConfig.java`.

Both files keep the original copyright notice and license in their headers.

~~~text
BSD 2-Clause License

Copyright (c) 2023, InfernoStats

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
~~~

### RuneLite example plugin (build tooling)

`gradlew`, `gradlew.bat` and `gradle/wrapper/*` come from the
[official RuneLite example plugin](https://github.com/runelite/example-plugin) at commit
`46e14f2c448fab6cbc599df1015b9631dcf1fc52`. The Gradle launcher scripts keep their Apache License 2.0 notices,
and the wrapper properties keep the upstream distribution checksum. Gradle is licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Plugin icon

`icon.png` is the Youngllef pet's detail image
([File:Youngllef_detail.png](https://oldschool.runescape.wiki/w/File:Youngllef_detail.png) on the Old School
RuneScape Wiki), scaled down to the Plugin Hub's 48×72 icon size. The artwork is Jagex game art, © Jagex Ltd.

## Inspiration and compatibility

These projects shaped how the plugin looks and behaves. No code or media from them is bundled here.

- **[Quest Helper](https://github.com/Zoinkwiz/quest-helper)** (Zoinkwiz and contributors): the model for the whole
  guide, showing what to do now, what comes next and where to go while every click stays yours. Its NPC step
  highlighting was the precedent for marking the prep mobs to defeat.
- **[Gauntlet Crafting](https://github.com/trs/runelite-gauntlet-crafting)** (trs, on the RuneLite Plugin Hub): the
  idea for the singing bowl menu states, teal to craft now, yellow for wanted but not makeable yet and gray for not
  needed.
- **RuneLite's Ground Items and Ground Markers plugins**: helper item names sit on the same lines Ground Items
  draws, and the Hunllef room tile markers follow Ground Markers' look and instance tile mapping, so both line up
  with those plugins when you run them together. The plugin reads Ground Items' settings and never changes them.
- **[Old School RuneScape Wiki](https://oldschool.runescape.wiki/)**: crafting costs, resource counts and drop
  facts used by the planner.

Gauntlet Helper is not affiliated with or endorsed by Jagex, RuneLite or the authors above. Old School RuneScape is
a trademark of Jagex Ltd.
