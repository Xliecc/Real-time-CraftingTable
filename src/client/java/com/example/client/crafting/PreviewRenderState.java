package com.example.client.crafting;

import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * 合成预览的方块实体渲染状态（1.21.11 状态式渲染）。
 *
 * <p>每帧由 {@link CraftingPreviewRenderer#appendPreviewStates} 动态构造并追加到
 * {@code WorldRenderState.blockEntityRenderStates}，正常视图与 Iris 阴影 pass 都会用
 * 标准路径（{@code BlockEntityRenderManager.render}）渲染它——与附魔台/可视化工作台
 * 完全一致，因此所有光影都能正确投影（不再有 MakeUp 错位/Bliss 半透明/Sildur 过亮）。
 *
 * <p><b>不依赖自定义 {@code BlockEntityType}</b>（vanilla 联机兼容）：本状态不带注册
 * 类型（{@code type} 保持父类默认值，不指向任何注册表条目），渲染由
 * {@code BlockEntityRenderManagerMixin} 在 {@code render} HEAD 直接手工调用
 * {@link CraftingPreviewRenderer#renderPreviewGeometry} 接管。参见
 * {@code BlockEntityRenderManagerMixin} 类注释。
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
	}
}
