package com.example.mixin;

import com.example.TemplateMod;
import com.example.crafting.CraftingGridStorage;
import com.example.crafting.OpenTableTracker;
import com.example.crafting.OpenTables;
import com.example.crafting.PlayerKeepPrefs;
import com.example.crafting.TableFacing;
import com.example.network.CraftingPreviewNetworking;
import com.example.network.GridBroadcastScheduler;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 为 {@link CraftingScreenHandler} 注入「关闭工作台时保留合成材料」逻辑（common：双端加载）。
 *
 * <p>仅当配置 {@code keepItemsWhenClosed} 开启时生效：
 * <ul>
 *   <li>构造（打开界面）时：把该位置已归档的合成网格物品恢复进网格，并重算合成结果；</li>
 *   <li>关闭时：在 vanilla 把网格物品退还玩家/掉落之前拦截，改为存入 {@link CraftingGridStorage}。</li>
 * </ul>
 *
 * <p><b>运行环境（联机支持后）：</b>mixin 已从 client 源集移到 common，物理服务端与物理客户端
 * 都会应用。真实服务端（单机集成服务器 / LAN / 独立服务器同构）持有真实
 * {@code ScreenHandlerContext}，负责存储/恢复/广播；客户端镜像（context 恒为 EMPTY）只做
 * 本地即时缓存写入（防关桌闪没）。工作台坐标与保留记录经 S2C 网络包同步给客户端渲染。
 *
 * <p>成员访问规则（踩坑记录）：
 * <ul>
 *   <li>{@code context}、{@code getInputSlots()}、{@code onContentChanged} 均声明在目标类
 *       {@code CraftingScreenHandler} 自身，可 {@code @Shadow}；</li>
 *   <li>**不能** {@code @Shadow} 父类 {@code AbstractCraftingScreenHandler} 的继承字段
 *       （如 {@code craftingResultInventory}）：mixin 的 @Shadow 字段只在目标类自身查找，不遍历
 *       父类，实测启动崩溃 {@code @Shadow field field_52560 was not located in class_1714}；</li>
 *   <li>需要访问父类公开成员（如结果槽 {@code getSlot(0)}）时，用 cast 技巧：
 *       {@code (CraftingScreenHandler)(Object)this} 后直接调用（运行时 this 就是 handler 实例）。</li>
 * </ul>
 */
@Mixin(CraftingScreenHandler.class)
public abstract class CraftingScreenHandlerMixin {

	private static final int GRID_SIZE = 9;

	/** 临时诊断开关（定位「配方书结果未同步给其他玩家」）：打印 syncLiveGrid 的结果重算判定。 */
	private static final boolean DIAG = false;

	@Shadow
	@Final
	private ScreenHandlerContext context;

	/** 该菜单所属玩家（CraftingScreenHandler 自身字段；onContentChanged 时用于「最后操作者」朝向）。 */
	@Shadow
	@Final
	private PlayerEntity player;

	/** 共享写回重入保护（服务端单线程）：写回他人槽位会触发对方 onContentChanged →
	 * 对方 syncLiveGrid 再进来时跳过（否则对方会被误记为「最后操作者」并重复广播）。
	 * 仅服务端线程访问，普通字段即可。 */
	private static boolean sharingSync = false;

	/** 3×3 合成网格的槽位（由目标类自身声明，非继承）。 */
	@Shadow
	public abstract List<Slot> getInputSlots();

	/** 网格内容变化后重算合成结果。 */
	@Shadow
	public abstract void onContentChanged(Inventory inventory);

