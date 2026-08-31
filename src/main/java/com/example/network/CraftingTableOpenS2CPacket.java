package com.example.network;

import com.example.TemplateMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：玩家打开工作台时，告知客户端该工作台的世界坐标与维度。
 *
 * <p>取代单机方案里 {@code OpenTableTracker} 仅靠 volatile static 直写的路径——联机环境
 * （LAN / 独立服务器）服务端与客户端不在同一 JVM，工作台坐标必须经网络同步：服务端在
 * 构造真实 {@code CraftingMenu}（真实 {@code ContainerLevelAccess}）时把坐标
 * 发给开桌玩家；客户端收到后写入 {@code OpenTableTracker}，供渲染器定位实时 GUI 预览。
 * 单机（集成服务器）下服务端仍会直写 tracker，本包与其幂等重复、无害。
 */
public record CraftingTableOpenS2CPacket(BlockPos pos, String dimensionKey) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CraftingTableOpenS2CPacket> TYPE =
			CustomPacketPayload.createType(TemplateMod.MOD_ID + ":crafting_table_open");

	/** 手写编解码（BlockPos + UTF 字符串）。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, CraftingTableOpenS2CPacket> STREAM_CODEC =
			StreamCodec.of(
					(buf, pkt) -> {
						buf.writeBlockPos(pkt.pos);
						buf.writeUtf(pkt.dimensionKey);
					},
					buf -> new CraftingTableOpenS2CPacket(buf.readBlockPos(), buf.readUtf()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
