package com.example.block;

import com.example.TemplateMod;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.math.BlockPos;

/**
 * 「预览」方块实体：只用于把工作台合成预览接入标准方块实体渲染管线
 * （从而让所有光影的阴影 pass 都按标准路径渲染，与附魔台/可视化工作台一致）。
 *
 * <p><b>永远不会真正放进世界</b>——原版工作台仍是原版方块（不是方块实体）。
 * 这个类型只用来：① 在 {@code BlockEntityRendererFactories} 注册渲染器；② 给
 * {@code PreviewRenderState.type} 赋值，使 {@code BlockEntityRenderManager}
 * 能按类型查到我注册的渲染器。渲染内容是每帧动态构造的渲染状态
 * （{@code com.example.client.crafting.PreviewRenderState}），不是本实体。
 */
public final class PreviewBlockEntity extends BlockEntity {

	// 1.21.11 移除了 BlockEntityType.Builder，用 Fabric 的 FabricBlockEntityTypeBuilder。
	// 不绑定任何方块（mod 不会真正放置该实体），只作为渲染状态的类型键。
	public static final BlockEntityType<PreviewBlockEntity> TYPE = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			TemplateMod.id("preview"),
			FabricBlockEntityTypeBuilder.create(PreviewBlockEntity::new).build());

	private PreviewBlockEntity(BlockPos pos, BlockState state) {
		super(TYPE, pos, state);
	}

	// 本模组不在世界放置任何方块实体：无需序列化 / tick / 网络同步。
}