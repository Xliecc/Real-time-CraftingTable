package com.example.client.mixin;

import com.example.client.crafting.CraftingPreviewRenderer;

import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让光影阴影 pass 用<b>标准方块实体路径</b>渲染工作台预览物品（从而所有光影都能正确投影）。
 *
 * <p>原理：Iris 的 {@code ShadowRenderer#renderBlockEntities} 遍历
 * {@code WorldRenderState.blockEntityRenderStates}（标准 BE 渲染列表）并用
 * {@code BlockEntityRenderManager.render} 逐条渲染——与正常视图、附魔台、
 * 可视化工作台完全同一条路径。本 mixin 在渲染开始前把当前预览的
 * {@code PreviewRenderState} 追加进该列表，Iris 的循环就会用标准路径渲染它们。
 *
 * <p>这样不再需要旧方案（AFTER_ENTITIES 几何直接塞阴影 pass 的裸命令队列），
 * 消除了 MakeUp 错位 / Bliss 半透明 / Sildur 过亮等跨光影不一致问题。
 *
 * <p>{@code @Pseudo} + 字符串 targets：模组不依赖 Iris，未装 Iris 时本 mixin 静默
 * 跳过（目标类不存在不报错）；装了 Iris 时才应用。方法签名必须与目标方法完全一致：
 * {@code renderBlockEntities(LevelRendererAccessor, MatrixStack, OrderedRenderCommandQueueImpl,
 * WorldRenderState, Camera) → int}。方法名与方法参数类型均为 Iris/JVM 层面的真实名字
 * （Iris 不混淆自己的类名/方法名，仅 MC 类型保持中间名，无需 remap）。
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererMixin {

	@Inject(method = "renderBlockEntities", at = @At("HEAD"), remap = false)
	private void templateMod$appendPreviewStates(LevelRendererAccessor accessor, MatrixStack matrices,
			OrderedRenderCommandQueueImpl queue, WorldRenderState worldRenderState, Camera camera,
			CallbackInfoReturnable<Integer> cir) {
		// 在 Iris 遍历 BE 渲染列表之前，把预览状态追加进去 → 标准路径渲染（全光影兼容）。
		CraftingPreviewRenderer.appendPreviewStates(worldRenderState);
	}
}