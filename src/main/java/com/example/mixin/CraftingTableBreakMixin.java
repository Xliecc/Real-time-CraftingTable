package com.example.mixin;

import com.example.crafting.CraftingGridStorage;
import com.example.crafting.OpenTables;
import com.example.crafting.TableFacing;
import com.example.network.CraftingPreviewNetworking;

import net.minecraft.world.level.Level;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 让「工作台方块被替换/破坏」时像箱子一样把上面保留的材料掉落出来，并清除该位置的记录。
 *
 * <p>26.2 中 {@code BlockBehaviour.onStateReplaced/onRemove} 已被移除，改为注入
 * {@link Level#setBlock}（方块被设置/替换/移除的统⼀入⼝）。玩家挖、爆炸、活塞、流体、
 * 火焰…任何方式破坏都会走这里。与原版容器（箱子等）掉落内容的挂点同源。
 *
 * <p>性能：{@code setBlock} 虽对全世界任意方块变动都调用，但本方法开头仅一次
 * {@code getBlock()} 字段读 + 引用比较（工作台判断）即返回，开销可忽略。
 */
@Mixin(Level.class)
public abstract class CraftingTableBreakMixin {

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("HEAD"))
	private void templateMod$dropKeptItemsOnBreak(BlockPos pos, BlockState state, int flags, int maxDepth,
			CallbackInfoReturnable<Boolean> cir) {
		if (!(((Level) (Object) this) instanceof ServerLevel serverWorld)) {
			return;
		}
		// 读旧状态：setBlock 执行前该位置还是旧方块
		BlockState oldState = serverWorld.getBlockState(pos);
		if (oldState.getBlock() != Blocks.CRAFTING_TABLE) {
			return;
		}
		if (state.getBlock() == Blocks.CRAFTING_TABLE) {
			return; // 换方向（如旋转）不算移除
		}
		// 工作台被替换/移除：掉落+清理
		BlockPos immutablePos = pos.immutable();
		CraftingGridStorage.GridData data = CraftingGridStorage.peek(serverWorld, immutablePos);
		if (data != null) {
			for (ItemStack s : data.inputs()) {
				if (s != null && !s.isEmpty()) {
					Block.popResource(serverWorld, immutablePos, s);
				}
			}
		}
		CraftingGridStorage.remove(serverWorld, immutablePos);
		TableFacing.remove(immutablePos);
		OpenTables.removeBlock(immutablePos);
		CraftingPreviewNetworking.broadcastStored(serverWorld, immutablePos,
				serverWorld.dimension().identifier().toString(), List.of(), ItemStack.EMPTY);
	}
}
