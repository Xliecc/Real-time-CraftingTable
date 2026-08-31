package com.example.network;

import com.example.TemplateMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * 服务端 → 客户端：某工作台的「最后操作者」同步（开桌方位扇区 + 最后操作者 UUID）。
 *
 * <p>发送时机：玩家开桌（构造真实 {@code CraftingMenu}）时服务端计算该玩家
 * 相对工作台的 90° 扇形，连同其 UUID 一起广播给追踪该区块的所有玩家（含开桌者本人）——
 * 客户端据此：
 * <ul>
 *   <li>「不随玩家旋转」档：直接用广播的扇区（开桌才切换方向，之后锁定不动）；</li>
 *   <li>「跟随玩家旋转」档：用 UUID 在本地世界里找到最后操作者实体，实时读取其方位
 *       （客户端本地跟随，无需服务端持续广播）；实体不在视野/维度时回退到广播扇区。</li>
 * </ul>
 * 玩家加入服务器时也补发当前全部记录（JOIN 补发），保证后加入者渲染保留预览时方向正确。
 *
 * <p>单机（集成服务器）下服务端已直写 {@link com.example.crafting.TableFacing}，
 * 此包为幂等重复、无害。
 */
public record CraftingTableFacingS2CPacket(BlockPos pos, int sector, UUID operatorUuid) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CraftingTableFacingS2CPacket> TYPE =
			new CustomPacketPayload.Type<>(TemplateMod.id("crafting_table_facing"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CraftingTableFacingS2CPacket> STREAM_CODEC =
			StreamCodec.of(
					(buf, pkt) -> {
						buf.writeBlockPos(pkt.pos);
						buf.writeInt(pkt.sector);
						buf.writeUUID(pkt.operatorUuid);
					},
					buf -> new CraftingTableFacingS2CPacket(buf.readBlockPos(), buf.readInt(), buf.readUUID()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
