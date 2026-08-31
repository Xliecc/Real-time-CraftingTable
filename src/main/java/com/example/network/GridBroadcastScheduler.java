package com.example.network;

import com.example.crafting.CraftingGridStorage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务端「保留记录广播合并器」（性能优化 P0-2）：
 * 同一 tick 内同一工作台位置的多次内容变化只广播一次，tick 末尾统一 flush。
 *
 * <p>背景：原 {@code syncLiveGrid} 在每次 {@code onContentChanged} 都立即
 * {@link CraftingPreviewNetworking#broadcastStored}——放 9 格 = 9 次全量 JSON 编码
 * （RegistryOps 编码 10 个 ItemStack）+ 9 次发给区块内所有追踪者。合并后：
 * <ul>
 *   <li>{@link #mark}：编辑时只登记 dirty 位置（Set 天然去重，同 tick 多次编辑只留一条）；</li>
 *   <li>{@link #flush}：tick 末对每个 dirty 位置从<b>权威缓存</b>（{@link CraftingGridStorage#peek}）
 *       读最新内容广播<b>一次</b>——权威缓存由 {@code storeMemory} 每次编辑即时更新，故
 *       flush 读到的一定是最新状态；延迟最多 1 tick（50ms），玩家无感知。</li>
 * </ul>
 *
 * <p>线程约定：仅在服务端主线程访问（syncLiveGrid / END_SERVER_TICK），无需同步。
 * 维度世界对象在服务端常驻（MC 服务端所有维度同时加载），按 ServerLevel 分组安全。
 */
public final class GridBroadcastScheduler {

	private static final Map<ServerLevel, Set<BlockPos>> DIRTY = new HashMap<>();

	private GridBroadcastScheduler() {
	}

	/** 登记某位置内容已变化（同一 tick 内重复登记自动去重）。 */
	public static void mark(ServerLevel world, BlockPos pos) {
		DIRTY.computeIfAbsent(world, k -> new HashSet<>()).add(pos);
	}

	/** tick 末尾：把本 tick 内所有 dirty 位置的最新权威内容各广播一次，然后清空。 */
	public static void flush(ServerLevel world) {
		Set<BlockPos> dirty = DIRTY.remove(world);
		if (dirty == null || dirty.isEmpty()) {
			return;
		}
		String dimensionKey = world.dimension().identifier().toString();
		for (BlockPos pos : dirty) {
			CraftingGridStorage.GridData data = CraftingGridStorage.peek(world, pos);
			if (data == null) {
				// 记录已被移除（如同 tick 内工作台被破坏）：方块消失后客户端渲染器
				// 按方块类型自行清理（FACING_STATE/GROWTH_STATE 过滤非工作台位置），无需广播。
				continue;
			}
			CraftingPreviewNetworking.broadcastStored(world, pos, dimensionKey,
					data.inputs(), data.result());
		}
	}

	/** 调试/联调：当前待广播位置数。 */
	public static int pendingCount() {
		int n = 0;
		for (Set<BlockPos> s : DIRTY.values()) {
			n += s.size();
		}
		return n;
	}
}