package com.example.mixin;

import com.example.crafting.CraftingGridStorage;
import com.example.crafting.OpenTables;
import com.example.crafting.TableFacing;
import com.example.network.CraftingPreviewNetworking;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 让「工作台方块被替换/破坏」时像箱子一样把上面保留的材料掉落出来，并清除该位置的记录。
 *
 * <p>挂在 {@link BlockBehaviour#onStateReplaced}（原版方块被移除/替换的统一钩子）——玩家挖、
 * 爆炸、活塞、流体、火焰…任何方式破坏都会走这里，且该方法参数本身是
 * {@link ServerLevel}（只在服务端逻辑侧执行）。这与原版容器（箱子等）掉落内容的挂点同源。
 *
 * <p>本钩子<b>替代</b>第十八章的 {@code PlayerBlockBreakEvents.AFTER}（只覆盖玩家挖），
 * 统一入口避免双份掉落。幂等：方块每次实际离开某位置只触发一次，做——
 * 掉落 inputs / {@link CraftingGridStorage#remove} 清记录 / {@link TableFacing#remove} +
 * {@link OpenTables#removeBlock} 清占用 / 广播全空记录给客户端停渲染残留预览。
 *
 * <p>性能：{@code onStateReplaced} 虽对全世界任意方块变动都调用，但本方法开头仅一次
 * {@code getBlock()} 字段读 + 引用比较（工作台判断）即返回，开销可忽略。
 */
@Mixin(BlockBehaviour.class)
public abstract class CraftingTableBreakMixin {

	@Inject(method = "onStateReplaced", at = @At("HEAD"))
	private void templateMod$dropKeptItemsOnBreak(BlockState state, ServerLevel world,
			BlockPos pos, boolean moved, CallbackInfo ci) {
		// 只处理工作台被替换/破坏（onStateReplaced 仅在状态真实变化时触发，方块离开该位置 →
		// 按坐标存储的保留材料不再滞留）。是否 moved（活塞移走）都一并掉落+清理：
		// 无方块实体时材料无法跟随，留在原位存储只会造成「换个位置又冒出来」。
		if (state.getBlock() != Blocks.CRAFTING_TABLE) {
			return;
		}
		BlockPos immutablePos = pos;
		CraftingGridStorage.GridData data = CraftingGridStorage.peek(world, immutablePos);
		if (data != null) {
			for (ItemStack s : data.inputs()) {
				if (s != null && !s.isEmpty()) {
					Block.popResource(world, immutablePos, s);
				}
			}
		}
		CraftingGridStorage.remove(world, immutablePos);
		TableFacing.remove(immutablePos);
		OpenTables.removeBlock(immutablePos);
		CraftingPreviewNetworking.broadcastStored(world, immutablePos,
				world.dimension().identifier().toString(), List.of(), ItemStack.EMPTY);
	}
}