	/**
	 * 打开工作台（创建菜单）时：登记打开者、把该位置<b>权威内容</b>同步进自己的 3×3 网格
	 * （共享合成网格：所有打开者看到同一份内容），并发包告知客户端工作台坐标（供渲染器
	 * 定位 GUI 预览）。两个构造重载：2 参构造委托给 3 参构造并传
	 * {@link ScreenHandlerContext#EMPTY}，因此只需拦截 3 参构造；EMPTY 上下文的 {@code run}
	 * 是空操作，客户端镜像无害。
	 *
	 * <p>共享语义：不再「仅第一位恢复」——任意打开者网格都同步为该位置的权威内容；
	 * 编辑经 {@link #templateMod$syncLiveGrid} 更新权威并写回所有打开者的网格槽位，
	 * 任何人的操作都实时反映到所有人（后写覆盖）。
	 */
	@Inject(method = "<init>(ILnet/minecraft/entity/player/PlayerInventory;Lnet/minecraft/screen/ScreenHandlerContext;)V",
			at = @At("RETURN"))
	private void templateMod$restoreGridOnOpen(int syncId, PlayerInventory playerInventory,
			ScreenHandlerContext context, CallbackInfo ci) {
		// 仅对原版 CraftingScreenHandler 生效：VisualWorkbench 等 mod 的子类菜单
		// （如 VisualCraftingMenu）在 super() 构造期间其 blockEntity 尚未赋值，本方法若
		// 继续执行（setStack / onContentChanged → slotsChanged）会触发 NPE，导致
		// 「点开工作台没反应」。原版合成台无此问题，行为完全不变。
		if (((Object) this).getClass() != CraftingScreenHandler.class) {
			return;
		}
		this.context.run((world, pos) -> {
			if (world instanceof ServerWorld serverWorld) {
				BlockPos immutablePos = pos.toImmutable();
				String dimensionKey = world.getRegistryKey().getValue().toString();
				// 记录当前打开的工作台坐标：① 单机直写 tracker（服务端线程写、渲染线程读，volatile）；
				// ② 经 S2C 包发给开桌玩家（联机下客户端的唯一来源；单机下与直写幂等重复）。
				// 与 keep 开关无关：GUI 打开时渲染器需要此坐标定位实时预览；维度一并记录，
				// 供关闭时按维度写存储缓存（客户端拿不到 world 对象）。
				OpenTableTracker.set(immutablePos, dimensionKey);
				if (playerInventory.player instanceof ServerPlayerEntity serverPlayer) {
					CraftingPreviewNetworking.sendOpen(serverPlayer, immutablePos, dimensionKey);
					// 联机共享朝向：以「最后实际操作者」为基准（onContentChanged 时更新）。
					// 开桌（点击）方向更新规则：
					//  - 无操作者 或 我就是最后操作者（自己重新打开）→ 面板转向我的点击方向
					//    （「从别的方向点击旋转生效」）；
					//  - 有其他操作者（B 旁观 A 的桌）→ 不抢方向，仍跟随 A（上轮需求），
					//    但把当前方向广播给新打开者（含本人），让它立即转向上一个操作者。
					UUID currentOp = TableFacing.getOperator(immutablePos);
					int sector = TableFacing.computeSector(serverPlayer.getX(), serverPlayer.getZ(), immutablePos);
					if (currentOp == null || currentOp.equals(serverPlayer.getUuid())) {
						TableFacing.setFacing(immutablePos, sector, serverPlayer.getUuid());
						CraftingPreviewNetworking.broadcastFacing(serverWorld, immutablePos, sector, serverPlayer.getUuid());
					} else {
						int currentSector = TableFacing.get(immutablePos);
						if (currentSector != TableFacing.UNKNOWN) {
							CraftingPreviewNetworking.broadcastFacing(serverWorld, immutablePos,
									currentSector, currentOp);
						}
					}
					// 登记打开者（共享网格：每人一条）。
					OpenTables.add(immutablePos, serverPlayer.getUuid());
				}
				// 同步该位置权威内容进自己的网格（共享合成网格：所有人看到同一份）。
				// 无论 keep 开关：打开时网格显示该位置当前内容（保持实时同步语义）。
				// 恢复写槽会触发 onContentChanged → syncLiveGrid：用 sharingSync 抑制，
				// 避免「开桌恢复」被误判为实际操作（抢走最后操作者朝向/重复广播）；
				// 重入期间对方 handler 的 syncLiveGrid 同样被抑制（内容相同，无需再同步）。
				sharingSync = true;
				try {
					CraftingGridStorage.GridData stored = CraftingGridStorage.peek(serverWorld, pos);
					if (stored != null) {
						List<Slot> slots = this.getInputSlots();
						List<ItemStack> inputs = stored.inputs();
						// 恢复进槽前规范化附魔条目：按 key 重解引用为注册表规范实例（值对象与注册表
						// 一致），避免原版 container_set_content 编码时因「外来值对象」查不到 raw id 断线
						// （Can't find id for Reference{...}）。规范化的副本替换原缓存数据进槽。
						net.minecraft.registry.Registry<net.minecraft.enchantment.Enchantment> reg =
								serverWorld.getServer().getRegistryManager()
										.getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
						for (int i = 0; i < slots.size() && i < inputs.size() && i < GRID_SIZE; i++) {
							ItemStack stack = inputs.get(i);
							if (stack == null || stack.isEmpty()) {
								slots.get(i).setStack(ItemStack.EMPTY);
								continue;
							}
							slots.get(i).setStack(CraftingGridStorage.canonicalizeEnchantments(reg, stack));
						}
						// 结果槽：客户端镜像不自己重算，服务端打开时直接写权威结果（共享显示）。
						CraftingScreenHandler self = (CraftingScreenHandler) (Object) this;
						ItemStack storedResult = stored.result();
						self.getSlot(0).setStack(storedResult != null
								? CraftingGridStorage.canonicalizeEnchantments(reg, storedResult) : ItemStack.EMPTY);
					}
				} finally {
					sharingSync = false;
				}
			}
		});
	}
	/**
	 * 网格内容变化（编辑 = 实际操作）：① 更新该位置<b>权威内容</b>（服务端缓存 + 落盘）；
	 * ② 写回所有打开该桌玩家的 handler 网格槽位（共享合成网格，双方 GUI 完全实时同步）；
	 * ③ 广播给追踪区块的所有客户端（悬浮预览同步）；④ 更新「最后实际操作者」朝向。
	 *
	 * <p>写回他人 handler 时 setStack 会再次触发对方的 onContentChanged → 本方法 ——
	 * 但内容相同：权威 store 幂等、广播 clientApplyIfChanged 幂等、槽位 diff 幂等，链终止。
	 */
	@Inject(method = "onContentChanged", at = @At("TAIL"))
	private void templateMod$syncLiveGrid(Inventory inventory, CallbackInfo ci) {
		// 仅原版合成台：子类菜单（VisualWorkbench 等）有自己的可视化与存储，不接管。
		if (((Object) this).getClass() != CraftingScreenHandler.class) {
			return;
		}
		// 共享写回重入：本方法由「写回他人槽位」触发时跳过（对方并非实际操作者）。
		if (sharingSync) {
			return;
		}
		this.context.run((world, pos) -> {
			if (!(world instanceof ServerWorld serverWorld)) return;
			templateMod$storeAndSync(serverWorld, pos);
		});
	}

