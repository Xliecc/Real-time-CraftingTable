package com.example;

import com.example.block.PreviewBlockEntity;
import com.example.crafting.CraftingGridStorage;
import com.example.crafting.PlayerKeepPrefs;
import com.example.crafting.TableFacing;
import com.example.network.CraftingGridStoredS2CPacket;
import com.example.network.CraftingTableFacingS2CPacket;
import com.example.network.CraftingTableOpenS2CPacket;
import com.example.network.GridBroadcastScheduler;
import com.example.network.KeepPreferenceC2SPacket;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

import org.slf4j.LoggerFactory;

public class TemplateMod implements ModInitializer {
	public static final String MOD_ID = "real-time-crafting-table";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 标准方块实体渲染路径的类型：此处强制加载，触发 Registry.register（注册表访问必须在
		// 初始化期完成）。该类型从不真正放进世界，只作为渲染状态（PreviewRenderState）的类型键。
		net.minecraft.block.entity.BlockEntityType.getId(PreviewBlockEntity.TYPE);

		// 联机支持（S2C 通道注册，双端都必须在初始化期完成）：
		// ① 开桌坐标包：服务端 → 开桌玩家（客户端写入 OpenTableTracker 定位实时 GUI 预览）；
		// ② 保留记录包：服务端 → 追踪区块的玩家（关闭后保留预览的跨客户端同步）。
		PayloadTypeRegistry.playS2C().register(CraftingTableOpenS2CPacket.ID, CraftingTableOpenS2CPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(CraftingGridStoredS2CPacket.ID, CraftingGridStoredS2CPacket.CODEC);
		// ③ 工作台朝向包：服务端 → 追踪区块的玩家（目标工作台的「最后操作者方位」扇区，
		//    使所有客户端渲染该工作台预览时都朝向最后一个操作它的人）。单机直写幂等重复。
		PayloadTypeRegistry.playS2C().register(CraftingTableFacingS2CPacket.ID, CraftingTableFacingS2CPacket.CODEC);
		// ④ keep 偏好包（C2S）：客户端上报自己的「关闭后保留材料」偏好 → 服务端按玩家 UUID
		//    记录（PlayerKeepPrefs），关桌拦截用关闭者本人的偏好判定，而非服务端全局配置。
		PayloadTypeRegistry.playC2S().register(KeepPreferenceC2SPacket.ID, KeepPreferenceC2SPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(KeepPreferenceC2SPacket.ID,
				(payload, context) -> {
					PlayerKeepPrefs.set(context.player().getUuid(), payload.keepItemsWhenClosed());
				});
		// 玩家离开时清除其 keep 偏好，防 UUID → 记录长期堆积。
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				PlayerKeepPrefs.remove(handler.getPlayer().getUuid()));

		// 玩家加入时的初始同步：把存储里全部非空保留记录（跨维度）逐条补发给新客户端，
		// 使其能直接渲染已有的「关闭后保留预览」（关桌/广播只发生在事件时刻，加入者会错过）。
		// 传 server 的注册表管理器：顺带触发存储文件加载（服务器刚启动还没人开过桌时缓存未加载，
		// 否则会漏掉上个会话的记录——顺带修复了单机「重进世界要开一次桌才显示保留预览」的旧限制）。
		// 记录自带维度键，客户端按当前维度过滤渲染；单机（集成服务器）下宿主收到的是
		// 同一 JVM 缓存里已有数据的幂等副本，无害。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			net.minecraft.registry.RegistryOps<com.google.gson.JsonElement> ops =
					net.minecraft.registry.RegistryOps.of(com.mojang.serialization.JsonOps.INSTANCE,
							server.getRegistryManager());
			for (CraftingGridStorage.SyncEntry entry
					: CraftingGridStorage.peekAllForSync(server.getRegistryManager())) {
				ServerPlayNetworking.send(player, CraftingGridStoredS2CPacket.fromGridData(
						ops, entry.pos(), entry.dimensionKey(),
						entry.data().inputs(), entry.data().result()));
			}
			// 补发全部已知工作台朝向（最后操作者方位 + UUID）：后加入的玩家渲染保留预览时
			// 方向正确，且 FOLLOW 档能用 UUID 在本地继续实时跟随操作者实体。
			for (Map.Entry<BlockPos, Integer> e : TableFacing.all().entrySet()) {
				UUID op = TableFacing.getOperator(e.getKey());
				ServerPlayNetworking.send(player,
						new CraftingTableFacingS2CPacket(e.getKey(), e.getValue(), op));
			}
		});

		// 工作台方块被破坏/替换时掉落保留材料 + 清记录：统一由 common mixin
		// CraftingTableBreakMixin（AbstractBlock.onStateReplaced）处理——与箱子同源，覆盖
		// 玩家挖、爆炸、活塞、流体、火焰等一切方式，且避免 PlayerBlockBreakEvents 双份掉落。
		LOGGER.info("Crafting preview mod initialized (multiplayer-ready).");

		// 服务器正常停止时把内存缓存落盘（性能优化 P0-1：编辑过程只写内存，见
		// CraftingGridStorage.storeMemory；若不在停止时兜底落盘，最近编辑会在重启后丢失）。
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				CraftingGridStorage.persist(server.getOverworld()));

		// 广播合并（性能优化 P0-2）：tick 末尾统一 flush 本 tick 内所有内容变化的广播
		// （同一位置多次编辑只发一次，见 GridBroadcastScheduler）。
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
				GridBroadcastScheduler.flush(world);
			}
		});
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}