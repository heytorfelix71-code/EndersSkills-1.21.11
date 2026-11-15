# Ender Skills — Fabric 1.21.11

Port of **Ender Skills** (Minecraft 1.12.2, Forge) to **Minecraft 1.21.11 / Fabric**, with **Better Hurt Timer** built in.

Original mod by Arekkuusu (BunnyCinnamon), cybercat5555 and PandizeYT — https://github.com/BunnyCinnamon/EnderSkills (MIT).
Better Hurt Timer by Arekkuusu — https://github.com/BunnyCinnamon/BetterHurtTimer (MIT).
Textures, skill values (`.cfg`) and texts come from the original mod under the MIT license (see `LICENSE`).

## How it works
- Craft a **Break Altar** (book, ender pearl, iron and planks) and right-click it.
- Each class (**Defense**, **Mobility**, **Offense**) has element trees. A tree has a starter skill, two either/or pairs and an ultimate, plus 6 class attributes.
- Skills cost **experience** (the altar can store XP) and your **Ender Level**: your N-th ability needs Ender Level N. **Ender Tokens** raise it (made from Ender Dust, found in Ender Ore between y 0 and 32).
- **Crystal Blocks** within 5 blocks power the altar (3 for the first pair, 11 for the second pair, 32 = full). Drop an **Ender Matrix** on an altar to make it an **Ultimate Altar** (needed for the ultimates).
- Every number is in `config/enderskills/` (one `.cfg` per skill, same format as the original) and `settings.json`.

## Port status
| Phase | Content | Status |
|---|---|---|
| 1 | Altar, tree rules and costs, Ender Level and tokens, XP storage, resets, items/blocks/ore, the 18 class attributes, config files | done (0.1.0) |
| 2 | Ability system (keys Z X C B N M, endurance, cooldowns, HUD), the 12 Mobility abilities, Better Hurt Timer (per-damage-type timers) | done (0.2.0) |
| 3 | The 24 Defense abilities (Light, Earth, Electric, Fire) | done (0.3.0) |
| 4 | The 36 Offense abilities (Void, Blood, Wind, Fire, Light, Black Flame) | done (0.4.0) |
| 5 | Status effects (Stunned, Electrified, Rooted, Bleeding, Black Flame), 4 potions with brewing, the 105 original sounds | done (1.0.0) |

Commands (operators): `/enderskills level <n> [player]`, `/enderskills reset [player]`, `/enderskills reload`.

## 1.1.0
- Config screen (Mod Menu + YACL): ability power, max-level overdrive, power per tree, portal/teleport distances, HUD position and colors, costs and rules.
- Portal works like a portal gun: the two portals stay until you use the ability again; the distance between them grows from 100 blocks to the whole world.
- Power scales with the skill level: balanced early, overwhelming at the top level.
- Speed and Speed Boost no longer stretch the camera FOV; no more blindness/darkness/poison on the screen.

## 1.2.0
- Portals close by themselves after 30 s (level 0) to 60 s (just below the top level); at the top level, or with "Portals never close by themselves", they stay until you use the ability again.
- Sneak + ability key on Portal or Teleport: type X Y Z (`~` = your position).
- Teleport never lands in the air (option).
- Animated status effects (particles on the entity and a colored pulsing frame on your screen).
- Commands: `/enderskills ability|attribute add <players> <skill|all> [level <n|max>]`, `... level <players> <skill|all> <n|max>`, `... remove <players> <skill|all>`, `/enderskills enderlevel|reset|refill|list|reload`.

## 1.3.0
- Ender Book (book + Ender Dust): opens your skills anywhere and the XP storage; unlocking needs an altar (or creative with "everything free").
- New altar/book screen over an animated End sky: classes and trees on the left, glowing tree, details with real stats (current → next level), power multiplier and costs.
- New HUD: endurance bar and a row of ability cells with cooldown sweep and key letters.
- Sound settings: master volume, extra Minecraft sounds on/off, volume per ability; effects density.
- Prettier effects: glowing halos, rings, shock waves and forked lightning (bright particles bloom with shader packs).
- Option: in creative, everything is free (off by default).

## 1.4.0
- HUD styles: Modern, List (1.0-1.2) and Original (1.12.2 blue boss bar); HUD size, background opacity, show movement abilities.
- Skill tree background option: Cosmic (default), Blur or Transparent.
- Clear descriptions for every setting.

## 1.5.0
- HUD ability groups: one group per tree (e.g. "Mobility: Void") shown above the hearts/food/XP; Left/Right arrows switch group; the ability keys use the current group. Movement abilities hidden by default.
- Status effects option: Original (Minecraft's blindness, darkness, poison, slowness...), Both, Current (the mod's own, default).
- Portals appear at once, are easier to enter, and you go through only when you step in (no bouncing back).
- Teleport: original Ender Skills sound; option "never into the sky" (on by default).

## 1.6.0
- Wall and Dome rebuilt: made of the ground's own blocks, small blocks in the way (slabs, carpets, grass...) are put back afterwards, broken wall blocks drop nothing, everything is restored when the server stops.
- Wall has side walls (1-2 blocks) folded towards you; Dome can have a roof.
- From level 40 (configurable) both switch on/off with the ability key.
- New settings category "Wall & Dome": width, height, thickness, side walls, distance, dome radius/height/roof, ground copy, fallback block.