	/**
	 * 配方书（clickRecipe）填充完成：<code>onInputSlotFillStart</code> 置 <code>filling=true</code>
	 * 期间 vanilla 的 <code>onContentChanged</code> 是空操作（不重算结果、不触发我们的
	 * syncLiveGrid 正常存储），结果只在 <code>updateResult</code> 里算出。因此必须在
	 * <code>onInputSlotFillFinish</code>（filling 已清、结果槽已被 updateResult 填入）之后，
	 * 补一次权威存储/写回/广播 —— 否则对方客户端只见材料不见结果
	 * （手动对网格操作一下 = 触发一次 filling=false 的 onContentChanged 才把结果带上存储）。
	 * 仅服务端（ServerWorld 参数由 vanilla 传入）执行，客户端镜像此方法不走 context 无副作用。
	 */
	@Inject(method = "onInputSlotFillFinish", at = @At("TAIL"))
	private void templateMod$syncOnInputSlotFillFinish(ServerWorld serverWorld,
			net.minecraft.recipe.RecipeEntry<net.minecraft.recipe.CraftingRecipe> recipe, CallbackInfo ci) {
		this.context.run((world, pos) -> {
			if (!(world instanceof ServerWorld sw)) return;
			templateMod$storeAndSync(sw, pos);
		});
	}

