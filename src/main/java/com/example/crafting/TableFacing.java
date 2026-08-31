package com.example.crafting;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个工作台的「最后操作者方位」扇区（0/1/2/3 → 0/90/180/270°）共享状态。
 *
 * <p>联机视觉方向需求：多个玩家看到同一张工作台的预览时，面板朝向应基于<b>上一个
 * 操作该工作台的人</b>（最后一次开桌者的站立方位），而不是各自面向自己——这样所有
 * 玩家的视角一致（世界观感更自然，类似 Visual Workbench 的共享朝向）。
 *
 * <p>访问矩阵（与 {@link CraftingGridStorage} 同一套三 JVM 场景）：
 * <ul>
 *   <li>单机（集成服务器）：服务端线程开桌时直写（与客户端同 JVM），渲染线程读——无需网络包；</li>
 *   <li>独立服务器 JVM：只有服务端直写（无人读）；</li>
 *   <li>远程客户端 JVM：由 {@code CraftingTableFacingS2CPacket} 网络接收器写入（唯一来源）。</li>
 * </ul>
 * 单机下服务端直写与 S2C 包（广播含宿主）幂等重复、无害。
 *
 * <p>键只含位置（BlockPos），不区分维度：渲染器在按当前维度过滤后的工作台上读取；
 * 不同维度同坐标的碰撞在实践中可忽略（原版也以坐标引用方块）。
 */
public final class TableFacing {

		/** 无记录（未知）时返回 {@code -1}。 */
	public static final int UNKNOWN = -1;

	/** 一个完整圆周的扇区数（90° 一档，共 4 档）。 */
	public static final int SECTOR_COUNT = 4;
	public static final double SECTOR_DEGREES = 360.0D / SECTOR_COUNT;

	private static final Map<BlockPos, Integer> SECTOR = new ConcurrentHashMap<>();

	/** 每个工作台的「最后操作者」（最后一次打开该工作台的玩家 UUID）。 */
	private static final Map<BlockPos, UUID> LAST_OPERATOR = new ConcurrentHashMap<>();

	private TableFacing() {
	}

	/**
	 * 开桌时一次性写入「方向 + 最后操作者」两个状态（服务端调用；客户端网络接收器也调用）。
	 * 方向无效（{@code <0} 或 {@code >=4}）时忽略方向仅记操作者。
	 */
	public static void setFacing(BlockPos pos, int sector, UUID player) {
		if (sector >= 0 && sector < SECTOR_COUNT) {
			SECTOR.put(pos, sector);
		}
		if (player != null) {
			LAST_OPERATOR.put(pos, player);
		}
	}

	/** 某工作台最后操作者 UUID（无记录返回 {@code null}）。 */
	public static UUID getOperator(BlockPos pos) {
		return LAST_OPERATOR.get(pos);
	}

	/**
	 * 把实体（玩家）相对工作台中心的方位量化到最近 90° 正方向扇区（0/1/2/3 → 0/90/180/270°）。
	 * 公式与客户端渲染器 {@code CraftingPreviewRenderer.computePlayerSector} 一致：
	 * {@code floor(φ/90 + 0.5) mod 4}（最近取整，切换边界在两相邻正方向的等距中点），
	 * 保证服务端记录与客户端渲染口径一致。
	 */
	public static int computeSector(double entityX, double entityZ, BlockPos tablePos) {
		double dx = entityX - (tablePos.getX() + 0.5D);
		double dz = entityZ - (tablePos.getZ() + 0.5D);
		float targetDeg = (float) Math.toDegrees(Math.atan2(dx, dz));
		float normDeg = (targetDeg % 360.0F + 360.0F) % 360.0F;
		return Math.floorMod((int) Math.floor(normDeg / SECTOR_DEGREES + 0.5F), SECTOR_COUNT);
	}

	/** 读取某工作台最后操作者扇区；未知返回 {@link #UNKNOWN}。 */
	public static int get(BlockPos pos) {
		Integer v = SECTOR.get(pos);
		return v == null ? UNKNOWN : v;
	}

	/** 全部条目（用于玩家加入时的初始补发；key 为不可变 BlockPos）。 */
	public static Map<BlockPos, Integer> all() {
		return new java.util.HashMap<>(SECTOR);
	}

	/** 移除某工作台的记录（工作台被拆 / 清空存储时调用，可选）。 */
	public static void remove(BlockPos pos) {
		SECTOR.remove(pos);
	}
}
