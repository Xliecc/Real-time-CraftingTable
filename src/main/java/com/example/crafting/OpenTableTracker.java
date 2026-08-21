package com.example.crafting;

import net.minecraft.util.math.BlockPos;

/**
 * 记录「当前正在使用的 3×3 工作台」的方块位置，供渲染线程在 GUI 打开时定位预览。
 *
 * <p><b>为什么不能直接读客户端 handler 的 {@code ScreenHandlerContext}：</b>客户端打开工作台
 * 界面时，{@code CraftingScreen} 里的 handler 是网络镜像——由
 * {@code ScreenHandlerType.create(syncId, inventory)} 用 {@code ScreenHandlerContext.EMPTY}
 * 构造（OpenScreen 数据包只带 syncId/标题、不带方块坐标），因此客户端的 {@code context}
 * 恒为空，读不到坐标（23:24→23:44 实测 GUI 预览不渲染）。
 *
 * <p><b>正确来源 = 服务端的真实 context，且分两条到达路径（联机支持起）：</b>
 * <ul>
 *   <li>单机（集成服务器与客户端同 JVM）：服务端构造 handler 时由 mixin 直接写入本类
 *       （服务端线程写、渲染线程读，{@code volatile} 保证可见性）——原有行为不变；</li>
 *   <li>联机（LAN / 独立服务器）：服务端构造 handler 时经
 *       {@code CraftingTableOpenS2CPacket} 把坐标发给开桌玩家，客户端接收器写入本类
 *       （每个客户端只跟踪自己打开的那张桌，单例足够）。两条路径幂等，单机下可能双写
 *       同一坐标，无害。</li>
 * </ul>
 *
 * <p>打开时设置、关闭时清空；渲染器仅在 {@code CraftingScreen} 打开时才读取，
 * 并在读取后校验该位置仍是工作台。
 */
public final class OpenTableTracker {

	/** 当前打开的工作台位置；无打开界面时（关闭后已清空）为 {@code null}。 */
	private static volatile BlockPos current;

	/** 当前打开工作台所在维度的 id 字符串（如 {@code minecraft:overworld}），与 {@link #current} 一并写入。 */
	private static volatile String currentDimensionKey;

	private OpenTableTracker() {
	}

	/** 服务端构造真实 handler 时写入（mixin 直写，或客户端网络接收器收到开桌包后写入）。 */
	public static void set(BlockPos pos, String dimensionKey) {
		current = pos;
		currentDimensionKey = dimensionKey;
	}

	/** 关闭工作台界面时清空（mixin 在 {@code onClosed} 无条件调用，客户端/服务端任一侧先执行）。 */
	public static void clear() {
		current = null;
		currentDimensionKey = null;
	}

	/** 渲染线程读取：当前打开的 handler 对应的工作台位置；无则为 {@code null}。 */
	public static BlockPos get() {
		return current;
	}

	/**
	 * 当前打开工作台所在维度的 id 字符串；供关闭时把网格内容写入存储缓存
	 * （{@link CraftingGridStorage#clientApplyIfChanged}）按维度建键用——客户端镜像 handler 的
	 * {@code context} 为 EMPTY 拿不到 world，坐标与维度都来自服务端（直写或网络包）。
	 */
	public static String getDimensionKey() {
		return currentDimensionKey;
	}
}
