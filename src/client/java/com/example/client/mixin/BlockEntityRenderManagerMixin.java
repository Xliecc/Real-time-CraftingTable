package com.example.client.mixin;

import com.example.client.crafting.CraftingPreviewRenderer;
import com.example.client.crafting.PreviewRenderState;
import com.example.config.PreviewConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 工作台预览的渲染入口（替代「自定义 BlockEntityType + 注册渲染器」方案）。
 *
 * <p>背景：旧方案在 {@code Registries.BLOCK_ENTITY_TYPE} 注册自定义类型
 * {@code real-time-crafting-table:preview} 作为渲染状态键，fabric 强制要求注册
 * （"intrusive holders" 检查），但注册后该条目会进联机注册表同步——未装本 mod 的
 * vanilla 客户端没有此条目，registry sync 直接拒绝连接
 * （"This server requires Fabric Loader and Fabric API... real-time-crafting-table"）。
 * 参考 enchantment-table 项目，改为<b>不注册任何注册表条目</b>的渲染方式。
 *
 * <p>本 mixin 拦截 {@link BlockEntityRenderDispatcher#render}（正常视图与 Iris 阴影 pass
 * 共用的标准 BE 渲染循环），若状态是 {@link PreviewRenderState} 则直接手工调用
 * {@link CraftingPreviewRenderer#renderPreviewGeometry}（调用时矩阵已由渲染循环平移到
 * 方块位置，相机相对——与 BlockEntityRenderer 的既有约定完全一致），随后 {@code cancel}
 * 掉原逻辑（原逻辑按 state 的 type 查渲染器，而我们的状态不再有注册类型，必须跳过）。
 *
 * <p>两个 mixin（{@link WorldRendererMixin} 主视图、{@link IrisShadowRendererMixin}
 * 阴影 pass）仍然负责把预览状态追加进 {@code LevelRenderState.blockEntityRenderStates}，
 * 渲染由本 mixin 接管——动画状态、光照、几何全部不变。
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderManagerMixin {

	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void templateMod$renderPreview(BlockEntityRenderState state, PoseStack matrices,
			SubmitNodeCollector collector, CameraRenderState cameraRenderState, CallbackInfo ci) {
		if (!(state instanceof PreviewRenderState ps)) {
			return;
		}
		if (!ps.hasContent) {
			ci.cancel();
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			ci.cancel();
			return;
		}
		Entity cameraEntity = client.getCameraEntity();
		if (cameraEntity == null) {
			ci.cancel();
			return;
		}
		float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		PreviewConfig cfg = PreviewConfig.get();
		if (!cfg.enabled) {
			ci.cancel();
			return;
		}
		CraftingPreviewRenderer.renderPreviewGeometry(matrices, collector,
				client, cameraEntity, cfg, ps.time, tickDelta,
				ps.blockPos, ps.grid, ps.result, ps.lightCoords);
		ci.cancel();
	}
}