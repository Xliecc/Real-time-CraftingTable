package com.example.client.crafting;

import com.example.block.PreviewBlockEntity;

import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 合成预览的标准方块实体渲染状态（1.21.11 状态式渲染）。
 *
 * <p>每帧由 {@link CraftingPreviewRenderer#appendPreviewStates} 动态构造并追加到
 * {@code WorldRenderState.blockEntityRenderStates}，正常视图与 Iris 阴影 pass 都会用
 * 标准路径（{@code BlockEntityRenderManager.render}）渲染它——与附魔台/可视化工作台
 * 完全一致，因此所有光影都能正确投影（不再有 MakeUp 错位/Bliss 半透明/Sildur 过亮）。
 *
 * <p>{@code type} 必须指向 {@link PreviewBlockEntity#TYPE} 注册的自定义类型，
 * {@code BlockEntityRenderManager.getByRenderState} 才能据此查到我注册的渲染器。
 */
public final class PreviewRenderState extends BlockEntityRenderState {

	/** 3×3 合成材料（长度与渲染器约定为 9）。 */
	public List<ItemStack> grid;

	/** 合成结果（可空）。 */
	public ItemStack result;

	/** 渲染所用单调时钟（world.getTime()），与正常视图动画同步。 */
	public long time;

	/** 是否有可见内容（grid 或 result 非空）。 */
	public boolean hasContent;

	public PreviewRenderState() {
		this.type = PreviewBlockEntity.TYPE;
	}
}
