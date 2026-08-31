package com.example.network;

import com.example.TemplateMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：告知本玩家的「关闭工作台后保留材料」偏好（客户端自身配置）。
 *
 * <p>背景：{@code keepItemsWhenClosed} 是<b>每客户端各自的配置</b>（Config.json 按端独立），
 * 而共享合成网格的关桌保留语义在<b>服务端</b>拦截处执行，服务端只能读到「服务端自己那份」
 * 配置（单机集成服务器 = 房主的配置；独立服务器 = 服务器文件的配置）。这会导致：房主关掉
 * keep 后，其他玩家（keep 开启）关桌时被服务端误用房主的 false 而不保留。
 *
 * <p>本包让客户端把自己的 keep 偏好上报服务端；服务端按玩家 UUID 记录（见
 * {@link com.example.crafting.PlayerKeepPrefs}），关桌拦截时用「关闭者本人的偏好」判定，
 * 而非服务端全局配置。客户端在加入时与每次打开工作台时发送（保持最新）。
 */
public record KeepPreferenceC2SPacket(boolean keepItemsWhenClosed) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<KeepPreferenceC2SPacket> TYPE =
			CustomPacketPayload.createType(TemplateMod.MOD_ID + ":keep_preference");

	/** 手写编解码（单布尔）。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, KeepPreferenceC2SPacket> STREAM_CODEC =
			StreamCodec.of(
					(buf, pkt) -> buf.writeBoolean(pkt.keepItemsWhenClosed),
					buf -> new KeepPreferenceC2SPacket(buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
