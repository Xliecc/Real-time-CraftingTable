package com.example.client.mixin;

import com.example.client.crafting.CraftingPreviewRenderer;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主视图（WorldRenderer）方块实体渲染 pass 的 HEAD 注入。
 *
 * <p>1.21.11 正常视图渲染方块实体走
 * {@code WorldRenderState.blockEntityRenderStates → BlockEntityRenderManager.render} 标准路径
 * （{@code WorldRenderer.renderBlockEntities} 循环逐条渲染，与 Iris 阴影 pass 的循环同构）。
 * 本 mixin 在渲染开始前把当前工作台预览的 {@code PreviewRenderState} 追加进该列表，
 * 使<b>正常视图</b>与 Visual Workbench（真 BE）完全同链渲染——所有光影在正常视图下
 * 对预览物品的处理与真实方块实体一致（不再走 AFTER_ENTITIES 旁路，消除跨光影差异）。
 *
 * <p>阴影 pass 由 {@link IrisShadowRendererMixin} 注入；两者共用
 * {@link CraftingPreviewRenderer#appendPreviewStates}（幂等：列表已含预览状态则跳过，
 * 避免同帧多次追加/重复渲染）。
 *
 * <p>方法签名（javap 反编译确认）：{@code private void renderBlockEntities(MatrixStack,
 * WorldRenderState, OrderedRenderCommandQueueImpl)}。目标为原版类，走默认 remap。
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

	@Inject(method = "renderBlockEntities", at = @At("HEAD"))
	private void templateMod$appendPreviewStates(MatrixStack matrices,
			WorldRenderState worldRenderState, OrderedRenderCommandQueueImpl queue, CallbackInfo ci) {
		CraftingPreviewRenderer.appendPreviewStates(worldRenderState);
	}
}
