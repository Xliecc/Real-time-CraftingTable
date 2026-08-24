# 实时工作台（Real-time CraftingTable）

<img src="src/main/resources/assets/real-time-crafting-table/icon.png" alt="实时工作台" width="128"/>

**工作台 3D 实时合成预览——材料悬浮在槽位上方，配方成立时结果浮于材料之上并缓慢旋转。**

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

## ⚙️ 配置
- Mod Menu → 本模组 → 配置页，修改即时生效
- 或直接编辑 `config/real-time-crafting-table.json`（首次运行自动生成）
- **⚠️ 「关闭后保留」默认关闭**——想保留材料需在配置里开启

## ❓ 常见问题
- **预览没显示？** 确认工作台在视野内、客户端已安装模组
- **装了 Mod Menu 但没有配置界面？** 需再装 Cloth Config
- **保留没生效？** 每个玩家用自己的配置决定关桌是否保留（LAN 中按各自配置，不是房主）；在需要保留的那台客户端上开启开关即可

## 🙏 致谢
- **Visual Workbench**（[Modrinth](https://modrinth.com/mod/visual-workbench)，MPL-2.0）：交互理念受其启发；代码为独立重写

## 📜 许可
[MIT](LICENSE) © 2026 Xliecc