	/**
	 * 从本 handler 读取权威网格+结果并做整套「存储 → 写回其他打开者 → 广播 → 更新朝向」。
	 * 供 onContentChanged（实时编辑）与 onInputSlotFillFinish（配方书补结果）共用。
	 */
	@Unique
	private void templateMod$storeAndSync(ServerWorld serverWorld, BlockPos pos) {
		BlockPos immutablePos = pos.toImmutable();
		List<Slot> slots = this.getInputSlots();
		List<ItemStack> grid = new ArrayList<>(GRID_SIZE);
		for (int i = 0; i < GRID_SIZE; i++) grid.add(i < slots.size() ? slots.get(i).getStack() : ItemStack.EMPTY);
		CraftingScreenHandler self = (CraftingScreenHandler) (Object) this;
		ItemStack result = self.getSlot(0).getStack();
		// 残渣修复：网格已全空（材料撤光）时配方必然无效，结果槽可能仍残留旧值
		// （vanilla 清空结果槽与 onContentChanged 存在时序差，store 可能读到过期结果）。
		// 此时强制结果为 EMPTY，避免存储残留旧结果——否则退出 GUI 后保留分支会用它
		// 重新渲染出已消失的结果物/重复退场（用户反馈：点第二配方后退出结果反复退场）。
		if (grid.stream().allMatch(s -> s == null || s.isEmpty())) {
			result = ItemStack.EMPTY;
		}
		if (DIAG) {
			long nonEmpty = grid.stream().filter(s -> s != null && !s.isEmpty()).count();
			String resultName = result.isEmpty() ? "<empty>" : result.getItem().toString();
			TemplateMod.LOGGER.warn("[DIAG-recipe] t={} 存储 gridNonEmpty={} nonEmptySlots={} resultEmpty={} result={}",
					this.player.getUuid(), nonEmpty > 0, nonEmpty, result.isEmpty(), resultName);
		}
		// ① 权威内容：仅更新内存缓存（不落盘，见 CraftingGridStorage.storeMemory——
		// 每次编辑落盘会造成服务端主线程磁盘 IO 抖动；落盘交给关桌/破坏/服务器停止）。
		// 每次 storeMemory 都新建 GridData（含 normalize 的深拷贝），渲染线程的
		// 引用快照据此检测内容变化（对象身份 = 内容版本）。
		CraftingGridStorage.storeMemory(serverWorld, pos, grid, result);
		// ② 共享写回：所有打开该桌的玩家（除自己）handler 网格 + 结果槽同步为权威内容。
		sharingSync = true;
		try {
			for (UUID openerUuid : OpenTables.getPlayers(immutablePos)) {
				if (openerUuid.equals(this.player.getUuid())) {
					continue;
				}
				ServerPlayerEntity opener = serverWorld.getServer().getPlayerManager().getPlayer(openerUuid);
				if (opener == null) {
					continue;
				}
				net.minecraft.screen.ScreenHandler sh = opener.currentScreenHandler;
				if (!(sh instanceof CraftingScreenHandler other)
						|| other == (CraftingScreenHandler) (Object) this) {
					continue;
				}
				syncSlotsTo(other, grid, result, serverWorld);
			}
		} finally {
			sharingSync = false;
		}
		// ③ 广播给追踪区块的所有客户端（悬浮预览同步）：合并到 tick 末统一广播
		// （P0-2，同一 tick 多次内容变化只发一次；内容从权威缓存读最新）。
		GridBroadcastScheduler.mark(serverWorld, immutablePos);
		// ④ 「最后实际操作者」朝向：网格内容变化 = 实际操作。覆盖开桌基准并广播，
		// 让所有客户端（含编辑者本人）的预览朝向转向当前正在编辑的玩家；
		// B 空手点开不做内容变化，不会抢走方向（仍跟随 A）。
		if (this.player instanceof ServerPlayerEntity operator) {
			int sector = TableFacing.computeSector(operator.getX(), operator.getZ(), immutablePos);
			TableFacing.setFacing(immutablePos, sector, operator.getUuid());
			CraftingPreviewNetworking.broadcastFacing(serverWorld, immutablePos, sector, operator.getUuid());
		}
	}

	/** 把权威网格/结果同步进另一个打开的 handler（diff 幂等；规范化附魔防断线）。 */
	private static void syncSlotsTo(CraftingScreenHandler other, List<ItemStack> grid,
			ItemStack result, ServerWorld serverWorld) {
		net.minecraft.registry.Registry<net.minecraft.enchantment.Enchantment> reg =
				serverWorld.getServer().getRegistryManager()
						.getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
		List<Slot> slots = other.getInputSlots();
		for (int i = 0; i < slots.size() && i < GRID_SIZE; i++) {
			ItemStack target = i < grid.size() ? grid.get(i) : ItemStack.EMPTY;
			if (target == null) {
				target = ItemStack.EMPTY;
			}
			if (!ItemStack.areEqual(slots.get(i).getStack(), target)) {
				slots.get(i).setStack(target.isEmpty()
						? ItemStack.EMPTY : CraftingGridStorage.canonicalizeEnchantments(reg, target.copy()));
			}
		}
		Slot resultSlot = other.getSlot(0);
		ItemStack targetResult = result != null ? result : ItemStack.EMPTY;
		if (!ItemStack.areEqual(resultSlot.getStack(), targetResult)) {
			resultSlot.setStack(targetResult.isEmpty()
					? ItemStack.EMPTY : CraftingGridStorage.canonicalizeEnchantments(reg, targetResult.copy()));
		}
	}


