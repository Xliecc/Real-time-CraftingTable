package com.example.network;

import com.example.TemplateMod;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import com.mojang.serialization.DataResult;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：某个工作台位置的「保留材料」记录同步。
 *
 * <p>三个发送时机（均由服务端发起）：
 * <ul>
 *   <li>关闭工作台且服务端存储完成后：广播给正在追踪该区块的所有玩家（实时更新保留预览）；</li>
 *   <li>关闭工作台但保留开关关闭：广播全空记录（清除客户端此前收到的保留预览，防止残留幻影）；</li>
 *   <li>玩家加入服务器时：把存储里的全部非空记录逐条补发（新客户端初始同步，跨维度记录
 *       一并发送、由客户端按当前维度过滤渲染）。</li>
 * </ul>
 *
 * <p>物品以 <b>JSON 字符串</b> 传输（{@link ItemStack#CODEC} + 发送方/接收方的注册表 ops）：
 * 不用二进制 {@link ItemStack#PACKET_CODEC}——实测在含 enchantment-table 一类 mod 的整合包中，
 * 栈内附魔的 RegistryEntry 实例与连接注册表的引用表不一致，PACKET_CODEC 编码抛
 * 「Can't find id for Reference{...}」导致断线。JSON 编解码走 {@code ResourceKey} 按 id 查找、
 * 不依赖条目对象身份，可正确往返。
 */
public record CraftingGridStoredS2CPacket(BlockPos pos, String dimensionKey,
		List<String> inputJson, String resultJson) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CraftingGridStoredS2CPacket> TYPE =
			new CustomPacketPayload.Type<>(TemplateMod.id("crafting_grid_stored"));

	private static final int GRID_SIZE = 9;

	/**
	 * 直接把栈编码成 JSON 字符串（不做空判断之外的任何加工，完整保留组件）。
	 *
	 * @param ops 发送方注册表 ops（服务端 world 的 registryManager，能解析所有条目）
	 */
	public static String toJson(RegistryOps<JsonElement> ops, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		DataResult<JsonElement> result = ItemStack.CODEC.encodeStart(ops, stack);
		return result.result().map(JsonElement::toString).orElse("");
	}

	/**
	 * 把 JSON 字符串解码回栈。
	 *
	 * @param ops 接收方注册表 ops（客户端 world 的 registryManager）
	 */
	public static ItemStack fromJson(RegistryOps<JsonElement> ops, String json) {
		if (json == null || json.isEmpty()) {
			return ItemStack.EMPTY;
		}
		try {
			JsonElement element = JsonParser.parseString(json);
			DataResult<ItemStack> result = ItemStack.CODEC.parse(ops, element);
			return result.result().orElse(ItemStack.EMPTY);
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	/**
	 * 直接从网格数据构造包（物品编码为 JSON 字符串，完整保留组件）。
	 *
	 * @param ops 发送方注册表 ops（服务端 registryManager）
	 * @param pos 工作台位置
	 * @param dimensionKey 维度键
	 * @param inputs 9 格输入（不足补空）
	 * @param result 结果（可空）
	 */
	public static CraftingGridStoredS2CPacket fromGridData(RegistryOps<JsonElement> ops,
			BlockPos pos, String dimensionKey, List<ItemStack> inputs, ItemStack result) {
		List<String> inputJson = new ArrayList<>(GRID_SIZE);
		for (int i = 0; i < GRID_SIZE; i++) {
			ItemStack s = inputs != null && i < inputs.size() ? inputs.get(i) : ItemStack.EMPTY;
			inputJson.add(toJson(ops, s));
		}
		String resultJson = toJson(ops, result == null ? ItemStack.EMPTY : result);
		return new CraftingGridStoredS2CPacket(pos, dimensionKey, inputJson, resultJson);
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, CraftingGridStoredS2CPacket> STREAM_CODEC =
			StreamCodec.of(
					(buf, pkt) -> {
						buf.writeBlockPos(pkt.pos);
						buf.writeUtf(pkt.dimensionKey);
						for (int i = 0; i < GRID_SIZE; i++) {
							String s = pkt.inputJson != null && i < pkt.inputJson.size()
									? pkt.inputJson.get(i) : "";
							buf.writeUtf(s);
						}
						buf.writeUtf(pkt.resultJson == null ? "" : pkt.resultJson);
					},
					buf -> {
						BlockPos pos = buf.readBlockPos();
						String dimensionKey = buf.readUtf();
						List<String> inputs = new ArrayList<>(GRID_SIZE);
						for (int i = 0; i < GRID_SIZE; i++) {
							inputs.add(buf.readUtf());
						}
						String resultJson = buf.readUtf();
						return new CraftingGridStoredS2CPacket(pos, dimensionKey, inputs, resultJson);
					});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
