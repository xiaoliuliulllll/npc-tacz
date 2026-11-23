# npc-tacz

An independently developed Minecraft 1.20.1 Forge NPC mod that uses **TACZ (Timeless and Classics Guns: Zero)** as a required runtime dependency.

**This is not an official TACZ project.** The main mod does not bundle TACZ code, models, textures, sounds, or gun pack data.

[中文说明](README.md) | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/npc-tacz)

## Requirements

- Minecraft 1.20.1
- Forge 47.x
- **TACZ 1.2.x** (required)
- Java 17

## Features

- Five NPC types: Soldier, Bandit, Rebel, Mercenary, Bounty Hunter
- TACZ guns, attachments, reloading, ballistics, tracers and recoil
- Faction hostility, nearest-target selection, armed-player priority and instant retaliation
- Threat retreat, cover, ally regrouping and backup calls when outnumbered
- Random lost-sight tactics: suppression / hold / push
- Creeper avoidance, melee finishers and retreat-fire accuracy penalty
- Alt+N 8-page admin screen with quick switches, fixed page tabs and autosave
- Four damage multipliers, NPC glow toggle and glass/leaf occlusion toggle
- Loot barrels with configurable lifetime, gun-type extra ammo and sniper 48-round cap
- Diamond ammo box drops with one-click / inventory-drag matching ammo deposit
- Xaero minimap radar icons for NPCs (blue dots)
- 9-slot pickup inventory, death drops, headshot bonus damage, stationary shooting, sniper downtime
- Long-range visibility, NPC culling, chat notification toggle, item cleanup and nearby ammo refill

## Build

1. Download the TACZ 1.2.0 Forge 1.20.1 jar.
2. Rename it to `tacz.jar` and place it at:

   ```text
   mod/libs/tacz.jar
   ```

3. Install JDK 17 and Gradle 8.8.
4. Build:

   ```bash
   cd mod
   gradle build
   ```

Output:

```text
mod/build/libs/npc-tacz-1.1.4.jar
```

See [BUILDING.md](BUILDING.md) for details.

> `mod/libs/tacz.jar` is a local compile-time dependency only. Do not commit it or redistribute it.

## License

**GPL-3.0-or-later**. See [LICENSE](LICENSE).

Third-party notices and asset credits are in [CREDITS.md](CREDITS.md).

## Assets

The modern soldier model and six NPC textures were created by **尺子老梅** and are distributed with this project under GPL-3.0-or-later.

## Not included

- TACZ jar or TACZ assets
- YSM (Yes Steve Model) code or assets
- Any unauthorized third-party models, textures or sounds

## Release

Only the main mod jar is published. TACZ is declared as a required dependency.

Before publishing, read [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).
