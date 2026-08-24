# Real-time CraftingTable

> A Fabric mod that adds a real-time 3D preview of your crafting result above the vanilla Crafting Table.
> Author: Xliecc　｜　License: MIT

📖 **[中文说明](README_zh.md)**

## ✨ Features
- **Real-time 3D preview**: Open a 3×3 Crafting Table and place ingredients — each one floats above its slot in 3D. When a recipe matches, the result hovers above the materials and slowly rotates. Change the recipe and the preview updates instantly.
- **Keep items after closing (optional)**: Keeps the ingredients at the table when the GUI closes, like using the workbench as a container — reopen the same table to view or keep crafting. Persisted state survives and syncs in multiplayer.
- **Highly customizable**: Floating / flat material styles, wave / sync / none floating animations, facing follows the player or stays fixed, pop-in growth animation, render distance, sizes, spacing, heights, speeds and amplitudes — all adjustable (compatible with various resource packs).
- **Performance-friendly**: Idle workbenches cost zero per frame; previews beyond the render distance are skipped automatically; render state is reused across frames, integration-pack friendly.
- **LAN / dedicated server support**: Works in singleplayer, LAN and dedicated servers; material state, table facing and kept items sync between clients when both sides have the mod.
- **Mod Menu config screen**: All animation parameters can be adjusted graphically via Mod Menu + Cloth Config, applied immediately without restart.

Adds no new items, blocks, recipes or world data — purely a visual enhancement (client + server co-op).

## 📦 Dependencies
- Fabric API
- Optional: Mod Menu + Cloth Config (provide the config screen; the mod runs fine without them)

## Installation
Put `build/libs/Real-time CraftingTable-1.0.0.jar` into `.minecraft/mods/`.

> Tip: With Mod Menu + Cloth Config installed, the graphical config screen opens from Mod Menu (optional; the mod runs fine without them).

## ⚙️ Configuration
- Open Mod Menu → this mod → config screen for graphical editing.
- Or edit `config/real-time-crafting-table.json` directly (auto-generated on first launch).
- Key adjustable values: enable toggle, floating type, visual style, facing mode, render distance, item/result sizes, heights, spacing, speeds, amplitudes, growth animation, keep-items-when-closed, etc.
- All config texts are localized (中文/EN), following the game language.

## Multiplayer
- Singleplayer / LAN / dedicated servers all supported.
- When both server and clients have the mod: kept materials, table facing and stored state sync correctly between clients; players joining later still see the kept preview.
- Client-only install (connecting to a vanilla server): the preview still renders, but server-reliant features (kept items, facing sync) are unavailable.
- Large-scale dedicated-server scenarios have not been heavily tested — evaluate on your own.

## Building
Requires JDK 21.

```bash
# Windows
gradlew.bat build
# macOS / Linux
./gradlew build
```

Artifacts are produced at `build/libs/Real-time CraftingTable-<version>.jar` (a `-sources.jar` source bundle is also produced for optional redistribution).

## FAQ
- **Preview not showing**: Make sure you open a 3×3 Workbench crafting screen (not the 2×2 inventory one), keep the workbench in view, and check `enabled` and `renderDistance`.
- **Config screen missing with Mod Menu installed**: Install [Cloth Config](https://modrinth.com/mod/cloth-config) as well (it is pulled in automatically by Mod Menu).
- **Keep-items-when-closed not working**: That option lives in the server-side config — the integrated server (host) config in singleplayer, or the server files on a dedicated server.

## 📜 License
[MIT](LICENSE) © 2026 Xliecc

## Acknowledgements
- **Visual Workbench** ([Modrinth](https://modrinth.com/mod/visual-workbench), MPL-2.0): this mod's interaction concept (floating crafting materials above the workbench + facing the player + floating rotating result) was inspired by it. The code is an independent rewrite with no code reuse.