	/**
	 * 关闭工作台时在 vanilla 的 {@code context.run(... dropInventory ...)} 之前拦截。
	 *
	 * <p>关闭是双向调用：客户端镜像（{@code closeScreenHandler}）与服务端（收到关闭数据包）
	 * 都会执行 {@code onClosed}。客户端镜像的 {@code context} 为 EMPTY、跑不了
	 * {@code context.run}；服务端在真实 {@code context.run} 里执行最终语义。
	 *
	 * <p><b>共享合成网格关闭语义</b>（用户需求：双方 GUI 完全实时同步）：
	 * <ul>
	 *   <li><b>还有其他打开者</b>：本玩家网格只是共享权威的镜像视图——取消 vanilla 归还
	 *       （否则会把共享物品复制给本玩家），清空自己的槽位即可，不动权威、不广播；
	 *       剩余打开者继续持有共享内容。</li>
	 *   <li><b>最后一名打开者 + keep 开启</b>：取消归还，权威存存储（下次打开恢复）。
	 *       清空自己槽位，广播权威（含空记录表示清空）。</li>
	 *   <li><b>最后一名打开者 + keep 关闭</b>：不取消 vanilla——网格即权威快照，归还给
	 *       最后一人（物品归属正确、不复制），清除权威记录并广播空。</li>
	 * </ul>
	 */
	@Inject(method = "onClosed",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/screen/ScreenHandlerContext;run(Ljava/util/function/BiConsumer;)V"),
			cancellable = true)
	private void templateMod$keepGridOnClose(PlayerEntity player, CallbackInfo ci) {
		// 仅原版合成台：子类菜单（VisualWorkbench 等）走自己的关桌逻辑（方块实体存档），
		// 不拦截、不取消、不写本 mod 的存储，避免干扰其自身的物品归还/保存。
		if (((Object) this).getClass() != CraftingScreenHandler.class) {
			return;
		}
		// 关闭界面即清空 tracker（与 keep 开关无关）：客户端镜像 closeScreenHandler 与服务端关闭
		// 都会调用 onClosed，任一侧先执行都会清掉，避免残留坐标在无 GUI 时被误用（渲染器仅在
		// CraftingScreen 打开时读取 tracker，且会校验该位置仍是工作台）。
		OpenTableTracker.clear();
		// keep 判定改用「关闭者本人的偏好」（客户端经 C2S 上报，PlayerKeepPrefs 记录），
		// 而非服务端全局配置——修复「房主关掉 keep 后其他玩家（keep 开启）关桌不保留」。
		boolean keep = PlayerKeepPrefs.getOrDefault(player.getUuid());

		List<Slot> slots = this.getInputSlots();
		List<ItemStack> grid = new ArrayList<>(GRID_SIZE);
		for (int i = 0; i < slots.size() && i < GRID_SIZE; i++) {
			grid.add(slots.get(i).getStack());
		}
		CraftingScreenHandler self = (CraftingScreenHandler) (Object) this;
		ItemStack result = self.getSlot(0).getStack();

		final List<Slot> finalSlots = slots;
		final List<ItemStack> finalGrid = grid;
		final ItemStack finalResult = result;
		final boolean finalKeep = keep;
		this.context.run((world, pos) -> {
			if (world instanceof ServerWorld serverWorld) {
				BlockPos immutablePos = pos.toImmutable();
				String dimensionKey = world.getRegistryKey().getValue().toString();
				boolean hasOthers = OpenTables.hasOtherOpeners(immutablePos, player.getUuid());
				OpenTables.remove(immutablePos, player.getUuid());
				// 清空槽位/归还都不得触发 syncLiveGrid：
				// slot.setStack(EMPTY) 会触发 onContentChanged → 本方法 TAIL inject →
				// store(空网格) 覆盖权威 + 广播空 + 写回他人槽位，把共享内容清空（实测 bug）。
				sharingSync = true;
				try {
					if (hasOthers) {
						// 共享中：取消 vanilla 归还（防复制），只清空自己的镜像槽；不动权威、不广播。
						ci.cancel();
						for (Slot slot : finalSlots) {
							slot.setStack(ItemStack.EMPTY);
						}
						self.getSlot(0).setStack(ItemStack.EMPTY);
						return;
					}
					// 最后一名打开者：走 keep 语义。
					if (finalKeep) {
						ci.cancel();
						CraftingGridStorage.store(serverWorld, pos, finalGrid, finalResult);
						for (Slot slot : finalSlots) {
							slot.setStack(ItemStack.EMPTY);
						}
						self.getSlot(0).setStack(ItemStack.EMPTY);
						CraftingPreviewNetworking.broadcastStored(serverWorld, immutablePos, dimensionKey,
								finalGrid, finalResult);
					} else {
						// keep 关闭：不取消 vanilla（网格即权威快照，归还给最后一人，物品归属正确）；
						// 清除权威记录并广播空，防止下次打开时旧内容复活。
						CraftingGridStorage.remove(serverWorld, pos);
						CraftingPreviewNetworking.broadcastStored(serverWorld, immutablePos, dimensionKey,
								List.of(), ItemStack.EMPTY);
					}
				} finally {
					sharingSync = false;
				}
			}
		});
	}
}
