# 实时工作台 / Real-time CraftingTable

一个基于 **Fabric** 的 **Minecraft 1.21.11** 模组：让你在合成台上**实时看到**合成过程——把材料放进 3×3 合成格，材料会以 **3D 立体**形式悬浮在对应槽位上方，配方成立时**合成结果**会浮在材料区上方并缓慢旋转。关闭界面后，材料与产物仍留在台面上；下次打开**同一张**工作台即可继续合成。

- **纯客户端 × 服务端配合**：不但单机可用，也支持 **LAN / 独立服务器联机**（详情见下文「联机」）。
- **不新增**任何物品、方块、配方或世界内容，不影响存档与其他模组逻辑。
- 内置 **Mod Menu 配置界面**，无需改文件即可微调外观。

适用版本：**Minecraft 1.21.11**（Fabric Loader ≥ 0.19.3，Fabric API，JDK 21）。

---

## English Overview

**Real-time CraftingTable** is a **Fabric** mod for **Minecraft 1.21.11** that shows a live **3D preview of your crafting result** right above the crafting table. Place ingredients into the 3×3 grid and they float above their slots in 3D; when a recipe matches, the result hovers above the materials and slowly rotates. Close the screen and the materials stay on the table — reopen the same workbench to keep crafting.

- 🌐 **Client + Server**: works in singleplayer, LAN and dedicated servers; materials, facing and kept items sync when both sides have the mod.
- 🧩 **No new content**: adds no items, blocks, recipes or world data — safe for existing worlds and other mods.
- 🎛️ **Fully customizable**: floating style, animations, sizes, spacing, distances — all adjustable via **Mod Menu** + **Cloth Config**.

**Requirements:** Minecraft 1.21.11 · Fabric Loader ≥ 0.19.3 · Fabric API · Java 21+.
**Optional:** [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config) for the config screen.
**Install:** put the jar into `.minecraft/mods/` and launch the game.

## 功能一览

- **3D 悬浮材料**：每种放入合成格的材料以立体模型悬浮在对应槽位正上方，随配方实时更新。
- **结果实时预览**：配方成立时，结果物浮在材料上方、绕竖直轴缓慢旋转；改配方即变。
- **关闭后保留**：可选的「工作台作为容器」模式——关界面不退还材料，材料与产物持久保存在台面上，下次打开同一位置继续（默认关闭，可在配置中开启）。
- **多档外观微调**：材料悬浮 / 平摊两种风格；波浪 / 同步 / 不浮动三种动画；朝向跟随玩家或不跟随；生长动画开关；各尺寸、间距、幅度、速度、旋转时长均可调；可以适配各种材质包
- **性能友好**：闲置工作台每帧零成本；超出渲染距离自动跳过；跨帧复用渲染状态，适配整合包。

## 联机

- **单机 / LAN / 独立服务器**均可使用。
- 服务端与客户端都安装时：材料的**保留状态**、**工作台朝向**、**关闭后保留的材料**会在客户端之间正确同步；后加入的玩家也能看到已保留的预览。
- 仅客户端安装（连非模组服务器）时：预览渲染仍可用，但「保留材料」「方向同步」等依赖服务端的功能不生效。
- 独立服务器的大规模联机场景尚未经过大规模实测，请按需自行评估。

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（≥ 0.19.3）与 [Fabric API](https://modrinth.com/mod/fabric-api)（兼容 1.21.11）。
2. 下载本模组 jar，放入 `.minecraft/mods/`（或 PCL2 / 你所用启动器的 mods 目录）。
3. （可选）安装 [Mod Menu](https://modrinth.com/mod/modmenu) 以使用图形化配置界面。
4. 启动 Minecraft。

## 配置

配置文件位于 `config/real-time-crafting-table.json`，首次运行自动生成。推荐通过 **Mod Menu → 模组设置 → 实时工作台** 图形化调整。

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关，关闭后不渲染任何预览 |
| `ingredientStyle` | `FLOAT` | 材料可视化：`FLOAT` 悬浮 / `FLAT` 平摊 |
| `floatingMode` | `WAVE` | `NONE` 不浮动 / `WAVE` 逐槽波浪 / `SYNC` 同步起伏 |
| `facingMode` | `FIXED` | 朝向：`FIXED` 固定开桌方向 / `FOLLOW_PLAYER` 跟随玩家 |
| `facingAnimationSeconds` | `0.6` | 跟随玩家时跨扇形转场时长（秒） |
| `rotationSeconds` | `8` | 结果旋转一圈秒数 |
| `floatHeight` | `0.09` | 材料基座高出台面距离（格） |
| `materialScale` / `resultScale` | `0.24` / `1.0` | 材料 / 结果缩放 |
| `slotSpacing` | `0.187` | 材料水平间距（格） |
| `floatSeconds` / `resultFloatSeconds` | `3` / `4` | 材料 / 结果浮动一个来回的秒数 |
| `floatAmplitude` / `resultFloatAmplitude` | `0.004` / `0.03` | 材料 / 结果浮动幅度 |
| `resultHeightGap` | `0.22` | 结果与材料基座垂直间距（格） |
| `growthEnabled` / `growthSeconds` | `true` / `0.3` | 出现时的生长动画开关 / 时长 |
| `renderDistance` | `64` | 预览渲染距离（格），`0` = 不限 |
| `keepItemsWhenClosed` | `false` | 「工作台作为容器」：关闭后保留材料到同一工作台 |


## 构建

需要 JDK 21。

```bash
# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

构建产物位于 `build/libs/`，形如 `Real-time CraftingTable-1.0.0.jar`。

## 常见问题

- **装了 Mod Menu 但配置界面没出现**：需要同时安装 [Cloth Config](https://modrinth.com/mod/cloth-config)（自动随 Mod Menu 下拉）

## 协议

本项目采用 **MIT License**——可自由使用、修改、分发与商用，需保留版权与许可声明（可再分发出其它协议的开源或闭源版本）。

## 致谢 / Acknowledgements

- **Visual Workbench**（[Modrinth](https://modrinth.com/mod/visual-workbench)，MPL-2.0）：本模组的灵感受其启发，代码为独立重写，无代码复用。

## 显示名本地化

- Mod Menu / 模组列表里的显示名随游戏语言切换
