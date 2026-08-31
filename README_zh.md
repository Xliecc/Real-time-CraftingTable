# 实时工作台（Real-time CraftingTable）

<img src="src/main/resources/assets/real-time-crafting-table/icon.png" alt="实时工作台" width="128"/>

**工作台 3D 实时合成预览——材料悬浮在槽位上方，配方成立时结果浮于材料之上并缓慢旋转，并且它们都有丝滑的动画效果**

![Real-time CraftingTable](https://cdn-alt.modrinth.com/data/UPOoH4hE/images/b512228a14d0567c270a26cb015f44de71fb28fe.gif)

> 为原版工作台添加 **3D 实时预览** 的 Fabric 模组。客户端单独装即可用——连 vanilla 服务器也行。作者：Xliecc　｜　许可：MIT

📖 **[English README](README.md)**

## ✨ 功能
- **3D 实时预览**——材料悬浮在对应槽位上方；配方成立时结果浮起并缓慢旋转
- **关闭后保留**（可开关）——把工作台当容器用，状态持久化并在联机中同步
- **大量自定义**——悬浮风格、动画、朝向、大小、速度等，Mod Menu 图形化调节
- **性能友好**——闲置工作台零成本，跨帧复用渲染状态
- **LAN / 独立服务器联机**——双方都装时在客户端间同步

## 📦 依赖
- [Fabric API](https://modrinth.com/mod/fabric-api)
- 可选：[Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config)（配置界面；不装也能正常运行）

## ✅ 支持版本
- **Minecraft Java Edition 26.2**（Fabric Loader ≥ 0.19.3、Fabric API ≥ 0.158.0+26.2、Java 25）
- 旧版 Minecraft 1.21.11 构建在 `1.21.11` 分支持续维护

## ⚙️ 配置
- Mod Menu → 本模组 → 配置页，修改即时生效
- 或直接编辑 `config/real-time-crafting-table.json`（首次运行自动生成）
- **⚠️ `keep-items-when-closed`（关闭时保留物品）默认关闭** — 开启后关闭界面物品留在工作台上。这是最需要注意的设置

## ❓ 常见问题
- **预览没显示？** 确认工作台在视野内、客户端已安装模组
- **装了 Mod Menu 但没有配置界面？** 需再装 Cloth Config
- **关闭工作台后物品没有得到保留？** 请确在配置中开启了【关闭工作台后保留物品】的选项，如果联机两个人配置不同的情况下同时打开了工作台的 GUI，这将会跟随后者的配置（两者配置不会互相干扰）
- **为什么我重新打开GUI，物品的方向不会跟着我旋转** 如果是联机模式的话，旋转会跟随上一名操作者，只有你对工作台进行了真实操作（例如把工作台中一个物品提起再放下）的时候旋转才会跟随你

## 🙏 致谢
- **Visual Workbench**（[Modrinth](https://modrinth.com/mod/visual-workbench)，MPL-2.0）：想法受其启发；代码为独立重写

## 📜 许可
[MIT](LICENSE) © 2026 Xliecc
