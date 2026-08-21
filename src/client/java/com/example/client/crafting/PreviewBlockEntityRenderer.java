package com.example.client.crafting;

import com.example.block.PreviewBlockEntity;
import com.example.config.PreviewConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;

/**
 * 合成预览的标准方块实体渲染器（1.21.11 状态式渲染）。
 *
 * <p>由 {@code BlockEntityRenderManager.render} 在正常视图和光影阴影 pass 中统一调用
 * （矩阵已被调用方平移到方块位置，相机相对），本渲染器只负责在工作台局部坐标绘制
 * 3×3 材料 + 结果——完全复用 {@link CraftingPreviewRenderer} 的现有几何逻辑。
 *
 * <p>注册：客户端初始化时 {@code BlockEntityRendererRegistry.register(PreviewBlockEntity.TYPE, ...)}。
 * 渲染状态由 {@code CraftingPreviewRenderer} 每帧动态构造并放进
 * {@code WorldRenderState.blockEntityRenderStates}，因此本类无需持有任何 BE 实例。
 */
public final class PreviewBlockEntityRenderer
		implements BlockEntityRenderer<PreviewBlockEntity, PreviewRenderState> {

	public PreviewBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
	}

	@Override
	public PreviewRenderState createRenderState() {
		return new PreviewRenderState();
	}

	@Override
	public void render(PreviewRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState) {
		if (state == null || !state.hasContent) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		Entity cameraEntity = client.getCameraEntity();
		if (cameraEntity == null) {
			return;
		}
		float tickDelta = client.getRenderTickCounter().getTickProgress(true);
		PreviewConfig cfg = PreviewConfig.get();
		if (!cfg.enabled) {
			return;
		}
		// 矩阵已被调用方（WorldRenderer / Iris 阴影 pass）平移到 state.pos（相机相对），
		// 直接以工作台局部坐标绘制即可；光照来自 state（构造时按世界实时采样）。
		CraftingPreviewRenderer.renderPreviewGeometry(matrices, queue,
				client, cameraEntity, cfg, state.time, tickDelta,
				state.pos, state.grid, state.result, state.lightmapCoordinates);
	}

	@Override
	public boolean rendersOutsideBoundingBox() {
		// 预览物品悬浮在工作台顶面之上，超出方块包围盒，必须返回 true 才能被渲染。
		return true;
	}

	@Override
	public int getRenderDistance() {
		return 128;
	}
}