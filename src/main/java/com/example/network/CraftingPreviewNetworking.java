package com.example.network;

import com.google.gson.JsonElement;

import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 合成预览的服务端发包入口（S2C）。
 *
 * <p>单机（集成服务器）与联机（LAN / 独立服务器）走完全相同的 Fabric Networking 通道——
 * 集成服务器对本地玩家同样存在一条内部网络连接，{@code ServerPlayNetworking.send} 对两者
 * 行为一致，无需分支处理。
 */
public final class CraftingPreviewNetworking {

	private CraftingPreviewNetworking() {
	}

	/**
	 * 把「刚打开的工作台坐标」发给开桌玩家本人（客户端收到后写入 OpenTableTracker，
	 * 供渲染器定位实时 GUI 预览）。应与原版 OpenScreen 包相邻送达。
	 */
	public static void sendOpen(ServerPlayer player, BlockPos pos, String dimensionKey) {
		ServerPlayNetworking.send(player, new CraftingTableOpenS2CPacket(pos, dimensionKey));
	}

	/**
	 * 把某工作台位置的保留记录（或全空记录 = 清除）广播给正在追踪该区块的所有玩家。
	 * 追踪者通常包含刚关桌的玩家本人（关桌后预览无缝出现的关键路径）。
	 *
	 * <p>物品先经 {@code world} 的注册表 ops 编码为 JSON 字符串（完整保留组件；
	 * JSON 按 ResourceKey 查找、不依赖 RegistryEntry 对象身份，兼容 enchantment-table
	 * 等替换/包装附魔注册表的整合包环境，避免 PACKET_CODEC 编码附魔时
	 * 「Can't find id for Reference」断线）。
	 */
	public static void broadcastStored(ServerLevel world, BlockPos pos, String dimensionKey,
			List<ItemStack> inputs, ItemStack result) {
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, world.registryAccess());
		CraftingGridStoredS2CPacket payload = CraftingGridStoredS2CPacket.fromGridData(
				ops, pos, dimensionKey, inputs, result);
		for (ServerPlayer watcher : PlayerLookup.tracking(world, pos)) {
			ServerPlayNetworking.send(watcher, payload);
		}
	}

	/**
	 * 把某工作台的「最后操作者」信息（开桌方位扇区 + 操作者 UUID）广播给正在追踪该区块的
	 * 所有玩家（含开桌者本人）。客户端据此把预览面板朝向「上一个操作工作台的人」；
	 * FOLLOW 档再用 UUID 在本地找操作者实体做客户端本地实时跟随。
	 */
	public static void broadcastFacing(ServerLevel world, BlockPos pos, int sector, UUID operatorUuid) {
		CraftingTableFacingS2CPacket payload =
				new CraftingTableFacingS2CPacket(pos, sector, operatorUuid);
		for (ServerPlayer watcher : PlayerLookup.tracking(world, pos)) {
			ServerPlayNetworking.send(watcher, payload);
		}
	}
}