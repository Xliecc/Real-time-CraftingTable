package com.example.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 合成预览的运行时配置（无 Cloth Config 依赖，纯 Gson 持久化）。
 *
 * <p>由 Mod Menu 的配置页（PreviewModMenu，client 侧）编辑，{@code save()} 后
 * 渲染器每帧读取最新值，无需重启即可生效。配置文件位于 {@code config/real-time-crafting-table.json}。
 *
 * <p><b>双端各自持有独立配置</b>（自联机支持起从 client 源集移到 common）：渲染外观类
 * 参数由各自客户端读自己的配置；「工作台作为容器」等行为语义以<b>服务端</b>配置为准
 * （存储/恢复/广播都发生在服务端拦截处）。两侧配置不一致时行为见 PROGRESS 决策记录。
 */
public final class PreviewConfig {

	/** 材料浮动模式：无浮动 / 逐槽位错开的波浪 / 全部同步起伏。 */
	public enum FloatingMode {
		NONE, WAVE, SYNC
	}

	/**
	 * 预览朝向模式：跟随玩家旋转（整块预览面板绕工作台中心竖直轴平滑转向玩家站立方向）/
	 * 不随玩家旋转（面板朝向 = 开桌瞬间玩家所在 90° 正方向，锁定不动，不随走动/转身转动）。
	 */
	public enum FacingMode {
		FOLLOW_PLAYER, FIXED
	}

	/**
	 * 材料可视化方式：悬浮（台面上方 3D 分层浮动，默认）/ 平摊（参考 Visual Workbench，材料平铺在
	 * 台面上 3×3 表位、静止不动；结果物仍悬浮自转，不受此项影响）。
	 */
	public enum IngredientStyle {
		FLOAT, FLAT
	}

	/**
	 * 模组总开关：关闭后完全不渲染合成预览（AFTER_ENTITIES 与阴影 pass 均跳过），
	 * 网络接收照常（重新开启后立即恢复显示）。默认开启。
	 */
	public boolean enabled = true;

	/** 材料可视化方式：悬浮/平摊，默认悬浮（保持现状，不会无故改变已有画面）。 */
	public IngredientStyle ingredientStyle = IngredientStyle.FLOAT;

	/** 浮动模式。 */
	public FloatingMode floatingMode = FloatingMode.WAVE;

	/** 预览朝向模式：跟随玩家旋转时整块预览面板朝玩家方向；不随玩家旋转时朝向开桌方向。默认不随玩家旋转（PCL2 当前调定值，2026-08-17 23:38 同步）。 */
	public FacingMode facingMode = FacingMode.FIXED;

	/**
	 * 跟随玩家旋转时，跨扇形转场动画的时长（秒）。越小转场越快（0.6 = 0.6 秒，PCL2 当前调定值，
	 * 2026-08-17 23:38 同步）。仅在朝向模式为「跟随玩家旋转」时生效；不跟随模式下面板不转，此值无影响。
	 */
	public double facingAnimationSeconds = 0.6;

	/** 合成结果旋转一圈所需秒数（越大越慢）。默认 8（2026-08-20 用户调定为 8 并设为新默认）。 */
	public int rotationSeconds = 8;

	/** 材料基座高于工作台顶面（y+1）的距离，单位格。默认 0.09（2026-08-19 用户调定）。 */
	public double floatHeight = 0.09;

	/** 材料预览的缩放（未叠加物品自身 FIXED 变换；方块类约 0.1 格、扁平类约 0.16 格）。 */
	public double materialScale = 0.24;

	/** 合成结果的缩放（未叠加物品自身 FIXED 变换；方块类约 0.25 格、扁平类约 0.40 格）。
	 * 默认 1.0（2026-08-19 用户调定）。 */
	public double resultScale = 1.0;

	/** 材料之间的水平间距（格）。 */
	public double slotSpacing = 0.187;

	/** 材料浮动速度：一整个来回（上→下→上）所需秒数（越小越快）。默认 3（2026-08-19 用户调定）。 */
	public double floatSeconds = 3;

