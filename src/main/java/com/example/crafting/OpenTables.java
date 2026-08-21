package com.example.crafting;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端登记「哪些工作台正被哪些玩家打开」（按方块位置 → 打开者 UUID 集合）。
 *
 * <p><b>共享合成网格语义</b>（用户需求：双方 GUI 完全实时同步）：
 * <ul>
 *   <li>打开即登记（任意玩家打开某工作台都加入集合）；</li>
 *   <li>网格内容由服务端权威缓存（{@link CraftingGridStorage}）持有，编辑广播后写回
 *       所有打开该桌玩家的 handler 槽位；</li>
 *   <li>关桌时若<b>还有他人打开</b>：只清空自己的镜像槽、不归还、不动权威（防物品复制）；
 *       最后一个关闭者才走 keep 保留/归还语义；</li>
 *   <li>方向「最后实际操作者」由 {@link TableFacing} 单独维护（onContentChanged 更新）。</li>
 * </ul>
 *
 * <p>单机只有一名玩家，恒为「最后关闭者」，行为与原单机版一致（零回归）。
 * 仅服务端线程访问（构造 / onClosed 拦截内），用 {@link ConcurrentHashMap} 防御跨线程。
 */
public final class OpenTables {

	private static final Map<BlockPos, Set<UUID>> OPEN = new ConcurrentHashMap<>();

	private OpenTables() {
	}

	/** 打开时登记该玩家（幂等；多人共享同一工作台时每人各占一条）。 */
	public static void add(BlockPos pos, UUID player) {
		OPEN.computeIfAbsent(pos.toImmutable(), k -> ConcurrentHashMap.newKeySet()).add(player);
	}

	/** 关闭时移除该玩家登记；若移除后该位置无人打开则整条删除。 */
	public static void remove(BlockPos pos, UUID player) {
		Set<UUID> set = OPEN.get(pos);
		if (set == null) {
			return;
		}
		set.remove(player);
		if (set.isEmpty()) {
			OPEN.remove(pos);
		}
	}

	/** 该位置当前打开者集合（快照，可能为空集）。 */
	public static Set<UUID> getPlayers(BlockPos pos) {
		Set<UUID> set = OPEN.get(pos.toImmutable());
		return set == null ? Set.of() : new HashSet<>(set);
	}

	/** 该位置当前是否有<b>其他</b>打开者（排除 {@code player}）。 */
	public static boolean hasOtherOpeners(BlockPos pos, UUID player) {
		Set<UUID> set = OPEN.get(pos.toImmutable());
		if (set == null) {
			return false;
		}
		for (UUID u : set) {
			if (!u.equals(player)) {
				return true;
			}
		}
		return false;
	}

	/** 工作台被挖掉/替换时清理该位置的全部登记（无论操作者是谁）。 */
	public static void removeBlock(BlockPos pos) {
		OPEN.remove(pos);
	}

	/** 全部当前占用条目（快照，供调试/联调）。 */
	public static Map<BlockPos, Set<UUID>> entries() {
		Map<BlockPos, Set<UUID>> out = new java.util.HashMap<>();
		for (Map.Entry<BlockPos, Set<UUID>> e : OPEN.entrySet()) {
			out.put(e.getKey(), new HashSet<>(e.getValue()));
		}
		return out;
	}
}