package com.example.client.mixin;

import com.example.crafting.OpenTableTracker;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端本地追踪「右键打开的工作台」位置（服务端没装本 mod 时渲染也能定位）。
 *
 * <p>背景：{@link OpenTableTracker} 原本只由服务端写入（服务端 mixin 直写，或
 * {@code CraftingTableOpenS2CPacket} 网络接收器写入）——服务端没装本 mod 时两条路都没有，
 * 渲染器读不到位置，GUI 实时预览不渲染（虽然槽位内容能本地读）。
 *
 * <p>本 mixin 拦截 {@link MultiPlayerGameMode#interactBlock}（客户端右键方块交互），
 * 若目标是工作台，则在 HEAD 直接本地写入 {@link OpenTableTracker}（位置 + 当前维度）。
 * 与服务端来源<b>幂等</b>：
 * <ul>
 *   <li>服务端没装 mod：本地写入是唯一来源，GUI 预览照常定位；</li>
 *   <li>服务端装了 mod：本地写入与服务端 mixin 直写 / S2C 包写入同一坐标，先写后写无害；</li>
 *   <li>关闭界面时由 common mixin {@code CraftingScreenHandlerMixin.onClosed} 清空
 *       （客户端 handler 镜像同样应用该 mixin），闭环完整。</li>
 * </ul>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ClientInteractionMixin {

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void templateMod$trackPickedTable(LocalPlayer player, InteractionHand hand,
			BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel world = client.level;
		if (world == null) {
			return;
		}
		if (world.getBlockState(hitResult.getBlockPos()).getBlock() != Blocks.CRAFTING_TABLE) {
			return;
		}
		OpenTableTracker.set(hitResult.getBlockPos(),
				world.dimension().identifier().toString());
	}
}