	/** 结果物浮动速度：一整个来回所需秒数，与材料分开调节。默认 4（2026-08-19 用户调定）。 */
	public double resultFloatSeconds = 4;

	/** 材料浮动幅度（格，峰值偏移量）。 */
	public double floatAmplitude = 0.004;

	/** 合成结果浮动幅度（格，峰值偏移量），与材料分开调节。默认 0.03（2026-08-20 用户调定并设为新默认）。 */
	public double resultFloatAmplitude = 0.03;

	/** 结果物基座与材料基座的垂直间距（格），即结果的浮空高度。默认 0.22（2026-08-20 用户调定，滑条居中）。 */
	public double resultHeightGap = 0.22;

	/**
	 * 生长动画总开关：材料/结果<b>出现</b>（放入新物品、更换成不同物品，或槽位清空后再放入）时，
	 * 从零放大到配置尺寸的弹出动画；关闭则直接以完整大小显示。默认开启。
	 */
	public boolean growthEnabled = true;

	/** 生长动画时长（秒，越小越干脆；0.3 = 0.3 秒）。 */
	public double growthSeconds = 0.3;

	/**
	 * 预览渲染距离（格）：超过此距离的工作台预览<b>不构建渲染状态</b>（远处工作台每帧
	 * 的动画推进/状态构建/方位计算全部跳过，性能优化 P2-1）。0 = 不限（保持全图渲染）。
	 * 默认 64 格——足够「走近看」，同时避免 chunk 已加载但很远的工作台白算。
	 */
	public int renderDistance = 64;

	/**
	 * 「工作台作为容器」：开启后工作台像容器一样把合成网格里的物品按维度+坐标归档到本 mod 的
	 * 持久化存储，关闭界面不退还玩家、下次打开同一位置的工作台仍可查看/操作；关闭则保持原版
	 * 逻辑（物品退还玩家/掉落）。用装箱类型以便区分「配置缺失」与「显式 false」。
	 * 联机时以服务端此开关为准（存储/恢复/广播都在服务端拦截处执行）。
	 * 默认关闭（false，2026-08-20 用户要求改为默认否）。
	 */
	public Boolean keepItemsWhenClosed = false;

	private static final int MIN_ROTATION_SECONDS = 1;
	private static final int MAX_ROTATION_SECONDS = 19;
	private static final double MIN_FLOAT_HEIGHT = 0.01;
	private static final double MAX_FLOAT_HEIGHT = 0.17;
	private static final double MIN_MATERIAL_SCALE = 0.02;
	private static final double MAX_MATERIAL_SCALE = 0.46;
	private static final double MIN_RESULT_SCALE = 0.20;
	private static final double MAX_RESULT_SCALE = 1.80;
	private static final double MIN_SLOT_SPACING = 0.047;
	private static final double MAX_SLOT_SPACING = 0.327;
	private static final double MIN_GROWTH_SECONDS = 0.05;
	private static final double MAX_GROWTH_SECONDS = 0.55;
	private static final double MIN_FLOAT_SECONDS = 1;
	private static final int MAX_FLOAT_SECONDS = 7;
	private static final double MIN_FLOAT_AMPLITUDE = 0.0;
	private static final double MAX_FLOAT_AMPLITUDE = 0.008;
	private static final double MIN_RESULT_FLOAT_AMPLITUDE = 0.002;
	private static final double MAX_RESULT_FLOAT_AMPLITUDE = 0.058;
	private static final double MIN_RESULT_HEIGHT_GAP = 0.05;
	private static final double MAX_RESULT_HEIGHT_GAP = 0.45;
	private static final double MIN_FACING_ANIMATION_SECONDS = 0.1;
	private static final double MAX_FACING_ANIMATION_SECONDS = 1.1;
	private static final int MIN_RENDER_DISTANCE = 0;
	private static final int MAX_RENDER_DISTANCE = 128;

