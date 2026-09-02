package com.example.client.crafting;

import com.example.TemplateMod;
import com.example.config.PreviewConfig;
import com.example.crafting.CraftingGridStorage;
import com.example.crafting.OpenTableTracker;
import com.example.crafting.TableFacing;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原版工作台（Crafting Table）的可视化合成预览渲染器。
 *
 * <p>工作原理（纯客户端，不修改任何原版逻辑、不新增物品/方块/配方）：
 * <ol>
 *   <li>监听 {@link WorldRenderEvents#AFTER_ENTITIES}；</li>
 *   <li>保留数据（配置「关闭工作台时保留合成材料」开启时）：遍历当前维度所有非空保留记录，
 *       <b>始终渲染</b>——不依赖玩家视线，看向与否都显示（见 {@link CraftingGridStorage#peekAll}）；</li>
 *   <li>实时 GUI：{@link CraftingScreen}（3×3 工作台界面）打开时，无论保留开关与否，都在
 *       正在使用的工作台上方渲染 {@link CraftingMenu} 实时槽位（slot 1~9 输入、slot 0 结果）；</li>
 *   <li>在工作台上方以 3D 形式渲染每个合成材料（悬浮于对应格子位置），
 *       若结果非空，则在合成区上方中央渲染最终结果，并绕竖直轴缓慢旋转。</li>
 *   <li>面板朝向两档语义（互斥）：
 *     - <b>跟随玩家旋转</b>（FOLLOW_PLAYER，keep 开启时）：纯客户端效果——面板实时跟随自己
 *       的方位旋转，各客户端只看自己的、相互/服务端都不同步；
 *     - <b>不随玩家旋转</b>（FIXED）或 keep 关闭：双方同步——所有客户端统一朝向「上一个操作
 *       工作台的人」的开桌方位（服务端开桌时广播，开桌才切换、之后锁定不动）。
 *     方向源变化时跨扇形平滑转场。</li>
 * </ol>
 *
 * <p>物品渲染采用 Minecraft 1.21.11 的 ItemModel 系统：
 * {@link ItemModelManager#updateForNonLiving} 根据物品栈构建 {@link ItemStackRenderState}，
 * 再通过 {@link ItemStackRenderState#render} 提交到世界渲染的命令队列（{@link SubmitNodeCollector}）。
 */
public final class CraftingPreviewRenderer {

	/** CraftingMenu 槽位布局：槽 0 为合成结果，槽 1~9 为 3×3 合成网格。 */
	private static final int RESULT_SLOT = 0;
	private static final int GRID_START_SLOT = 1;
	private static final int GRID_SLOT_COUNT = 9;

	// ------------------------------------------------------------------
	// 布局常量（世界坐标，单位：格）
	// ------------------------------------------------------------------
	// 物品大小、材料间距、浮动幅度等可由 Mod Menu 配置页调整（PreviewConfig），
	// 此处仅保留与尺寸无关的固定关系常量。

	/** 工作台顶面的世界 Y 坐标（方块底部为 y，顶面在 y+1）。 */
	private static final double TABLE_TOP_Y = 1.0D;

	/**
	 * 方块类材料额外下移的高度（格）。方块类物品缩放后高约 0.1 格、视觉占位比扁平类大，
	 * 若与扁平类共用同一基座高度会显得浮得偏高；单独下移一点点（0.01 格）使其相对自身尺寸更贴近桌面。
	 */
	private static final double BLOCK_ITEM_LOWER_OFFSET = 0.01D;

	/**
	 * 扁平精灵的额外缩小系数：其 FIXED 显示缩放为 1.0，而 3D 方块类为 0.5。05:54 起尺寸分类改为
	 * 几何判定（体厚 > 1/16 格），扁平精灵（含门/告示牌/按钮等 BlockItem）统一乘本系数——
	 * 取 0.5 使两者外层缩放相抵后最终尺寸与方块类一致（1.0×0.5 == 0.5×1.0）；
	 * 实机微调链：0.5 → 0.6 → 0.7 → 用户最终定档 **0.6**（门约 0.36 格、略大于方块）。
	 */
	private static final float FLAT_ITEM_EXTRA_SCALE = 0.6F;

	/**
	 * 非方块（扁平）合成结果的额外放大系数：方块类结果（体厚 > 1/16 格）保持
	 * {@code resultScale} 原样；扁平物品结果（刀/锭/药水等）额外乘本系数「放大一点」，
	 * 使其在台上更醒目（2026-08-20 用户需求）。材料仍统一走 {@link #FLAT_ITEM_EXTRA_SCALE}，
	 * 不受此项影响。
	 */
	private static final float RESULT_FLAT_BONUS_SCALE = 1.15F;

	/**
	 * 非方块（扁平）合成结果的额外抬高高度（格）：方块类结果仍用 {@code resultBase}
	 * （= 材料基座 + resultHeightGap）；扁平结果在此基础上额外抬高本系数「高一点」
	 * （2026-08-20 用户需求）。材料不受影响。
	 */
	private static final double RESULT_FLAT_HEIGHT_RAISE = 0.08D;

	/** 波浪模式下逐槽位错开的相位步长（弧度）。 */
	private static final double WAVE_PHASE_STEP = 0.7D;

	/** 面板朝向的扇形量化宽度（度）。90° = 4 个正方向，面板只在 0/90/180/270 停靠。 */
	private static final int FACING_SECTOR_DEGREES = 90;


	/**
	 * 平滑朝向状态：每个工作台面板当前的偏航角。跟随玩家模式逐帧跟转；方向锁定（不随玩家
	 * 旋转）模式也用它跨开桌保存旧朝向，使「换个方向再开桌」能播放 A→B 的旋转动画（否则
	 * 每次开桌都被重置、直接吸附到新方向，本地玩家永远看不到转场——只有联机对方能看到）。
	 * {@link #FACING_TOUCHED} 记录本帧实际渲染过的位置（仅供清空，不据此删状态）。
	 */
	private static final Map<BlockPos, FacingState> FACING_STATE = new HashMap<>();
	private static final Set<BlockPos> FACING_TOUCHED = new HashSet<>();

	/**
	 * 单个工作台的朝向动画状态。面板只在玩家跨入新的 90° 扇形时启动一次动画
	 * （时长由配置「朝向转场用时」决定，默认 0.5 秒，从 {@link #animStartDeg} 沿最短弧转到
	 * {@link #animTargetDeg}）；同一扇形内玩家走动不再触发，面板保持停靠——不逐帧实时跟转。
	 */
	private static final class FacingState {
		/** 当前显示的偏航角（度）。 */
		float currentDeg;
		/** 当前目标扇形（0/1/2/3 对应 0/90/180/270°）。 */
		int sector = -1;
		/** 动画起始游戏刻（须与 {@code renderPreview} 的 {@code time} = {@code world.getOverworldClockTime()} 单调时钟同一时钟，
		 * 否则 (time + tickDelta) 相减基线错乱；06:44 起赋值改自 {@code time}）。 */
		long animStartTick;
		/** 动画起始角（度）。 */
		float animStartDeg;
		/** 动画目标角（度，扇形基准角）。 */
		float animTargetDeg;

		FacingState() {
		}
	}

	// ------------------------------------------------------------------
	// 生长动画状态（材料/结果出现时从零放大到配置尺寸的弹出动画）
	// ------------------------------------------------------------------
	/** 结果槽在生长状态里的槽位下标（材料为 0~8，结果独占 9，互不冲突）。 */
	private static final int RESULT_GROWTH_INDEX = 9;

	/**
	 * 「瞬时变空」宽限窗（游戏刻）：槽位被读为空后，若在窗口内同一物品又回来，则视为
	 * 瞬时时序抖动（而非真正的拿走）——取消退场、不重放入场，消除「结果缩一下又弹回」的
	 * 闪烁。典型来源：提取合成结果时，原版先把结果槽清空、随后在下一两个 tick 才重算回同一
	 * 结果（表现层 GUI 槽位与配方重算之间存在时序差）。窗口取 3 刻（0.15s）：既覆盖常见的
	 * 1~2 刻瞬时空窗，又不足以误吞「移除后过久再次放置同一物品」的真实操作。
	 */
	private static final float TRANSIENT_GRACE_TICKS = 3.0F;

	/** 生长动画状态键：按 (工作台位置, 槽位) 记录。 */
	private record GrowthKey(BlockPos pos, int index) {
	}

	/**
	 * 生长/退场动画状态：每个 (工作台, 槽位) 记录当前物品与两个动画的起始刻。
	 * <ul>
	 *   <li>进入（出现时从 0 放大到 1）：首次放入、换成不同物品时启动；</li>
	 *   <li>退场（拿走时从 1 缩小到 0）：槽位清空、或被不同物品替换时启动，动画期间继续渲染
	 *       旧物品直到消失。</li>
	 * </ul>
	 * 同一物品持续渲染不重放；动画结束后自动清理。
	 */
	private static final class GrowthState {
		/** 进入动画起始游戏刻（world.getOverworldClockTime() 单调时钟）。 */
		long enterTick;
		/** 当前槽位物品（用于检测变化；空槽为 null）。 */
		ItemStack current;
		/** 退场动画起始游戏刻。 */
		long exitTick;
		/** 正在退场的物品（非 null 时渲染缩小中的旧物品）。 */
		ItemStack exiting;
	}

	/**
	 * 单槽动画结果：进入缩放系数（0→1）+ 退场物品（可为空）+ 退场缩放系数（1→0）
	 * + 退场冻结的浮动偏移（格）。
	 *
	 * <p>{@code exitBob}：退场开始那一 tick 的浮动相位算出的 bob 偏移（不是当前帧的
	 * 实时 bob）——退场中的物品<b>冻结在它被拿走时所在的高度</b>原地缩小，而不是瞬移到
	 * 基座高度再退场（实机反馈：结果退场时从浮动位置跳回中间）。
	 */
	private record SlotAnim(float enterFactor, ItemStack exiting, float exitFactor, double exitBob) {
	}

	private static final Map<GrowthKey, GrowthState> GROWTH_STATE = new HashMap<>();

	/** GUI 关闭检测：记录每个 GUI 位置最后一次看到的网格内容（副本，防槽位栈原地清空）。
	 * {@code hadContent}：本次 GUI 会话是否出现过可见内容——决定「空网格兜底接管」是否启用
	 * （见 {@code appendPreviewStates}：从未放过任何内容才允许用存储记录接管；操作过之后
	 * 网格空就按真空渲染，避免把存储里的旧结果/旧材料错误显示出来）。 */
	private record LastGui(List<ItemStack> grid, ItemStack result, boolean hadContent) {
	}

	private static final Map<BlockPos, LastGui> LAST_GUI = new HashMap<>();

	/**
	 * 同步驱动的过渡动画：每帧看到的存储缓存内容快照。
	 * <b>性能优化 P0-3</b>：grid/result 存<b>引用</b>（不再每帧深拷贝）；cache 内
	 * GridData 每次写入都是整体替换的新对象（normalize 深拷贝，见 CraftingGridStorage），
	 * 因此用 {@code data} 对象身份（==）即可判断内容是否变化——无变化位置零拷贝，
	 * 只有真正变化/移除的位置才启动退场（退场时再 copy 进 GrowthState.exiting）。
	 */
	private record GridSnapshot(boolean present, List<ItemStack> grid, ItemStack result,
			CraftingGridStorage.GridData data) {
	}

	private static final Map<BlockPos, GridSnapshot> LAST_STORE = new HashMap<>();

	/** LAST_STORE 对应的维度键（维度切换时清空，防跨维度误判）。 */
	private static String lastStoreDimKey = "";

	/** 双 pass 守卫（P1-2）：同一 tick（world.getOverworldClockTime()）内只推进一次动画状态——
	 * 主视图与阴影 pass 都会调 appendPreviewStates，第二次进入跳过 syncTransitions/
	 * advanceGrowth（time 幂等，重复推进无视觉差异但 CPU 翻倍）。 */
	private static long lastAdvancedTick = -1;

	/**
	 * 退场存在性快照（性能优化：hasActiveExit 从 O(全表) 降为 O(1)）。
	 * 每帧首次查询时按 {@code lastActivesSnapshotTick != time} 重建一次：扫描 GROWTH_STATE
	 * 得到「有正在退场物品」的位置集合。同一 tick 内主/影两 pass 共享同一快照（time 幂等），
	 * render 阶段（renderPreviewGeometry）查询上一帧构建的集合即可——无需每次 O(N) 全表扫。
	 * 无回归：仅缓存「某位置是否存在退场」，不触碰任何状态维护逻辑。
	 */
	private static long lastActivesSnapshotTick = -1;
	private static final Set<BlockPos> ACTIVE_EXIT_SNAPSHOT = new HashSet<>();

	/** 走进/走出距离动画（P2-1b，用户需求）：上一帧「在渲染距离内且有可见内容」的工作台位置。
	 * 玩家走进 → 该位置从 {@code PREV_IN_RANGE} 缺失 → 重播生长；玩家走出 → 位置脱离快照
	 * 由 syncTransitions 自动启动退场，残影由收尾 pass 渲染。每帧 clear+rebuild。 */
	private static final Set<BlockPos> PREV_IN_RANGE = new HashSet<>();

	// —— 每帧临时集合复用（性能优化 P1-x）——
	// 这三个集合在本帧内构建、本帧内消费、<b>不跨帧存引用</b>（PREV_IN_RANGE 用 addAll 拷贝、
	// LAST_STORE/LAST_GUI 存的是独立新对象），因此可复用同一 static 实例：每帧 clear 后重建，
	// 消除每帧 new HashMap/HashSet 的 GC 抖动。渲染线程单线程访问，无并发顾虑。
	/** 本帧「距离内且有内容」的位置 → 数据（供存储分支遍历，避免重复 peekAll）。 */
	private static final Map<BlockPos, CraftingGridStorage.GridData> FRAME_IN_RANGE_DATA = new HashMap<>();
	/** 本帧「距离内且有内容」的位置集合（含当前 GUI 位置）。 */
	private static final Set<BlockPos> FRAME_IN_RANGE_NOW = new HashSet<>();
	/** 本帧已追加渲染状态的位置（去重，动画收尾 pass 不再重复追加）。 */
	private static final Set<BlockPos> FRAME_APPENDED = new HashSet<>();

	/**
	 * 临时诊断开关（定位「提取结果/材料时整桌预览闪没一帧」）：开启时打印关键判定日志。
	 * 定位完成后应置回 {@code false}（或删除日志）。false 时零开销（所有日志均被短路）。
	 */
	private static final boolean DIAG = false;

	/** 物品渲染状态缓存 key：displayContext + 物品栈（内容相等即共享——模型更新结果只依赖这两者）。 */
	private record ItemStateKey(ItemDisplayContext context, ItemStack stack) {
	}
	/**
	 * 物品模型更新缓存（渲染优化，用户确认实施）：
	 * {@code updateForNonLiving}（模型查询/图层决策/矩阵计算）是每帧渲染 CPU 大头，
	 * 但结果只依赖 (displayContext, 物品栈内容)——台上物品内容未变时每帧重复调用是浪费。
	 * 缓存命中直接复用上一帧构建好的 {@link ItemStackRenderState}（其渲染为只读提交，缩放/旋转
	 * 都在 matrices 上做，不入状态），内容/显示上下文变化时才重新 update。
	 * <p>HashMap + 显式清理：key 是 (displayContext, 槽位 ItemStack <b>引用</b>)，槽位栈可变，
	 * 不适合 WeakHashMap 弱 key（可变对象哈希隐患）。改为强引用 HashMap，靠
	 * {@link #ITEM_STATE_CACHE_MAX} 软上限（超限整表清空，重建代价小）与维度切换
	 * （{@code syncTransitions} 内随 {@link #LAST_STORE} 一并清空）兜底，内存有界。
	 * 仅渲染线程访问（服务端不触碰）。
	 */
	private static final Map<ItemStateKey, ItemStackRenderState> ITEM_STATE_CACHE = new HashMap<>();

	/** {@link #ITEM_STATE_CACHE} 软上限：超过后整表清空重建（防跨物品/跨世界无限增长）。 */
	private static final int ITEM_STATE_CACHE_MAX = 512;

	private CraftingPreviewRenderer() {
	}

	/**
	 * 标准方块实体路径的统一追加入口：由 {@link WorldRendererMixin}（主视图）与
	 * {@link IrisShadowRendererMixin}（阴影 pass）在各自 {@code submitBlockEntities}
	 * 的 HEAD 调用，把当前所有工作台预览的标准方块实体渲染状态
	 * （{@link PreviewRenderState}）追加进 {@code LevelRenderState.blockEntityRenderStates}。
	 *
	 * <p>主视图与阴影 pass 都用标准路径（{@code BlockEntityRenderDispatcher.render}）渲染它们——
	 * 与附魔台/可视化工作台完全一致，因此<b>所有光影在正常视图与阴影下都一致</b>，
	 * 不再有旧方案（AFTER_ENTITIES 旁路 + 阴影裸队列）在 MakeUp/Bliss/Sildur 下的
	 * 错位/半透明/过亮问题。
	 *
	 * <p>幂等：列表已含本帧预览状态（另一 pass 已追加）则跳过；帧切换清理已消失工作台的
	 * 朝向状态（FACING_STATE）。列表由 WorldRenderer/阴影 pass 每帧多次 clear() 重建，
	 * 每次重建后本方法追加一次即可，同帧多 pass 不重复。
	 *
	 * @param worldRenderState 当前渲染的 LevelRenderState（blockEntityRenderStates 即标准
	 *                         BE 渲染列表，追加后由调用方循环统一渲染）
	 */
	public static void appendPreviewStates(net.minecraft.client.renderer.state.level.LevelRenderState worldRenderState) {
		// 幂等：列表已含预览状态（同帧另一 pass 已追加）则跳过，避免重复追加/渲染。
		if (containsPreviewState(worldRenderState)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel world = client.level;
		Entity cameraEntity = client.getCameraEntity();
		if (world == null || cameraEntity == null) {
			return;
		}

		// 物品模型缓存软上限：超过后整表清空重建（防跨物品/跨世界无限增长；重建代价小）。
		// 每帧一次 O(1) 的 size() 检查，可忽略。
		if (ITEM_STATE_CACHE.size() > ITEM_STATE_CACHE_MAX) {
			ITEM_STATE_CACHE.clear();
		}

		// 清理朝向状态中「已被拆掉/换成别方块」的工作台。保留仍存在的（含关桌后没再渲染的）——
		// 这样关桌后换个方向再开桌时，旧朝向还在，能播放 A→B 的旋转动画（而不是每次直接吸附）。
		FACING_TOUCHED.clear();
		FACING_STATE.keySet().removeIf(pos -> world.getBlockState(pos).getBlock() != Blocks.CRAFTING_TABLE);

		// 生长动画状态同样只保留「仍是工作台」的位置（内存有界，与朝向状态同规模）。
		GROWTH_STATE.keySet().removeIf(key -> world.getBlockState(key.pos()).getBlock() != Blocks.CRAFTING_TABLE);

		// LAST_GUI 同理：方块已不是工作台（被拆/替换/换维度后旧坐标残留）的记录不再有
		// 「关闭检测」意义，直接清理，防跨维度/跨世界堆积（历史 bug：换世界后 LAST_GUI
		// 残留旧维度坐标，渲染器每帧多一条无效关闭检测）。
		LAST_GUI.keySet().removeIf(pos -> world.getBlockState(pos).getBlock() != Blocks.CRAFTING_TABLE);

		float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		// 与 render() 同一单调时钟（world.getOverworldClockTime()）：动画相位/朝向转场与正常视图严格一致。
		long time = world.getOverworldClockTime();
		PreviewConfig cfg = PreviewConfig.get();

		// 模组总开关：关闭后阴影 pass 同样跳过。
		if (!cfg.enabled) {
			return;
		}

		// 双 pass 守卫（P1-2）：同一 tick 只推进一次动画状态（主 pass 推进，阴影 pass 复用）。
		// 阴影 pass 仍会走下方「追加渲染状态」路径（它需要自己的渲染状态列表），但跳过
		// syncTransitions/advanceGrowth——time 幂等，推进两次无视觉差异，纯属白跑。
		boolean firstPass = lastAdvancedTick != time;
		if (firstPass) {
			lastAdvancedTick = time;
		}

		// 内容来源与 render() 相同：当前打开的 GUI 实时槽位 + 保留数据（keepItemsWhenClosed）。
		// 先计算 guiPos——syncTransitions 需要它来「跳过当前正打开 GUI 的位置」：
		// GUI 位置的内容变化由下方 GUI 分支实时处理（每帧读实时结果槽，EMPTY 即退场）；
		// 若 syncTransitions 也参与该位置的 diff，会与 GUI 分支叠加、并可能「复活」已消失的
		// 旧结果退场（用户反馈：点掉材料后结果残留/换不可合成物时旧结果反复播退场）。
		BlockPos guiPos = null;
		List<ItemStack> guiGrid = null;
		ItemStack guiResult = ItemStack.EMPTY;
		if (client.gui.screen() instanceof CraftingScreen screen
				&& screen.getMenu() instanceof CraftingMenu handler) {
			BlockPos hit = OpenTableTracker.get();
			if (hit != null && world.getBlockState(hit).getBlock() == Blocks.CRAFTING_TABLE) {
				guiPos = hit;
				guiGrid = new ArrayList<>(GRID_SLOT_COUNT);
				for (int i = 0; i < GRID_SLOT_COUNT; i++) {
					// 深拷贝：GUI 槽位栈是可变引用，玩家提取物品时 vanilla 会原地清空
					// （setCount(0)）这些栈；若 PreviewRenderState 直接持有引用，提取那帧
					// 渲染会读到已清空的栈 → 幻影闪烁/消失（用户反馈：提取仅剩一个材料会闪）。
					// 副本保证渲染看到的是稳定快照，不受槽位栈清空影响。
					ItemStack s = handler.getSlot(GRID_START_SLOT + i).getItem();
					guiGrid.add(s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy());
				}
				ItemStack gr = handler.getSlot(RESULT_SLOT).getItem();
				guiResult = (gr == null || gr.isEmpty()) ? ItemStack.EMPTY : gr.copy();
			}
		}
		// 提取瞬间备用：捕获「上一帧」的 LAST_GUI 内容——供当前帧整桌变空时补建退场状态用。
		// 必须在下方第 370 行覆盖 LAST_GUI 之前取到，否则上帧内容就丢了。
		LastGui prevGui = guiPos != null ? LAST_GUI.get(guiPos) : null;

		if (DIAG && guiPos != null) {
			LastGui prev = LAST_GUI.get(guiPos);
			boolean hadPrev = prev != null && prev.hadContent();
			boolean emptyNow = !hasVisibleContent(guiGrid, guiResult);
			if (hadPrev && emptyNow) {
				// 关键探测：上帧有内容、本帧整桌读空。若此时退场尚未建立（hasActiveExit=false），
				// appendOneState 会整桌跳过 → 「所有物品闪没一帧」。打印决定依据。
				boolean active = hasActiveExit(time, guiPos);
				TemplateMod.LOGGER.warn("[DIAG] t={} 整桌变空(hadPrev=true) activeExit={} result.isEmpty={} gridEmpty={}",
						time, active, guiResult.isEmpty(), guiGrid == null || guiGrid.stream().allMatch(ItemStack::isEmpty));
			}
		}

		// —— 漫游早退（性能优化，整合包友好）：无当前 GUI、当前维度无任何存储记录、且上一帧
		// 快照也已空 → 本帧没有任何可渲染/可过渡的工作台，整帧跳过 syncTransitions 与
		// 存储分支的全量遍历（peekAll/peekAllIncludingEmpty 扫缓存）。整合包里闲置工作台不入
		// 存储缓存，此判断几乎恒真，漫游时省掉无谓白算。
		// 安全性：仅当 LAST_STORE 也为空才跳过——若上帧还有旧记录需过渡退场则正常执行；
		// 一旦出现任何记录（hasAny）或打开 GUI，自动退出早退恢复正常渲染。
		boolean idleFastSkip = guiPos == null
				&& !CraftingGridStorage.hasAny(world.dimension().identifier().toString())
				&& LAST_STORE.isEmpty();

		// 同步驱动的过渡动画：先于存储分支处理缓存内容变化（内容从非空变空/不同 → 启动退场）。
		// 这让联机下「其他玩家台上内容消失」也能在所有观察者视角播退场动画，不受 keep 门控
		// （A 关桌 keep 关闭时 B 也能看到残影缩小）。
		if (firstPass && !idleFastSkip) {
			syncTransitions(cfg, world, cameraEntity, time);
		}

		// 关闭检测：某位置上帧是 GUI、本帧不再是（guiPos 变化/清空）→ 触发一次「全部退场」。
		// 仅 keep 关闭时需要（keep 开启时物品留在台上，由存储分支继续渲染，不缩）。
		// 用快照迭代：处理中会移除 LAST_GUI 条目。
		for (Map.Entry<BlockPos, LastGui> e : new ArrayList<>(LAST_GUI.entrySet())) {
			BlockPos lastPos = e.getKey();
			if (lastPos.equals(guiPos)) {
				continue; // 仍是当前 GUI，保留记录
			}
			LastGui last = e.getValue();
			if (!cfg.keepItemsWhenClosed) {
				beginExitAll(cfg, time, lastPos, last.grid(), last.result());
			}
			LAST_GUI.remove(lastPos);
		}
		// 实时 GUI 内容：先做「空手打开」兜底——自己的 3×3 网格全空但该位置有保留/远程内容
		// （其他玩家刚放入的内容）时，用保留记录接管悬浮预览，让开桌玩家也能看到台上的方块，
		// 并在自己实际操作（放入内容）之前始终跟随 A 的编辑（缓存随广播实时更新）。
		// 一旦自己的网格放入内容，实时内容自然接管。
		// 「空网格兜底接管」仅当本次 GUI 会话<b>从未出现过任何内容</b>（真·空手打开）时启用：
		// 玩家操作过（放过/撤过材料）后网格变空，应显示真空而非存储里的旧记录——否则
		// 撤掉材料后结果/材料仍被渲染出来（旧记录残留），退出时还会触发结果重放。
		if (guiPos != null && guiGrid != null && !hasVisibleContent(guiGrid, guiResult)
				&& !lastGuiHadContent(guiPos)) {
			CraftingGridStorage.GridData remote = findStoredAt(world, guiPos);
			if (remote != null) {
				ItemStack storedResult = remote.result();
				guiGrid = remote.inputs();
				guiResult = storedResult != null ? storedResult : ItemStack.EMPTY;
			}
		}
		// 记录当前 GUI 内容（副本）供下次关闭检测使用；hadContent 表示本会话是否出现过内容。
		if (guiPos != null && guiGrid != null) {
			boolean had = lastGuiHadContent(guiPos) || hasVisibleContent(guiGrid, guiResult);
			LAST_GUI.put(guiPos, new LastGui(copiesOf(guiGrid),
					guiResult.isEmpty() ? ItemStack.EMPTY : guiResult.copy(), had));
		}

		// —— 走进/走出距离动画（P2-1b，用户需求） ——
		// 本帧「在渲染距离内且可能有可见内容」的位置（peekAll 非空记录 + GUI——打开着的
		// 工作台玩家必在旁，必在距离内）。<b>inRangeData</b> 一次遍历构建
		// （pos → data），供下方走进检测与保留数据分支<u>复用</u>，避免每帧两次 peekAll 遍历。
		FRAME_IN_RANGE_DATA.clear();
		FRAME_IN_RANGE_NOW.clear();
		Map<BlockPos, CraftingGridStorage.GridData> inRangeData = FRAME_IN_RANGE_DATA;
		Set<BlockPos> inRangeNow = FRAME_IN_RANGE_NOW;
		if (idleFastSkip) {
			// 漫游早退：当前维度无任何存储记录——inRangeData/Now 保持空，存储分支自然跳过全量遍历。
			// guiPos 为 null（早退前提之一），故 inRangeNow 也为空；PREV_IN_RANGE 下方照常清空。
		} else {
			for (CraftingGridStorage.StoredPreview stored
					: CraftingGridStorage.peekAll(world.dimension().identifier().toString())) {
				if (withinRenderDistance(cfg, cameraEntity, stored.pos())) {
					inRangeData.put(stored.pos(), stored.data());
					inRangeNow.add(stored.pos());
				}
			}
			if (guiPos != null) {
				inRangeNow.add(guiPos);
			}
		}
		// 走进（上一帧不在、本帧在）：清除该位置全部动画状态 → 存储分支本帧 advanceGrowth
		// 重建 → 重播生长（进入）动画。清 LAST_STORE 防 syncTransitions 拿旧快照做 diff。
		if (firstPass) {
			for (BlockPos pos : inRangeNow) {
				if (!PREV_IN_RANGE.contains(pos)) {
					GROWTH_STATE.keySet().removeIf(k -> k.pos().equals(pos));
					LAST_STORE.remove(pos);
				}
			}
		}
		// 走出：不在此处处理——syncTransitions 的快照按距离过滤，位置脱离快照即自动启动退场
		// （startExitForRemoved）；残影由收尾 pass 渲染（收尾 pass 只处理退场中位置，不设距离过滤）。
		PREV_IN_RANGE.clear();
		PREV_IN_RANGE.addAll(inRangeNow);

		// 本帧已追加渲染状态的位置（去重：动画收尾 pass 不能再追加同一位置，避免双份渲染）。
		FRAME_APPENDED.clear();
		Set<BlockPos> appended = FRAME_APPENDED;

		// 保留/共享数据分支：渲染「非当前 GUI」工作台上的存储内容（含其他玩家实时放的
		// 材料/结果——服务端每次编辑无条件 storeMemory + 广播进存储缓存，peekAll 读取）。
		// <b>不随自己的 keepItemsWhenClosed 门控</b>：keep 只决定「我关桌后是否保留我自己的
		// 材料」（服务端关桌时已据此删/留存储），而「看到别人在共享台上放的东西」是独立的
		// 多玩家可视化职责，关闭 keep 不该连带关闭看别人的内容（原 bug：keep=false 者看不到
		// 对方实时放置的预览；服务端 keep=false 关桌已清存储+归还，故此处放开门控不会渲染
		// 已经归还的陈旧物品）。GUI 位置仍跳过，避免与实时内容重叠。
		// 复用 inRangeData（已滤距离 + 非空），不再重复 peekAll 遍历。
		for (Map.Entry<BlockPos, CraftingGridStorage.GridData> e : inRangeData.entrySet()) {
			BlockPos pos = e.getKey();
			if (pos.equals(guiPos)) {
				continue;
			}
			if (world.getBlockState(pos).getBlock() != Blocks.CRAFTING_TABLE) {
				continue;
			}
			CraftingGridStorage.GridData data = e.getValue();
			ItemStack storedResult = data.result();
			// 先推进动画状态（全空帧也能启动/继续退场），再追加渲染。
			appended.add(pos);
			if (firstPass) {
				advanceGrowth(cfg, time, pos, data.inputs(),
						storedResult != null ? storedResult : ItemStack.EMPTY);
			}
			appendOneState(worldRenderState, world, time, pos, data.inputs(),
					storedResult != null ? storedResult : ItemStack.EMPTY);
		}

		// 实时 GUI 内容。
		if (guiPos != null && guiGrid != null) {
			// 提取瞬间修复：本帧整桌读空、但上帧还有内容（材料+结果被一次性提取走）时，
			// 退场状态可能尚未建立（advanceGrowth 里旧物 state 可能已被清空/current 为空），
			// 导致 appendOneState 的 hasActiveExit=false 而「整桌闪没一帧」。用上一帧的内容
			// 为刚消失的物品补建退场状态，让它们从既有位置平滑缩小、不闪。
			// 注意：seedExitFromPrev 幂等（仅当槽位尚无退场物时才补建），且必须在 appendOneState
			// 判定 hasActiveExit 之前执行——因此<b>不随 firstPass 门控</b>（主/影两 pass 都会走到
			// 这里，任一个先执行都能确保 exits 建立，规避 pass 顺序导致的概率性漏建）。
			if (!hasVisibleContent(guiGrid, guiResult)
					&& prevGui != null && prevGui.hadContent()) {
				if (DIAG) {
					int prevItems = (prevGui.grid() == null ? 0
							: (int) prevGui.grid().stream().filter(s -> s != null && !s.isEmpty()).count())
							+ (prevGui.result() != null && !prevGui.result().isEmpty() ? 1 : 0);
					TemplateMod.LOGGER.warn("[DIAG] t={} 尝试 seedExitFromPrev: prevItems={}", time, prevItems);
				}
				seedExitFromPrev(cfg, time, guiPos, prevGui.grid(), prevGui.result());
			}
			// 先推进动画状态（全空帧也能启动/继续退场），再追加渲染。
			appended.add(guiPos);
			if (firstPass) {
				advanceGrowth(cfg, time, guiPos, guiGrid, guiResult);
			}
			appendOneState(worldRenderState, world, time, guiPos, guiGrid, guiResult);
		}

		// 动画收尾 pass：关闭 GUI 后（或 keep 关闭时），把仍在进行中的退场动画继续播完——
		// 网格/结果已空（记录被 peekAll 过滤、或根本没有存储记录），但 GROWTH_STATE 里还有
		// 正在缩小的旧物品：用空内容推进 + 渲染，让残影缩到消失为止。
		for (Map.Entry<GrowthKey, GrowthState> e : new ArrayList<>(GROWTH_STATE.entrySet())) {
			GrowthKey k = e.getKey();
			if (appended.contains(k.pos())) {
				continue;
			}
			GrowthState gs = e.getValue();
			if (gs.exiting == null) {
				// 只收尾退场动画：进入中的物品若仍真实存在（keep 开启），已由存储分支渲染；
				// keep 关闭时物品已归还背包，不应渲染幽灵（避免凭空多出物品）。
				continue;
			}
			// 走出距离的退场残影也要渲染（P2-1b）：位置脱离快照由 syncTransitions 启动了退场，
			// 残影在 GROWTH_STATE 里缩小中——不论远近都播完（0.3s），播完 exiting 清空后自然
			// 停帧。无退场的位置不会走到这里，故不设距离过滤。内存：残影播完后条目残留，
			// 由玩家下次走近时的「走进检测」清除（或保持到方块被拆）。
			List<ItemStack> emptyGrid = List.of();
			if (firstPass) {
				advanceGrowth(cfg, time, k.pos(), emptyGrid, ItemStack.EMPTY);
			}
			appendOneState(worldRenderState, world, time, k.pos(), emptyGrid, ItemStack.EMPTY);
		}
	}

	/**
	 * 列表是否已含本帧的预览渲染状态（用于幂等去重）。
	 */
	private static boolean containsPreviewState(net.minecraft.client.renderer.state.level.LevelRenderState worldRenderState) {
		for (BlockEntityRenderState state : worldRenderState.blockEntityRenderStates) {
			if (state instanceof PreviewRenderState) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 构造一个标准的方块实体渲染状态并追加到 BE 渲染列表。
	 */
	private static void appendOneState(net.minecraft.client.renderer.state.level.LevelRenderState worldRenderState,
			ClientLevel world, long time, BlockPos pos, List<ItemStack> grid, ItemStack result) {
		// 内容全空但仍需渲染的唯一情况：该位置有正在退场的物品（拿走动画收尾）。
		if (!hasVisibleContent(grid, result) && !hasActiveExit(time, pos)) {
			if (DIAG) {
				LastGui lg = LAST_GUI.get(pos);
				int growthCount = 0;
				StringBuilder sb = new StringBuilder();
				for (Map.Entry<GrowthKey, GrowthState> e : GROWTH_STATE.entrySet()) {
					if (!e.getKey().pos().equals(pos)) continue;
					growthCount++;
					GrowthState gs = e.getValue();
					String cur = (gs.current != null && !gs.current.isEmpty())
							? gs.current.getHoverName().getString() : "-";
					String ex = (gs.exiting != null && !gs.exiting.isEmpty())
							? gs.exiting.getHoverName().getString() : "-";
					sb.append("[").append(e.getKey().index()).append(":c=").append(cur)
							.append(",ex=").append(ex).append("] ");
				}
				TemplateMod.LOGGER.warn("[DIAG] t={} pos={} appendOneState 整桌跳过: hasContent=false activeExit=false "
						+ "growthCount={} lastGuiHadContent={} slots={}",
						time, pos, growthCount, lg != null && lg.hadContent(), sb.toString());
			}
			return;
		}
		PreviewRenderState state = new PreviewRenderState();
		state.blockPos = pos;
		state.time = time;
		state.grid = grid;
		state.result = result;
		state.hasContent = true;
		state.lightCoords = computeLight(world, pos);
		worldRenderState.blockEntityRenderStates.add(state);
	}

	/**
	 * 每帧推进某工作台全部槽位（9 材料 + 结果）的进入/退场动画状态（纯状态更新，不渲染）。
	 *
	 * <p>必须在「追加渲染状态」之前调用：当网格/结果全空时，若退场尚未启动，
	 * {@code hasActiveExit} 为 false 会导致整个预览状态被跳过、退场动画永远无法开始
	 * （拿走最后一个物品时直接消失）。先推进、再判定可见性，即可让「全空帧」也正确
	 * 启动并继续退场动画。
	 *
	 * @param grid   当前槽位内容（可为空列表/全空）
	 * @param result 当前结果（可为空）
	 */
	private static void advanceGrowth(PreviewConfig cfg, long time, BlockPos pos,
			List<ItemStack> grid, ItemStack result) {
		for (int index = 0; index < GRID_SLOT_COUNT; index++) {
			ItemStack stack = index < grid.size() ? grid.get(index) : ItemStack.EMPTY;
			slotAnimation(cfg, time, 0.0F, pos, index, stack);
		}
		slotAnimation(cfg, time, 0.0F, pos, RESULT_GROWTH_INDEX,
				result == null ? ItemStack.EMPTY : result);
	}

	/**
	 * 「提取后补建退场」：当前帧内容整桌变空、但上一帧还有可见内容时，用上一帧的槽位内容为
	 * 所有刚消失的物品（材料 + 结果）补建退场状态。
	 *
	 * <p>背景：提取结果（材料也被一次性消耗）的瞬间，GUI 帧读到全空；advanceGrowth 只能为
	 * 「state 仍持有 current」的槽位启动退场。若相关槽位的 state 恰好已被清空 / current 为空
	 * （历史残留、走进检测清态等），则没有任何 exiting 被建立 → hasActiveExit=false →
	 * appendOneState 把整桌跳过 → 所有物品闪没一帧后才开始退场。本方法用上一帧内容兜底，
	 * 确保该位置上帧显示过的每个物品都在退场，整桌平滑缩小不闪。
	 *
	 * <p>幂等：目标槽位已有 exiting 则跳过；不影响已有 current / enterTick。
	 *
	 * @param prevGrid   上一帧的 9 格材料（可为空列表）
	 * @param prevResult 上一帧的结果（可空）
	 */
	private static void seedExitFromPrev(PreviewConfig cfg, long time, BlockPos pos,
			List<ItemStack> prevGrid, ItemStack prevResult) {
		if (!cfg.growthEnabled) {
			return;
		}
		int seeded = 0;
		for (int index = 0; index < GRID_SLOT_COUNT; index++) {
			ItemStack prev = index < prevGrid.size() ? prevGrid.get(index) : ItemStack.EMPTY;
			if (prev == null || prev.isEmpty()) {
				continue;
			}
			if (seedExitSlot(cfg, time, pos, index, prev)) {
				seeded++;
			}
		}
		if (prevResult != null && !prevResult.isEmpty() && seedExitSlot(cfg, time, pos, RESULT_GROWTH_INDEX, prevResult)) {
			seeded++;
		}
		if (DIAG && seeded > 0) {
			TemplateMod.LOGGER.warn("[DIAG] t={} pos={} seedExitFromPrev 成功补建 {} 个退场槽位", time, pos, seeded);
		}
	}

	/** 为单个槽位用「上一帧内容」补建退场（仅当其尚无退场物品时）；返回是否真正补建。 */
	private static boolean seedExitSlot(PreviewConfig cfg, long time, BlockPos pos,
			int index, ItemStack prev) {
		GrowthKey key = new GrowthKey(pos, index);
		GrowthState st = GROWTH_STATE.computeIfAbsent(key, k -> new GrowthState());
		if (st.exiting == null || st.exiting.isEmpty()) {
			st.exiting = prev.copy();
			st.exitTick = time;
			return true;
		}
		return false;
	}

	/**
	 * 同步驱动的过渡动画（所有玩家视角一致，不受 keep 门控）：
	 * 跟踪存储缓存内容变化（含空记录）。内容从「非空」变「空/不同」时，为被移除的槽位
	 * 直接启动退场动画——联机下 B 在 keep 关闭时看不到 A 的退场，就是因为 A 关桌广播空记录
	 * 后 B 的缓存被直接清空、而 B 的存储分支（keep 门控）没有机会为旧内容启动退场。
	 * 这里不渲染内容本身，只负责「启动退场」；渲染交由存储分支 / GUI 兜底 / 动画收尾 pass。
	 *
	 * <p>幂等：同一槽位已有退场中的物品时不再重复启动（exiting == null 守卫）。
	 */
	private static void syncTransitions(PreviewConfig cfg, ClientLevel world, Entity cameraEntity, long time) {
		if (!cfg.growthEnabled) {
			return;
		}
		String dimKey = world.dimension().identifier().toString();
		if (!dimKey.equals(lastStoreDimKey)) {
			lastStoreDimKey = dimKey;
			LAST_STORE.clear();
			ITEM_STATE_CACHE.clear(); // 维度切换：物品模型状态一并释放（模型基于旧维度资源）
		}
		// P0-3 引用快照：grid/result 直接存缓存引用（不深拷贝），data 对象身份（==）即内容版本——
		// cache 写入路径全部整体替换新 GridData（normalize 深拷贝），故引用不变 ⟺ 内容没变。
		// P2-1 距离裁剪：只登记渲染距离内的工作台快照（远处不启动退场也不维护状态，
		// LAST_STORE 每帧 clear+putAll 只含近处 → 远处快照自动清理）。
		Map<BlockPos, GridSnapshot> now = new HashMap<>();
		for (CraftingGridStorage.StoredPreview stored
				: CraftingGridStorage.peekAllIncludingEmpty(dimKey)) {
			if (!withinRenderDistance(cfg, cameraEntity, stored.pos())) {
				continue;
			}
			CraftingGridStorage.GridData data = stored.data();
			now.put(stored.pos(), new GridSnapshot(true,
					data.inputs(),
					data.result() != null ? data.result() : ItemStack.EMPTY,
					data));
		}
		// 对比上一帧：内容从非空变空/不同 → 启动退场。
		for (Map.Entry<BlockPos, GridSnapshot> e : LAST_STORE.entrySet()) {
			BlockPos pos = e.getKey();
			GridSnapshot prev = e.getValue();
			if (!prev.present()) {
				continue; // 上一帧本就不存在，无需退场
			}
			GridSnapshot cur = now.get(pos);
			if (cur == null || !cur.present()) {
				// 记录被移除/变为全空 → 全部非空槽位退场
				startExitForRemoved(cfg, time, pos, prev.grid(), prev.result());
				continue;
			}
			// 引用相同 = 同一 GridData 对象 = 内容未变（写路径整体替换）→ 零拷贝跳过。
			if (cur.data() == prev.data()) {
				continue;
			}
			// 槽位逐一对比：旧非空、新空/不同 → 退场
			startExitForDiff(cfg, time, pos, prev.grid(), cur.grid());
			if (!ItemStack.matches(prev.result(), cur.result())) {
				GrowthKey gk = new GrowthKey(pos, RESULT_GROWTH_INDEX);
				GrowthState st = GROWTH_STATE.computeIfAbsent(gk, k -> new GrowthState());
				if (st.exiting == null && prev.result() != null && !prev.result().isEmpty()) {
					st.exiting = prev.result().copy();
					st.exitTick = time;
				}
			}
		}
		// 记录本帧快照供下一帧对比；清理已消失的位置。
		LAST_STORE.clear();
		LAST_STORE.putAll(now);
	}

	/** 记录整个被移除/变全空：所有旧非空槽位启动退场。 */
	private static void startExitForRemoved(PreviewConfig cfg, long time, BlockPos pos,
			List<ItemStack> oldGrid, ItemStack oldResult) {
		startExitForDiff(cfg, time, pos, oldGrid, List.of());
		GrowthKey gk = new GrowthKey(pos, RESULT_GROWTH_INDEX);
		GrowthState st = GROWTH_STATE.computeIfAbsent(gk, k -> new GrowthState());
		if (st.exiting == null && oldResult != null && !oldResult.isEmpty()) {
			st.exiting = oldResult.copy();
			st.exitTick = time;
		}
	}

	/** 槽位逐一：旧非空、新空/不同 → 启动退场（复用/新建 GrowthState）。 */
	private static void startExitForDiff(PreviewConfig cfg, long time, BlockPos pos,
			List<ItemStack> oldGrid, List<ItemStack> newGrid) {
		for (int i = 0; i < GRID_SLOT_COUNT; i++) {
			ItemStack oldS = i < oldGrid.size() ? oldGrid.get(i) : ItemStack.EMPTY;
			if (oldS == null || oldS.isEmpty()) {
				continue;
			}
			ItemStack newS = i < newGrid.size() ? newGrid.get(i) : ItemStack.EMPTY;
			if (newS != null && !newS.isEmpty() && ItemStack.matches(oldS, newS)) {
				continue; // 内容未变
			}
			GrowthKey gk = new GrowthKey(pos, i);
			GrowthState st = GROWTH_STATE.computeIfAbsent(gk, k -> new GrowthState());
			if (st.exiting == null) {
				st.exiting = oldS.copy();
				st.exitTick = time;
			}
		}
	}

	/**
	 * 按位置查找当前维度的保留记录（含全空记录的对比场景用不到空记录）——优先返回
	 * 非空记录，兜底返回该位置的记录（可能为空，此时调用方自行判断内容）。
	 */
	private static CraftingGridStorage.GridData findStoredAt(ClientLevel world, BlockPos pos) {
		String dimKey = world.dimension().identifier().toString();
		for (CraftingGridStorage.StoredPreview stored : CraftingGridStorage.peekAll(dimKey)) {
			if (stored.pos().equals(pos)) {
				return stored.data();
			}
		}
		return null;
	}

	/**
	 * 复制一份槽位内容（非空栈深拷贝），防止与 GUI 槽位栈共享可变对象。
	 */
	private static List<ItemStack> copiesOf(List<ItemStack> src) {
		List<ItemStack> out = new ArrayList<>(src.size());
		for (ItemStack s : src) {
			out.add(s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy());
		}
		return out;
	}

	/**
	 * 关闭 GUI（keep 开关关闭）时：把最后看到的网格内容当作「全部拿走」——对每个非空槽位
	 * 以空栈推进动画状态，为它们启动退场动画（残影缩小消失）。
	 *
	 * <p>仅 keep 关闭时需要：keep 开启时物品会留在工作台上由存储分支继续渲染（不缩）。
	 */
	private static void beginExitAll(PreviewConfig cfg, long time, BlockPos pos,
			List<ItemStack> grid, ItemStack result) {
		if (!cfg.growthEnabled) {
			return;
		}
		// 直接为每个可见物品补建退场（seedExitSlot 幂等：仅当该槽位尚无退场物才建 exiting）。
		// 旧实现依赖 slotAnimation(EMPTY) 从 st.current 启动退场，但若槽位的 GROWTH_STATE
		// current 已被清空/未建立（关桌与提取一样存在此脆弱假设），则退场不会启动、
		// hasActiveExit=false → 整桌跳过一帧 →「关闭 GUI 时闪一下」。直接按传入内容建 exiting
		// 不依赖 st.current，保证关桌后残影一定平滑缩小。
		for (int i = 0; i < GRID_SLOT_COUNT; i++) {
			ItemStack s = i < grid.size() ? grid.get(i) : ItemStack.EMPTY;
			if (s == null || s.isEmpty()) {
				continue;
			}
			seedExitSlot(cfg, time, pos, i, s);
		}
		if (result != null && !result.isEmpty()) {
			seedExitSlot(cfg, time, pos, RESULT_GROWTH_INDEX, result);
		}
	}

	/**
	 * 该位置是否存在「正在退场」的物品（拿走/替换后缩小消失中的旧物品）。
	 * 用于内容全空时仍继续渲染——否则最后一个物品被拿走时整个预览状态消失，退场动画无法播放。
	 *
	 * <p>性能优化：不每次 O(N) 全表扫 GROWTH_STATE，而是复用每帧构建一次的
	 * {@link #ACTIVE_EXIT_SNAPSHOT}（见该字段）；{@code time} 用于惰性重建快照
	 * （主/影两 pass 同 tick 共享，幂等）。异常路径（快照尚未构建）回退全表扫描。
	 */
	private static boolean hasActiveExit(long time, BlockPos pos) {
		if (lastActivesSnapshotTick != time) {
			lastActivesSnapshotTick = time;
			ACTIVE_EXIT_SNAPSHOT.clear();
			for (Map.Entry<GrowthKey, GrowthState> e : GROWTH_STATE.entrySet()) {
				if (e.getValue().exiting != null) {
					ACTIVE_EXIT_SNAPSHOT.add(e.getKey().pos());
				}
			}
		}
		return ACTIVE_EXIT_SNAPSHOT.contains(pos);
	}

	/** 无 time 的兼容重载（快照未构建时回退全表扫描，保证行为一致）。 */
	private static boolean hasActiveExit(BlockPos pos) {
		if (lastActivesSnapshotTick == -1) {
			for (Map.Entry<GrowthKey, GrowthState> e : GROWTH_STATE.entrySet()) {
				if (e.getKey().pos().equals(pos) && e.getValue().exiting != null) {
					return true;
				}
			}
			return false;
		}
		return ACTIVE_EXIT_SNAPSHOT.contains(pos);
	}

	/**
	 * 网格与结果全空则返回 {@code false}（无需渲染）。
	 */
	private static boolean hasVisibleContent(List<ItemStack> grid, ItemStack result) {
		if (result != null && !result.isEmpty()) {
			return true;
		}
		for (ItemStack s : grid) {
			if (s != null && !s.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** 本次 GUI 会话是否出现过可见内容（用于「空网格兜底接管」门控）。 */
	private static boolean lastGuiHadContent(BlockPos pos) {
		LastGui last = LAST_GUI.get(pos);
		return last != null && last.hadContent();
	}

	/**
	 * 预览几何核心：在工作台<b>局部坐标</b>绘制 3×3 材料 + 结果（含面板朝向动画、
	 * 浮动、结果自转）。
	 *
	 * <p>矩阵必须在调用前已平移到工作台方块左下角（相机相对），本方法不做任何平移/压栈；
	 * 光照由调用方提供（状态内存储的 lightmap）。
	 * 被正常视图与阴影 pass 的 {@link BlockEntityRenderDispatcherMixin} 共用——两处动画状态
	 * （FACING_STATE 按 tablePos 共享）严格一致。
	 */
	public static void renderPreviewGeometry(PoseStack matrices, SubmitNodeCollector collector,
			Minecraft client, Entity cameraEntity, PreviewConfig cfg, long time, float tickDelta,
			BlockPos tablePos, List<ItemStack> grid, ItemStack result, int light) {
		// 内容全空但仍需渲染的唯一情况：该位置有正在退场的物品（拿走动画收尾）。
		if (!hasVisibleContent(grid, result) && !hasActiveExit(time, tablePos)) {
			return;
		}

			// —— 面板朝向：整块预览绕工作台中心竖直轴旋转，使其正面（+Z）朝向「朝向源」 ——
			// 两档语义（互斥）：
			//  (1) 不随玩家旋转（FIXED）或 keep 关闭：方向源 = 服务端广播的最后操作者开桌方位
			//      ——双方同步（所有客户端统一），「上一次点击（开桌）才切换方向」，之后锁定不动。
			//  (2) 跟随玩家（keep 开启且 FOLLOW_PLAYER）：方向源 = 自己的实时方位——纯客户端
			//      效果，面板实时跟随自己旋转，各客户端只看自己的、多人与服务端都不同步。
			// 面板随方向源变化平滑转场（跨扇形动画）。
			float panelYawDeg = 0.0F;
			{
				boolean lockOpenDir = !cfg.keepItemsWhenClosed
						|| cfg.facingMode == PreviewConfig.FacingMode.FIXED;
				// 正确扇形 = 最近正方向取整：+0.5 再 floor 等价于 round(φ/90) mod 4，
				// 切换边界落在两相邻正方向的等距中点（45°/135°/225°/315° 对角），任意站立
				// 方向与面板正面的夹角 ≤ 45°，站正方向时完全正对。若直接 floor 会全程保持
				// 扇区起始方向、直到玩家精确到达下一个正方向才切换（滞后，误差可达 ~90°）。
				int sector = lockOpenDir
						? resolveLockSector(tablePos)
						: resolveFollowSector(tablePos, cameraEntity);
				float sectorBaseDeg = sector * FACING_SECTOR_DEGREES;

				FacingState st = FACING_STATE.computeIfAbsent(tablePos, p -> new FacingState());
				// 与 renderPreview 的 time（= world.getOverworldClockTime() 单调时钟）同一时钟，转场进度才能用
				// (time + tickDelta) 逐渲染帧插值（否则两套时钟相减基线错乱）。+1/刻与 getTime 一致。
				long nowTick = time;
				if (st.sector != sector) {
					if (st.sector == -1) {
						// 首次出现（打开 GUI、或保留预览初次渲染）：直接吸附到目标扇形，
						// 不播起手动画——否则面板不在 0° 方向时，会先从 0° 明显空转一段再
						// 追向目标方向（实机反馈「打开/关闭工作台会重新定向」。Visual Workbench
						// 用常驻动画控制器持续跟踪，角度早已到位，故无此问题）。
						st.currentDeg = sectorBaseDeg;
						st.animStartDeg = sectorBaseDeg;
						st.animTargetDeg = sectorBaseDeg;
					} else {
						// 跨入新扇形：启动一次转场动画，从当前显示角沿最短弧转到新的扇形基准角。
						// 时长由配置「朝向转场用时」控制（默认 0.8 秒 = 16 刻；历史固定 1 秒 = 20 刻）。
						st.animStartTick = nowTick;
						st.animStartDeg = st.currentDeg;
						st.animTargetDeg = sectorBaseDeg;
					}
					st.sector = sector;
				}
				float animTicks = (float) Math.max(1.0D, cfg.facingAnimationSeconds * 20.0D);
				// 用小数刻 (time + tickDelta) 而非整数刻推进：每渲染帧角度都移动（60~144fps 下无 20 刻
				// 顿挫感），与浮动/结果动画同款插值节奏。time 已改用单调的 world.getOverworldClockTime()，配下界钳制
				// 防御任何时钟回退（如重进世界新档总时更小）——否则 animateStartTick > nowTick 产生
				// 巨大负进度、面板疯狂旋转（getTimeOfDay 正是被 /time 指令跳变触发此问题）。
				float progress = Math.max(0.0F, ((time + tickDelta) - st.animStartTick) / animTicks);
				if (progress >= 1.0F) {
					st.currentDeg = st.animTargetDeg;
				} else {
					// ease-out（缓出，参考 VS 的 ease-out）：先快后慢、最后减速停稳，比匀速急停更丝滑。
					float e = 1.0F - progress;
					float eased = 1.0F - e * e * e;
					float deltaDeg = Mth.wrapDegrees(st.animTargetDeg - st.animStartDeg);
					st.currentDeg = st.animStartDeg + deltaDeg * eased;
				}
				FACING_TOUCHED.add(tablePos);
				panelYawDeg = st.currentDeg;
			}
			if (panelYawDeg != 0.0F) {
				// 以工作台顶面中心为旋转轴（纯竖直轴旋转，与高度无关）。
				matrices.translate(0.5D, 0.0D, 0.5D);
				matrices.mulPose(Axis.YP.rotation(panelYawDeg * Mth.DEG_TO_RAD));
				matrices.translate(-0.5D, 0.0D, -0.5D);
			}

			// 浮动相位：材料与结果各自使用配置的浮动速度。
			double materialPhase = (time + tickDelta) * radPerTick(cfg.floatSeconds);
			double resultPhase = (time + tickDelta) * radPerTick(cfg.resultFloatSeconds);

			// 材料基座 = 桌面(y+1) + 配置浮空高度；结果物基座在材料上方保持固定间距。
			double materialBase = TABLE_TOP_Y + cfg.floatHeight;
			double resultBase = materialBase + cfg.resultHeightGap;

			// —— 1. 渲染 3×3 合成材料 ——
			for (int index = 0; index < GRID_SLOT_COUNT; index++) {
				ItemStack stack = index < grid.size() ? grid.get(index) : ItemStack.EMPTY;

				int row = index / 3;   // 行（Z）
				int col = index % 3;   // 列（X）

				// 材料预览围绕方块中心展开 3×3，浮在对应槽位上方（退场物品也按同一槽位渲染）。
				double localX = 0.5D + (col - 1) * cfg.slotSpacing;
				double localZ = 0.5D + (row - 1) * cfg.slotSpacing;

				// 进入 + 退场动画状态推进（拿走物品时旧物品缩小退场）。
				SlotAnim anim = slotAnimation(cfg, time, tickDelta, tablePos, index, stack);

				// 退场中的旧物品：原地缩小消失（平摊/悬浮都按各自落位）。
				if (!anim.exiting().isEmpty()) {
					if (cfg.ingredientStyle == PreviewConfig.IngredientStyle.FLAT) {
						renderItem(matrices, collector, anim.exiting(), localX, TABLE_TOP_Y, localZ,
								(float) cfg.materialScale * anim.exitFactor(), 0.0F, light,
								client, cameraEntity, true, ItemDisplayContext.FIXED);
					} else {
						double exitBase = materialBase
								- (anim.exiting().getItem() instanceof BlockItem ? BLOCK_ITEM_LOWER_OFFSET : 0.0D);
						// 退场物冻结在被拿走时的浮动高度（anim.exitBob），不瞬移回基座。
						renderItem(matrices, collector, anim.exiting(), localX, exitBase + anim.exitBob(), localZ,
								(float) cfg.materialScale * anim.exitFactor(), 0.0F, light,
								client, cameraEntity, false, ItemDisplayContext.FIXED);
					}
				}

				if (stack.isEmpty()) {
					continue;
				}

				if (cfg.ingredientStyle == PreviewConfig.IngredientStyle.FLAT) {
					// 平摊（参考 Visual Workbench）：材料平铺在台面上、静止不动——不参与浮动动画、
					// 不浮空；Y 传哨兵值台面顶，实际落台高度由 renderItem 按模型真实边界重算，
					// 避免门/栅栏等几何不同的物品悬空或陷入桌面。3D 方块按正常放置姿态站立
					// （顶面朝上、底边落台、正面朝向面板），刀/门/锭等 2D 薄片躺平（正面朝上）。
					renderItem(matrices, collector, stack, localX, TABLE_TOP_Y, localZ,
							(float) cfg.materialScale * anim.enterFactor(), 0.0F, light,
							client, cameraEntity, true, ItemDisplayContext.FIXED);
				} else {
					// 悬浮（默认）：浮在台面上方对应槽位、按浮动类型起伏。
					// 方块类物品基座比扁平类略低，使其相对自身尺寸显得更贴近桌面。
					double baseHeight = materialBase
							- (stack.getItem() instanceof BlockItem ? BLOCK_ITEM_LOWER_OFFSET : 0.0D);

					double bob = bobFor(cfg.floatingMode, materialPhase, index, cfg.floatAmplitude);
					renderItem(matrices, collector, stack, localX, baseHeight + bob, localZ,
							(float) cfg.materialScale * anim.enterFactor(), 0.0F, light,
							client, cameraEntity, false, ItemDisplayContext.FIXED);
				}
			}

			// —— 2. 渲染合成结果（配方成立时；拿走/失效时缩小退场） ——
			{
				SlotAnim anim = slotAnimation(cfg, time, tickDelta, tablePos, RESULT_GROWTH_INDEX, result);
				float angleDeg = (time + tickDelta) * degPerTick(cfg.rotationSeconds);

				// 退场中的旧结果：原地缩小消失（保持自转，不随面板偏航）。
				// 扁平（非方块）结果额外放大一点、并比方块结果略高（与当前结果同逻辑；退场物一般同类）。
				if (!anim.exiting().isEmpty()) {
					boolean exitIsFlat = isFlatItem(anim.exiting(), client, cameraEntity, ItemDisplayContext.GROUND);
					float exitFlat = exitIsFlat ? RESULT_FLAT_BONUS_SCALE : 1.0F;
					double exitRaise = exitIsFlat ? RESULT_FLAT_HEIGHT_RAISE : 0.0D;
					float exitYawDeg = angleDeg - panelYawDeg;
					// 退场结果冻结在被拿走时的浮动高度（anim.exitBob），不瞬移回中间基座。
					renderItem(matrices, collector, anim.exiting(), 0.5D, resultBase + exitRaise + anim.exitBob(), 0.5D,
							(float) cfg.resultScale * exitFlat * anim.exitFactor(), exitYawDeg, light,
							client, cameraEntity, false, ItemDisplayContext.GROUND);
				}

				if (!result.isEmpty()) {
					// 生长动画：结果出现/变化时从零放大。
					double resultBob = bobFor(cfg.floatingMode, resultPhase, 0, cfg.resultFloatAmplitude);
					// 结果物不参与面板朝向旋转：仅保留「结果旋转速度」自转，不被面板偏航带动。
					// 结果位于面板旋转轴（0.5, *, 0.5）上，位置本就不随面板转动；两张都是绕 Y 轴的
					// 旋转，角度可直接相减——用 -panelYawDeg 抵消父级面板偏航，使全局朝向 = 自身自转角。
					float resultYawDeg = angleDeg - panelYawDeg;
					// 扁平（非方块，如刀/锭/药水）结果额外乘 RESULT_FLAT_BONUS_SCALE 放大一点、
					// 并比方块结果抬高 RESULT_FLAT_HEIGHT_RAISE；方块类结果保持 resultScale/高度原样。
					boolean resultFlat = isFlatItem(result, client, cameraEntity, ItemDisplayContext.GROUND);
					float flatBonus = resultFlat ? RESULT_FLAT_BONUS_SCALE : 1.0F;
					double flatRaise = resultFlat ? RESULT_FLAT_HEIGHT_RAISE : 0.0D;
					renderItem(matrices, collector, result, 0.5D, resultBase + flatRaise + resultBob, 0.5D,
							(float) cfg.resultScale * flatBonus * anim.enterFactor(), resultYawDeg, light,
							client, cameraEntity, false, ItemDisplayContext.GROUND);
				}
			}
	}

	/**
	 * 玩家所在方位的最近 90° 正方向扇区（0/1/2/3 → 0/90/180/270°）。
	 *
	 * <p>取整方式：{@code floor(φ/90 + 0.5)} 最近取整，切换边界落在两正方向的等距中点
	 * （对角 45°/135°/225°/315°），任意站位与面板正面夹角 ≤ 45°、站正方向完全正对。
	 * 若直接 {@code floor} 会全程保持扇区起始方向、直到精确到达下一个正方向才切换
	 * （滞后，误差可达 ~90°——21:17 踩坑，21:47 校正）。
	 */
	private static int computePlayerSector(Entity cameraEntity, BlockPos tablePos) {
		double dx = cameraEntity.getX() - (tablePos.getX() + 0.5D);
		double dz = cameraEntity.getZ() - (tablePos.getZ() + 0.5D);
		float targetDeg = (float) Math.toDegrees(Math.atan2(dx, dz));
		float normDeg = (targetDeg % 360.0F + 360.0F) % 360.0F;
		return Math.floorMod((int) Math.floor(normDeg / FACING_SECTOR_DEGREES + 0.5F), 4);
	}

	/**
	 * 不随玩家旋转 / keep 关闭：方向源 = 服务端广播的「最后操作者开桌方位扇区」——
	 * 开桌（点击）时更新、之后锁定不动（绝不实时跟随玩家）。拿不到共享方向（首帧 / 联机
	 * 广播尚未送达）时固定为 0°（面板正对 +Z，不旋转），保证「不随玩家旋转」档永远不
	 * 跟随玩家转动——而不是像旧实现那样回退到自己的实时方位（那会让关闭档也实时转）。
	 */
	private static int resolveLockSector(BlockPos tablePos) {
		int shared = TableFacing.get(tablePos);
		if (shared != TableFacing.UNKNOWN) {
			return shared;
		}
		return 0;
	}

	/**
	 * 跟随玩家旋转：方向源 = <b>自己</b>的实时方位（纯客户端效果）——每帧按当前玩家（cameraEntity）
	 * 相对工作台的实时位置算方位扇区，面板实时跟随自己旋转。各客户端只看自己的、互不影响，
	 * 不与服务端同步（这正是「纯客户端效果」的语义：跟随档各自看各自的）。相反，「不随玩家
	 * 旋转」档才是双方同步（所有客户端统一朝向服务端广播的最后操作者开桌方向）。
	 */
	private static int resolveFollowSector(BlockPos tablePos, Entity cameraEntity) {
		return computePlayerSector(cameraEntity, tablePos);
	}

	/**
	 * 结果旋转速度：配置为「秒/圈」，换算成「度/游戏刻」（游戏 20 刻 = 1 秒）。
	 */
	private static float degPerTick(int rotationSeconds) {
		return 360.0F / (rotationSeconds * 20);
	}

	/**
	 * 浮动速度：配置为「秒/来回」，换算成「弧度/游戏刻」（游戏 20 刻 = 1 秒，一来回 = 2π）。
	 */
	private static double radPerTick(double secondsPerCycle) {
		return (2.0 * Math.PI) / (secondsPerCycle * 20);
	}

	/**
	 * 按浮动模式计算垂直起伏量（格）。
	 *
	 * @param slotIndex 合成槽下标（0~8）；结果物传 0（固定相位，不参与波浪错位）
	 */
	private static double bobFor(PreviewConfig.FloatingMode mode, double phase, int slotIndex, double amplitude) {
		return switch (mode) {
			case NONE -> 0.0D;
			case SYNC -> Math.sin(phase) * amplitude;
			case WAVE -> Math.sin(phase + slotIndex * WAVE_PHASE_STEP) * amplitude;
		};
	}

	/**
	 * 距离裁剪（性能优化 P2-1）：工作台与相机距离是否在渲染距离内。
	 * {@code renderDistance <= 0} 表示不限（保持全图渲染）；使用平方距离避免开方。
	 */
	private static boolean withinRenderDistance(PreviewConfig cfg, Entity cameraEntity, BlockPos pos) {
		int maxDist = cfg.renderDistance;
		if (maxDist <= 0) {
			return true;
		}
		double dx = cameraEntity.getX() - (pos.getX() + 0.5D);
		double dz = cameraEntity.getZ() - (pos.getZ() + 0.5D);
		return dx * dx + dz * dz <= (double) maxDist * (double) maxDist;
	}

	/**
	 * 计算预览光照强度（lightmap 坐标）：在工作台上方（预览物所在高度）采样方块光与
	 * 天空光，打包成 lightmap 坐标。夜晚/室内自然变暗，与原版物品实机光照一致，
	 * 光影（如 BLS）下也不会因全亮而显得自发光（光照模式配置已移除，固定此行为）。
	 */
	private static int computeLight(ClientLevel world, BlockPos tablePos) {
		return LightCoordsUtil.getLightCoords(world, tablePos.above());
	}

	/**
	 * 单槽动画（进入 + 退场）状态推进与系数计算。
	 *
	 * <p>进入：物品<b>首次出现</b>或<b>换成不同物品</b>时从 0 放大到 1（ease-out 三次），
	 * 之后保持（同一物品持续渲染不重放）。退场：槽位<b>清空（拿走）</b>或<b>被替换</b>时，
	 * 旧物品从 1 缩小到 0（ease-in 三次 = 进入动画的镜像/倒放），动画期间仍渲染旧物品。
	 *
	 * @param tablePos 工作台位置（状态键）
	 * @param index    槽位（材料 0~8；结果用 {@link #RESULT_GROWTH_INDEX}）
	 * @param stack    当前槽位栈（可为空——空则推进退场）
	 */
	private static SlotAnim slotAnimation(PreviewConfig cfg, long time, float tickDelta,
			BlockPos tablePos, int index, ItemStack stack) {
		if (!cfg.growthEnabled) {
			// 动画关闭：清掉该槽状态（不残留退场），直接完整大小显示。
			GROWTH_STATE.remove(new GrowthKey(tablePos, index));
			return new SlotAnim(1.0F, ItemStack.EMPTY, 1.0F, 0.0D);
		}
		GrowthKey key = new GrowthKey(tablePos, index);
		GrowthState st = GROWTH_STATE.get(key);
		if (st == null) {
			if (stack == null || stack.isEmpty()) {
				// 空槽且无状态：不创建、不渲染任何东西。
				return new SlotAnim(1.0F, ItemStack.EMPTY, 1.0F, 0.0D);
			}
			st = new GrowthState();
			GROWTH_STATE.put(key, st);
		}

		if (stack == null || stack.isEmpty()) {
			// 槽位空了（拿走）：若当前还有物品且未在退场，启动退场。
			if (st.current != null && !st.current.isEmpty() && st.exiting == null) {
				st.exiting = st.current;
				st.exitTick = time;
				if (DIAG && index == RESULT_GROWTH_INDEX) {
					TemplateMod.LOGGER.warn("[DIAG] t={} pos={} RESULT 启动退场 exitTick={} 当前物={}", time, tablePos, time,
							st.exiting.getHoverName().getString());
				}
			}
			st.current = null;
		} else {
			// 槽位有物品：变化（首次/更换）时启动进入；若换成了不同物品，旧物品同时退场。
			if (st.current == null) {
				// 上一帧被读为空。可能是「提取合成结果」时原版先把结果槽清空、随后才在下一两个
				// tick 重算回同一结果（表现层槽位与配方重算之间的时序差）——这是瞬时抖动，不是
				// 真正拿走。若此刻即将退场的旧物与回来的栈是同一个、且退场刚起步（宽限窗内），
				// 则取消退场并保持原进入进度：不缩、不弹，平滑无闪烁。
				if (st.exiting != null && !st.exiting.isEmpty()
						&& ItemStack.matches(st.exiting, stack)
						&& (time + tickDelta) - st.exitTick < TRANSIENT_GRACE_TICKS) {
					if (DIAG && index == RESULT_GROWTH_INDEX) {
						TemplateMod.LOGGER.warn("[DIAG] t={} pos={} RESULT 宽限取消退场(同物回归)", time, tablePos);
					}
					st.exiting = null;
					st.current = stack.copy();
				} else {
					// 正常摆放（槽位此前确实为空/旧物已退完）或换成不同物品：新物品从 0 入场；
					// 若仍有不同旧物在退场，保持其继续缩小（双物品各播各的）。
					st.enterTick = time;
					st.current = stack.copy();
					if (DIAG && index == RESULT_GROWTH_INDEX) {
						TemplateMod.LOGGER.warn("[DIAG] t={} pos={} RESULT 空->有(非宽限/换新) enterTick重置 exitingIsNull={}",
								time, tablePos, st.exiting == null);
					}
				}
			} else if (!ItemStack.matches(st.current, stack)) {
				// 换成了不同物品：旧物品同时退场，新物品重新入场。
				if (st.current != null && !st.current.isEmpty() && st.exiting == null) {
					st.exiting = st.current;
					st.exitTick = time;
				}
				st.enterTick = time;
				st.current = stack.copy();
			} else {
				// 同一物品（count 可能不同）：同步最新数量，但不重放进入/退场动画。
				st.current = stack.copy();
			}
		}

		float ticks = (float) Math.max(1.0D, cfg.growthSeconds * 20.0D);

		// 进入系数（ease-out：先快后慢，最后减速停稳）。
		float enter = 1.0F;
		float p = ((time + tickDelta) - st.enterTick) / ticks;
		if (p < 1.0F) {
			float e = 1.0F - Math.max(0.0F, p);
			enter = 1.0F - e * e * e;
		}

		// 退场物品与系数（ease-in：先慢后快，是进入动画的镜像——倒放感，无「残留细丝」）。
		ItemStack exiting = st.exiting != null ? st.exiting : ItemStack.EMPTY;
		float exit = 1.0F;
		if (st.exiting != null) {
			float ep = ((time + tickDelta) - st.exitTick) / ticks;
			if (ep >= 1.0F) {
				st.exiting = null;
				exiting = ItemStack.EMPTY;
			} else {
				float t = Math.max(0.0F, ep);
				exit = 1.0F - t * t * t;
			}
		}

		// 退场冻结的浮动偏移：退场开始时（exitTick 那一帧的相位）的 bob——退场中的物品
		// 冻结在它被拿走时所在的高度原地缩小，而不是瞬移到基座高度再退场。结果与材料
		// 各自用各自的浮动速度/幅度（错相 WAVE 时材料按槽位 index 取值）。
		double exitBob = 0.0D;
		if (!exiting.isEmpty() && st.exiting != null) {
			exitBob = (index == RESULT_GROWTH_INDEX)
					? bobFor(cfg.floatingMode, st.exitTick * radPerTick(cfg.resultFloatSeconds),
							0, cfg.resultFloatAmplitude)
					: bobFor(cfg.floatingMode, st.exitTick * radPerTick(cfg.floatSeconds),
							index, cfg.floatAmplitude);
		}

		// 槽位空且退场结束：整体移除状态（下次放入重新生长）。
		if (st.current == null && st.exiting == null) {
			GROWTH_STATE.remove(key);
		}
		return new SlotAnim(enter, exiting, exit, exitBob);
	}

	/**
	 * 判断物品渲染是否归类为「扁平（非方块）」：与 {@link #renderItem} 的几何判定一致
	 * （模型体厚 ≤ 1/16 格 = 扁平）。扁平合成结果额外放大 {@link #RESULT_FLAT_BONUS_SCALE}。
	 * 复用 {@link #ITEM_STATE_CACHE} 避免重复建模（结果与材料共享该缓存，内容不变即命中）。
	 */
	private static boolean isFlatItem(ItemStack stack, Minecraft client,
			Entity cameraEntity, ItemDisplayContext displayContext) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		ItemStackRenderState st = ITEM_STATE_CACHE.computeIfAbsent(
				new ItemStateKey(displayContext, stack), k -> {
					ItemStackRenderState s = new ItemStackRenderState();
					client.getItemModelResolver()
							.updateForNonLiving(s, stack, displayContext, cameraEntity);
					return s;
				});
		if (st.isEmpty()) {
			return false;
		}
		return st.getModelBoundingBox().getZsize() <= 0.0625D;
	}

	/**
	 * 在工作台局部坐标 {@code (x, y, z)} 处以 3D 形式渲染一个物品。
	 *
	 * @param yawAngle 绕竖直轴 Y 的旋转角度（度）；0 表示不旋转
	 * @param layFlat  为 {@code true} 时用于「平摊」可视化方式：按模型真实边界落台贴合（解决门/栅栏等
	 *                 悬空）。3D 方块（体厚 &gt; 1/16 格）按正常放置姿态站立、顶面朝上，2D 精灵绕 X −90°
	 *                 平放、正面朝上。
	 *                 为 {@code false} 时保持直立自然渲染（悬浮/结果）。
	 */
	private static void renderItem(PoseStack matrices, SubmitNodeCollector collector, ItemStack stack,
			double x, double y, double z, float scale, float yawAngle,
			int light, Minecraft client, Entity cameraEntity, boolean layFlat,
			ItemDisplayContext displayContext) {
		// 先构建物品渲染状态：平摊模式需要真实模型边界计算落台高度与旋转方向（先于矩阵压栈，
		// 渲染状态与位置无关，此处构建即可）。
		// 渲染优化（物品模型更新缓存）：内容/显示上下文未变时复用缓存状态，跳过
		// updateForNonLiving（每帧每物品的模型查询+图层决策是大头）。缩放/旋转/位置
		// 全部在下方 matrices 上做，不入 ItemStackRenderState，故复用安全。
		ItemStackRenderState itemState = ITEM_STATE_CACHE.computeIfAbsent(
				new ItemStateKey(displayContext, stack), k -> {
					ItemStackRenderState st = new ItemStackRenderState();
					client.getItemModelResolver()
							.updateForNonLiving(st, stack, displayContext, cameraEntity);
					return st;
				});
		if (itemState.isEmpty()) {
			return;
		}

		// 尺寸分类按模型几何而非物品类：3D 方块（体厚 > 1/16 格，草方块/圆石/炉子等）的 FIXED
		// 显示缩放为 0.5 → 保持原尺寸；扁平精灵（厚度≈0，含门这类 BlockItem 的方块物品）的 FIXED
		// 为 1.0 → 额外乘 FLAT_ITEM_EXTRA_SCALE（0.5）使两者最终显示同大（1.0×0.5 == 0.5×1.0）。
		// 此前按 {code instanceof BlockItem} 分类：门是 BlockItem 会走方块分支、按 1.0×scale 满格
		// 渲染而过大的根因（历次调系数无效的原由）。
		AABB box = itemState.getModelBoundingBox();
		boolean flatCube = box.getZsize() > 0.0625D;
		float effectiveScale = flatCube ? scale : scale * FLAT_ITEM_EXTRA_SCALE;

		// 平摊落台：3D 方块按正常放置姿态站立在台面——不绕 X 躺平，底边（模型最小 Y）对齐台面顶
		// （y+1 = 1.0），顶面朝上、正面朝向面板/开桌方向；2D 精灵（厚度≈0）躺平、平面即台高，无需下垫。
		float pivotY = (float) y;
		if (layFlat) {
			pivotY = flatCube
					? (float) (TABLE_TOP_Y - effectiveScale * box.minY)
					: (float) TABLE_TOP_Y;
		}

		matrices.pushPose();
		try {
			matrices.translate(x, pivotY, z);

			if (layFlat && !flatCube) {
				// 平摊旋转（方向由 JOML 实测验证）：仅 2D 精灵绕 X −90° 躺平、正面朝上；
				// 3D 方块保持正常放置姿态（顶面朝上），不参与躺平旋转。
				matrices.mulPose(Axis.XP.rotation(
						-90.0F * Mth.DEG_TO_RAD));
			}

			if (yawAngle != 0.0F) {
				// 绕竖直轴缓慢旋转（用于合成结果）。rotation 以弧度计，先由角度换算。
				matrices.mulPose(Axis.YP.rotation(yawAngle * Mth.DEG_TO_RAD));
			}

			matrices.scale(effectiveScale, effectiveScale, effectiveScale);

			// 提交物品的 3D 渲染状态（原版 1.21.11 ItemModel 管线）。
			// overlay 使用 DEFAULT_UV（采样纯白纹素）；若传 0 会采样到 overlay 纹理
			// 左上角的红色区域（alpha≈0.7），entity 着色器会将其混入颜色导致材质变暗发红。
			itemState.submit(matrices, collector, light, OverlayTexture.NO_OVERLAY, 0);
		} finally {
			matrices.popPose();
		}
	}

}