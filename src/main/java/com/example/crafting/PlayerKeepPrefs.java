package com.example.crafting;

import com.example.config.PreviewConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端按玩家（UUID）记录的「关闭工作台后保留材料」偏好。
 *
 * <p>值由客户端经 {@link com.example.network.KeepPreferenceC2SPacket} 上报（客户端读自己
 * 的 Config.json）。关桌拦截（{@code CraftingScreenHandlerMixin.keepGridOnClose}）据此用
 * <b>关闭者本人的偏好</b>判定是否保留，而非服务端全局配置——修复「房主关掉 keep 后其他
 * 玩家（keep 开启）关桌不保留」。
 *
 * <p>取不到（玩家尚未上报/旧版本客户端）时回退服务端全局配置 {@link PreviewConfig#get()}，
 * 与旧行为一致。仅在服务端线程访问，用 {@link ConcurrentHashMap} 防御（join/接收线程）。
 */
public final class PlayerKeepPrefs {

	private static final Map<UUID, Boolean> PREFS = new ConcurrentHashMap<>();

	private PlayerKeepPrefs() {
	}

	/** 记录某玩家的 keep 偏好（幂等覆盖）。 */
	public static void set(UUID player, boolean keep) {
		PREFS.put(player, keep);
	}

	/** 清除某玩家的偏好（玩家离开时调用，防内存堆积）。 */
	public static void remove(UUID player) {
		PREFS.remove(player);
	}

	/**
	 * 取某玩家的 keep 偏好；未上报时回退服务端全局配置（兼容旧客户端/未上报的玩家）。
	 * 全局配置缺失/为 null 时按默认「关闭」（false）。
	 */
	public static boolean getOrDefault(UUID player) {
		Boolean v = PREFS.get(player);
		if (v != null) {
			return v;
		}
		Boolean server = PreviewConfig.get().keepItemsWhenClosed;
		return server != null && server;
	}
}