	// ⚠️ 静态初始化顺序：GSON 必须先于 INSTANCE 声明！
	// 构造 INSTANCE 时会调用 load()，而 load() 要用 GSON.fromJson；若 GSON 声明在后面，
	// 此刻它还是 null → load() 抛 NPE 被 catch 吞掉 → 配置永远重置为默认（重启丢失）。
	// 曾踩坑（2026-08-21），勿再调回顺序。
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final PreviewConfig INSTANCE = new PreviewConfig();

	private PreviewConfig() {
		load();
	}

	public static PreviewConfig get() {
		return INSTANCE;
	}

	private Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("real-time-crafting-table.json");
	}

	/** 从磁盘加载配置；文件缺失或损坏时保留默认值。 */
	public synchronized void load() {
		Path path = file();
		if (!Files.exists(path)) {
			return;
		}
		try {
			String raw = Files.readString(path);
			PreviewConfig loaded = GSON.fromJson(raw, PreviewConfig.class);
			if (loaded == null) {
				return;
			}
			if (loaded.floatingMode != null) {
				this.floatingMode = loaded.floatingMode;
			}
			if (loaded.facingMode != null) {
				this.facingMode = loaded.facingMode;
			}
			if (loaded.ingredientStyle != null) {
				this.ingredientStyle = loaded.ingredientStyle;
			}
			if (loaded.keepItemsWhenClosed != null) {
				this.keepItemsWhenClosed = loaded.keepItemsWhenClosed;
			}
			// 总开关：Gson 对缺失的布尔字段给 false，无法区分「显式 false」与「旧版无此键」，
			// 故按原文是否含 "enabled" 键判定（旧配置视为默认 true）。
			this.enabled = raw.contains("\"enabled\"") ? loaded.enabled : true;
			// 生长动画开关同理：旧配置无此键时默认开启（动画默认打开，不改变旧配置行为之外的期望）。
			this.growthEnabled = raw.contains("\"growthEnabled\"") ? loaded.growthEnabled : true;
			this.rotationSeconds = clampInt(loaded.rotationSeconds, MIN_ROTATION_SECONDS, MAX_ROTATION_SECONDS);
			this.floatHeight = clampDouble(loaded.floatHeight, MIN_FLOAT_HEIGHT, MAX_FLOAT_HEIGHT);
			this.materialScale = clampDouble(loaded.materialScale, MIN_MATERIAL_SCALE, MAX_MATERIAL_SCALE);
			this.resultScale = clampDouble(loaded.resultScale, MIN_RESULT_SCALE, MAX_RESULT_SCALE);
			this.slotSpacing = clampDouble(loaded.slotSpacing, MIN_SLOT_SPACING, MAX_SLOT_SPACING);
			this.floatSeconds = clampDouble(loaded.floatSeconds, MIN_FLOAT_SECONDS, MAX_FLOAT_SECONDS);
			this.resultFloatSeconds = clampDouble(loaded.resultFloatSeconds, MIN_FLOAT_SECONDS, MAX_FLOAT_SECONDS);
			this.floatAmplitude = clampDouble(loaded.floatAmplitude, MIN_FLOAT_AMPLITUDE, MAX_FLOAT_AMPLITUDE);
			this.resultFloatAmplitude = clampDouble(loaded.resultFloatAmplitude,
					MIN_RESULT_FLOAT_AMPLITUDE, MAX_RESULT_FLOAT_AMPLITUDE);
			this.resultHeightGap = clampDouble(loaded.resultHeightGap,
					MIN_RESULT_HEIGHT_GAP, MAX_RESULT_HEIGHT_GAP);
			this.growthSeconds = clampDouble(loaded.growthSeconds, MIN_GROWTH_SECONDS, MAX_GROWTH_SECONDS);
			this.facingAnimationSeconds = clampDouble(loaded.facingAnimationSeconds,
					MIN_FACING_ANIMATION_SECONDS, MAX_FACING_ANIMATION_SECONDS);
			this.renderDistance = clampInt(loaded.renderDistance, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
		} catch (Exception e) {
			// 配置损坏：回退默认值
		}
	}

	/** 写入磁盘。 */
	public synchronized void save() {
		try {
			Path path = file();
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			// 忽略写入失败（不影响游戏运行）
		}
	}

	private static int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clampDouble(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}