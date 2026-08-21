package com.example.client;

import com.example.block.PreviewBlockEntity;
import com.example.client.crafting.PreviewBlockEntityRenderer;
import com.example.config.PreviewConfig;
import com.example.crafting.CraftingGridStorage;
import com.example.crafting.OpenTableTracker;
import com.example.crafting.TableFacing;
import com.example.network.CraftingGridStoredS2CPacket;
import com.example.network.CraftingTableFacingS2CPacket;
import com.example.network.CraftingTableOpenS2CPacket;
import com.example.network.KeepPreferenceC2SPacket;

import com.google.gson.JsonElement;

import com.mojang.serialization.JsonOps;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;

import java.util.ArrayList;
import java.util.List;

public class TemplateModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 预览渲染：正常视图与光影阴影 pass 都走标准方块实体路径
		// （WorldRendererMixin + IrisShadowRendererMixin 分别把预览状态追加进
		// blockEntityRenderStates，由 BlockEntityRenderManager 统一渲染，与 Visual Workbench 同链）。
		// 注册预览方块实体的渲染器（渲染状态由 CraftingPreviewRenderer 每帧动态构造）。
		BlockEntityRendererRegistry.register(PreviewBlockEntity.TYPE, PreviewBlockEntityRenderer::new);

		// keep 偏好上报（C2S）：客户端把自己的「关闭后保留材料」偏好发服务端（服务端据此按
		// 玩家判定关桌是否保留，而非用房主/服务端全局配置）。⋆⋆ 客户端在各端持有独立 Config。
		// 加事件时发送一次（基线），每次打开工作台再重发一次（覆盖运行中改配置的情况）。
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendKeepPref());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { });

		// S2C 接收器：网络线程收到包后转到客户端主线程写入（context.client().execute）。
		// ① 开桌坐标 → OpenTableTracker（渲染器在 CraftingScreen 打开期间读取定位实时预览）。
		//    单机下服务端已直写 tracker，此处为幂等重复；联机下是唯一来源。
		//    顺带：收到「打开工作台」通知时，把当前 keep 偏好重上报一次（覆盖运行中改配置）。
		ClientPlayNetworking.registerGlobalReceiver(CraftingTableOpenS2CPacket.ID,
				(payload, context) -> context.client().execute(() -> {
					OpenTableTracker.set(payload.pos(), payload.dimensionKey());
					sendKeepPref();
				}));

		// ② 保留记录 → CraftingGridStorage 内存缓存（渲染线程 peekAll 即刻可见）：
		//    关桌广播（保留预览更新/清除）与玩家加入时的全量补发共用此入口。
		//    包内物品为 JSON 字符串（见 CraftingGridStoredS2CPacket 注释），此处用客户端
		//    世界注册表 ops 解码为完整栈（含附魔等组件），再写缓存。
		//    clientApplyIfChanged：与缓存相同则跳过——单机下服务端 store 已写同 JVM 缓存，
		//    回写相等数据无害；内容不同（清除/更新）正常写入。
		ClientPlayNetworking.registerGlobalReceiver(CraftingGridStoredS2CPacket.ID,
				(payload, context) -> context.client().execute(() -> {
					MinecraftClient client = context.client();
					if (client.world == null) {
						return; // 无世界（未进游戏）时不处理，缓存无可写对象
					}
					RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE,
							client.world.getRegistryManager());
					List<ItemStack> inputs = new ArrayList<>(9);
					for (String s : payload.inputJson()) {
						inputs.add(CraftingGridStoredS2CPacket.fromJson(ops, s));
					}
					ItemStack result = CraftingGridStoredS2CPacket.fromJson(ops, payload.resultJson());
					CraftingGridStorage.clientApplyIfChanged(payload.dimensionKey(), payload.pos(),
							inputs, result);
				}));

		// ③ 工作台朝向（最后操作者方向 + UUID）→ TableFacing：所有客户端由此让预览面板朝向
		//    「上一个操作工作台的人」（开桌广播 + 加入补发共用此入口，可重复设置、幂等）。
		//    FOLLOW 档再用 UUID 在本地世界找操作者实体，做客户端本地实时跟随。
		ClientPlayNetworking.registerGlobalReceiver(CraftingTableFacingS2CPacket.ID,
				(payload, context) -> context.client().execute(() ->
						TableFacing.setFacing(payload.pos(), payload.sector(), payload.operatorUuid())));
	}

	/**
	 * 把本客户端当前的「关闭工作台后保留材料」偏好上报服务端（C2S）。读取本端 Config.json，
	 * 服务端据此按玩家判定关桌是否保留（而非用房主/服务端全局配置）。
	 */
	private static void sendKeepPref() {
		Boolean keepEnabled = PreviewConfig.get().keepItemsWhenClosed;
		boolean keep = keepEnabled == null || keepEnabled;
		ClientPlayNetworking.send(new KeepPreferenceC2SPacket(keep));
	}
}