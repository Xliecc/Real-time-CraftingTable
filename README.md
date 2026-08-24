# Real-time CraftingTable

<img src="src/main/resources/assets/real-time-crafting-table/icon.png" alt="Real-time CraftingTable" width="128"/>

**A real-time 3D preview of your crafting result — materials float above the workbench, and the result hovers and slowly rotates above them.**

> A Fabric mod that adds a **real-time 3D preview** to the vanilla Crafting Table. Install on your client and it works — even on vanilla servers. *(Client and server are independent: either side may be installed alone.)*

📖 **[中文说明](README_zh.md)**

## ✨ Features
- **Real-time 3D preview** — materials float above their slots; the result hovers and rotates when a recipe matches
- **Keep items after closing** (optional) — use the workbench like a container; state persists and syncs in multiplayer
- **Highly customizable** — floating style, animations, facing, sizes, speeds and more, all via Mod Menu
- **Performance friendly** — idle workbenches cost nothing; render state reused across frames
- **LAN / dedicated server support** — synced between clients when both sides have the mod

## 📦 Dependencies
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config) (config screen; works fine without them)

## ⚙️ Configuration
- Mod Menu → this mod → config screen; all changes apply immediately
- Or edit `config/real-time-crafting-table.json` (auto-generated on first run)
- **⚠️ `keep-items-when-closed` is OFF by default** — enable it to keep materials in the table after closing the GUI

## ❓ FAQ
- **Preview not showing?** Make sure the workbench is in view and the mod is installed on your client
- **No config screen with Mod Menu?** You also need Cloth Config
- **Keep not working?** That option lives in the server-side config — the host config in singleplayer, or the server files on a dedicated server

## 🙏 Acknowledgements
- **Visual Workbench** ([Modrinth](https://modrinth.com/mod/visual-workbench), MPL-2.0): interaction concept inspired by it; code is an independent rewrite

## 📜 License
[MIT](LICENSE) © 2026 Xliecc
