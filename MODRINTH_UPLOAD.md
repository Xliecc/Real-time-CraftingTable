# Modrinth 上传资料包（v1.0.0）

> 供用户登录 Modrinth 后手动上传，或提供 API token 后代为上传（本会话不做自动上传）。
> 上传 jar = `build/libs/Real-time CraftingTable-1.0.0.jar`（100371 字节）。

---

## 1. 基本信息

| 字段 | 值 |
|---|---|
| 项目标题（Name） | 实时工作台 / Real-time CraftingTable |
| 项目 ID / Slug（上传后系统生成/建议） | `real-time-crafting-table`（全小写连字符，与 mod id 一致，合规） |
| 作者 | Xliecc |
| 版本号 | 1.0.0 |
| 协议（License） | MIT |
| 加载器（Loaders） | Fabric |
| 支持游戏版本（Game versions） | 1.21.11 |
| 环境（Side） | **Client + Server 均可**（`environment: "*"`；视觉为客户端，服务端无感） |

---

## 2. 简介 / 描述（Description）

### 2.1 短简介（Short summary，项目页一行）
> 在合成台上方以 3D 形式实时预览合成结果，拥有丝滑动画与大量自定义配置。
> A real-time 3D preview of your crafting result above the workbench, with smooth animations and lots of customization.

### 2.2 项目描述（Markdown，放完整 description 区）

````markdown
# 实时工作台 / Real-time CraftingTable

在**合成台（工作台）上方**以 3D 形式**实时预览**你的合成结果：材料悬浮在台面上方，合成结果会随配方在台面正上方显示，并带有**丝滑的生长/浮动/旋转动画**。

A real-time **3D preview** of your crafting result floating **above the workbench**: materials hover over the tabletop and the crafted result appears right above, with **smooth grow, float and rotation animations**.

## 特性 / Features

- ✨ **实时 3D 预览**：合成结果以 3D 形式显示在工作台上方，所见即所得。
- 🎞️ **丝滑动画**：材料/结果出现时的生长动画、浮动动画、转场动画均可调节。
- 🎛️ **大量自定义配置**：浮动类型、可视化方式、朝向模式、渲染距离、大小/高度/速度/幅度等（经 Mod Menu + Cloth Config 配置）。
- 🌏 **中英双语**界面与简介。

## 安装 / Installation

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（>= 0.19.3）。
2. 安装 [Fabric API](https://modrinth.com/mod/fabric-api)。
3. 安装本模组（依赖见下）。
4. 强烈建议安装 [Mod Menu](https://modrinth.com/mod/modmenu) 与 [Cloth Config](https://modrinth.com/mod/cloth-config) 以获得配置界面。

## 依赖 / Dependencies

| 依赖 | 类型 | 说明 |
|---|---|---|
| [Fabric API](https://modrinth.com/mod/fabric-api) | 必需（`depends`） | 运行必需 |
| [Mod Menu](https://modrinth.com/mod/modmenu) | 可选（强烈建议） | 配置入口 |
| [Cloth Config](https://modrinth.com/mod/cloth-config) | 可选（强烈建议） | 配置界面；缺失时模组仍可运行，但无配置界面 |

> Java 21+，Minecraft 1.21.11，Fabric Loader >= 0.19.3。

## 许可 / License

[MIT](LICENSE)。感谢 **VisualWorkbench**（MPL-2.0，作者 LudoCrypt）提供功能参照；本项目与其**无代码复用**。
````

---

## 3. 版本信息（Version）

| 字段 | 值 |
|---|---|
| 版本号 | 1.0.0 |
| 加载器 | Fabric |
| 游戏版本 | 1.21.11 |
| 上传文件 | `Real-time CraftingTable-1.0.0.jar`（100371 字节） |
| 版本类型（Release type） | Release（正式版） |
| 主要版本（featured） | 是 |

---

## 4. 分类 / 标签（Categories / Tags）

### 4.1 分类（Categories，最多选 5 个）
- **utility**（实用）
- **cosmetic**（装饰/外观）

> 备选/次要：`decoration`（摆放/装饰）——如需更贴合「改进工作台外观」可选；建议保留 utility + cosmetic 即可。

### 4.2 加载器 Tags
- **Fabric**

### 4.3 环境
- 下载标签可标「Client」优先（本模组视觉在客户端），但因 `environment: "*"` 服务端也能装，建议 **Client + Server** 都打上。

---

## 5. 依赖清单（依赖关系，供 Modrinth 版本页填写）

| 依赖 | 版本匹配 | 关系 |
|---|---|---|
| `fabric-api` | 任意（`*`） | **required**（必需） |
| `minecraft` | `~1.21.11`（1.21.11） | required |
| `fabricloader` | `>=0.19.3` | required |
| `java` | `>=21` | required（非 Modrinth 依赖项，仅说明） |
| `cloth-config` | 任意 | 可选（建议 optional，缺失仍可运行） |
| `modmenu` | 任意 | 可选（建议 optional，配置入口） |

> ⚠️ 说明：`fabric.mod.json` 的 `depends` 只硬性声明了 `fabric-api / minecraft / fabricloader / java`；**cloth-config** 与 **modmenu** 未写进 depends（避免强制依赖、允许观战/直装），在 Modrinth 上按「可选」登记即可。若用户希望缺失 Cloth Config 时也能直接跳下载提示，可后续把 `cloth-config` 加进 depends（但需确认代码在缺失时仍能正常 fallback，见 `rtct.config.missingTitle`）。

---

## 6. 待办提醒

- [x] **homepage / contact 已补**：GitHub 仓库已建（https://github.com/Xliecc/real-time-crafting-table），`fabric.mod.json` 的 `contact`（homepage + sources）已加回并随 jar 构建。
- [ ] 上传成功后把 Modrinth 项目链接回填到 README。
