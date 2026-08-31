package com.example.crafting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「关闭工作台时保留合成材料」的持久化存储（common：服务端读写文件，客户端只碰内存缓存）。
 *
 * <p>1.21.11 原版合成台没有方块实体（菜单由 {@code CraftingTableBlock} 直接创建），
 * 无法把物品存进方块实体；因此按「维度 + 坐标」把合成网格物品归档到一个 JSON 文件
 * （{@code config/real-time-crafting-table-crafting-storage.json}），下次打开同一位置的工作台时恢复。
 *
 * <p>每个位置保存 {@link GridData}：9 格输入 + 关闭时重算出的结果。结果槽用于
 * GUI 关闭后预览仍能显示合成产物。
 *
 * <p>物品用 {@link ItemStack#CODEC} + {@link RegistryOps} 序列化，可完整保留 1.21
 * 数据组件（附魔、改名、damage 等）。
 *
 * <p><b>联机支持后的访问矩阵（同一份静态缓存、三个 JVM 场景）：</b>
 * <ul>
 *   <li><b>单机（集成服务器）</b>：服务端线程 {@link #store}/{@link #peek}（落盘），
 *       客户端镜像关桌时 {@link #clientApplyIfChanged}（即时写缓存，防闪没），渲染线程
 *       {@link #peekAll} 读——三者共享同一 JVM 的缓存（原有行为）；</li>
 *   <li><b>独立服务器的服务端 JVM</b>：只有 {@link #store}/{@link #peek} 走落盘；
 *       {@link #clientApplyIfChanged} 不触发（无客户端镜像），{@link #peekAll} 无人调用；</li>
 *   <li><b>远程客户端 JVM</b>：只有 {@link #clientApplyIfChanged}（网络接收器写入）与
 *       {@link #peekAll}（渲染线程读）被使用——缓存可能从未经服务端 ensureLoaded 初始化，
 *       故 {@link #clientApplyIfChanged} 遇 {@code cache == null} 时自行建空表。</li>
 * </ul>
 *
 * <p>线程约定：所有方法在同一把 {@link #LOCK} 上同步，保证 HashMap 跨线程可见性。
 */
public final class CraftingGridStorage {

	/** 一个位置归档的完整预览数据：9 格输入 + 关闭时重算出的结果（可为空）。 */
	public record GridData(List<ItemStack> inputs, ItemStack result) {

		/** 是否有任何可见内容（任一格非空或结果非空）。 */
		public boolean isEmpty() {
			if (result != null && !result.isEmpty()) {
				return false;
			}
			if (inputs == null) {
				return true;
			}
			for (ItemStack s : inputs) {
				if (s != null && !s.isEmpty()) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * 存储中一个位置的非空保留预览：位置 + 数据。供渲染线程整表扫描当前维度，
	 * 实现「关闭界面后保留预览始终显示」（不依赖玩家视线）。
	 */
	public record StoredPreview(BlockPos pos, GridData data) {
	}

	/** 供玩家加入时的全量补发：带维度键的记录（跨维度一并发送，客户端按需过滤）。 */
	public record SyncEntry(String dimensionKey, BlockPos pos, GridData data) {
	}

	private static final int GRID_SIZE = 9;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 所有读写共用的锁（server 线程 / 网络接收线程 / 渲染线程），保证 HashMap 可见性。 */
	private static final Object LOCK = new Object();

	private static Map<String, GridData> cache;
	private static boolean loaded;

	private CraftingGridStorage() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("real-time-crafting-table-crafting-storage.json");
	}

	private static String key(String dimensionId, BlockPos pos) {
		return dimensionId + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static String key(ServerLevel world, BlockPos pos) {
		return key(world.dimension().identifier().toString(), pos);
	}

	private static RegistryOps<JsonElement> ops(ServerLevel world) {
		return ops(world.getServer().registryAccess());
	}

	/** 注册表来源只需 Frozen（ServerLevel 的或 MinecraftServer 的皆可）。 */
	private static RegistryOps<JsonElement> ops(RegistryAccess.Frozen registryManager) {
		return RegistryOps.create(JsonOps.INSTANCE, registryManager);
	}

	/** 把 9 格输入规范化为恰好 9 项（不足补空、多则截断），并对每项防御性拷贝。 */
	private static List<ItemStack> normalize(List<ItemStack> src) {
		List<ItemStack> copy = new ArrayList<>(GRID_SIZE);
		for (int i = 0; i < GRID_SIZE; i++) {
			if (src != null && i < src.size() && src.get(i) != null) {
				copy.add(src.get(i).copy());
			} else {
				copy.add(ItemStack.EMPTY);
			}
		}
		return copy;
	}

	/**
	 * 把附魔组件按 {@code ResourceKey} 重解引用为注册表规范条目。
	 *
	 * <p>动机：部分附魔相关整合包（如 enchantment-table 自定义附魔台）给物品附加魔时，
	 * 附魔条目携带的 {@code Enchantment} <b>值对象</b>并非注册表内的规范实例（对象身份不同，
	 * 仅 key 相同）。原版/自订包用 {@code ItemStack.PACKET_CODEC} 编码时按值对象身份查 raw id
	 * （{@code Registry.getRawId}），查不到就抛 {@code Can't find id for Reference{...}} 断线。
	 * 这里用 {@code reg.get(key)} 拿注册表规范条目重建组件，值对象与注册表一致，编码不再炸。
	 * 无法重解引用的条目（无 key 或 key 缺失）直接丢弃——这类条目本来也无法过网络编码。
	 *
	 * @param reg 服务端附魔注册表（能解析全部 key）
	 * @param stack 待规范化栈（未改原对象，返回副本或原对象）
	 */
	public static ItemStack canonicalizeEnchantments(
			net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> reg, ItemStack stack) {
		// 26.2（Java Edition 26.2）不再需要此项规范化：
		//   - 附魔组件在 26.2 已是 Holder 体系（net.minecraft.core.Holder），网络传输走 ResourceKey
		//     按 id 查找，不依赖条目对象身份（旧 1.21 时代 PACKET_CODEC 按 raw id 查值对象身份
		//     才会炸「Can't find id for Reference」）；
		//   - 本模组联机包已改用 JSON 字符串传输（CraftingGridStoredS2CPacket.toJson/fromJson），
		//     走 RegistryOps + ItemStack.CODEC，按 key 编解码，天然规避对象身份问题。
		// 故直接返回原栈（保留签名以兼容调用方）。
		return stack;
	}

	/**
	 * 把合成网格（前 {@code GRID_SIZE} 个槽位）及结果槽存入该位置并<b>落盘</b>；
	 * 随后调用方负责清空网格。低频「终态」时机使用：关桌保留（最后一人）等。
	 */
	public static void store(ServerLevel world, BlockPos pos, List<ItemStack> inputs, ItemStack result) {
		storeMemory(world, pos, inputs, result);
		save(world);
	}

	/**
	 * 仅更新内存缓存（<b>不落盘</b>）：共享网格每次内容变化（onContentChanged）调用。
	 * 编辑频率远高于关桌/破坏——若每次都落盘，会在服务端主线程产生磁盘 IO 抖动
	 * （性能优化 P0-1，实测每点一下都写 JSON 文件）。
	 * 落盘交给 {@link #store}（终态）、{@link #remove}（破坏）或 {@link #persist}（服务器停止）。
	 */
	public static void storeMemory(ServerLevel world, BlockPos pos, List<ItemStack> inputs, ItemStack result) {
		synchronized (LOCK) {
			ensureLoaded(world);
			String k = key(world, pos);
			net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> reg =
					world.getServer().registryAccess()
							.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
			List<ItemStack> norm = normalize(inputs);
			List<ItemStack> canon = new ArrayList<>(norm.size());
			for (ItemStack s : norm) {
				canon.add(canonicalizeEnchantments(reg, s));
			}
			ItemStack canonResult = canonicalizeEnchantments(reg,
					result == null ? ItemStack.EMPTY : result.copy());
			cache.put(k, new GridData(canon, canonResult));
		}
	}

	/**
	 * 把当前内存缓存落盘（服务器正常停止时的兜底：编辑只写内存，若不落盘会丢最近编辑）。
	 * 缓存未加载或为空时不写文件。调用方须持有 {@link #LOCK}（save 不自行加锁）。
	 */
	public static void persist(ServerLevel world) {
		synchronized (LOCK) {
			if (cache == null || cache.isEmpty()) {
				return;
			}
			save(world);
		}
	}

	/**
	 * 读取该位置归档的数据（<b>不移除</b>），供打开界面时恢复到合成网格；无记录返回
	 * {@code null}。与曾经的 {@code take} 差异：记录在 GUI 打开期间仍留在存储缓存中，渲染线程的
	 * {@link #peekAll} 持续可见——避免「打开界面时记录被瞬时移除、实时内容尚未接管」的渲染空档
	 * （预览闪没一瞬，实机反馈「点开工作台预览闪一下」）。关闭时 {@link #store} 会覆盖该记录。
	 */
	public static GridData peek(ServerLevel world, BlockPos pos) {
		synchronized (LOCK) {
			ensureLoaded(world);
			return cache.get(key(world, pos));
		}
	}

	/**
	 * 移除某位置的归档记录并落盘（工作台被挖掉/替换时调用）。调用方负责先把需掉落的
	 * 物品当作实体生成（本方法只管清数据，避免「下次放同一位置又残留物品」）。
	 * 客户端侧由调用方通过广播全空记录同步清除（本方法不处理网络）。
	 */
	public static void remove(ServerLevel world, BlockPos pos) {
		synchronized (LOCK) {
			ensureLoaded(world);
			cache.remove(key(world, pos));
			save(world);
		}
	}

	/**
	 * 客户端安全的写入（<b>不落盘</b>、不需要服务端 registry ops），两个调用方：
	 * <ul>
	 *   <li>客户端镜像 handler 的 {@code onClosed}（其 {@code context} 为 EMPTY、跑不了
	 *       {@code context.run}）立即把网格内容写进缓存，渲染线程 {@link #peekAll} 即刻可见——
	 *       填补「实时源停止、服务端 store 还要等关闭数据包往返」之间的渲染空档（实机反馈
	 *       「打开/关闭预览会闪一下」）。服务端随后会经 {@link #store} 完整覆盖并落盘；
	 *       覆盖时 ensureLoaded 从文件重载会顺带清掉本写入，但 store 紧接着写回同一记录，净效果一致；</li>
	 *   <li>客户端网络接收器收到 {@code CraftingGridStoredS2CPacket} 后写入（远程客户端的
	 *       唯一数据来源；此时缓存从未被服务端 ensureLoaded 初始化，故遇 {@code cache == null}
	 *       自行建空表）。</li>
	 * </ul>
	 *
	 * <p>当同一位置的现存记录与待写入内容完全相等时跳过（幂等防覆盖）。用途：单机（集成服务器）
	 * 下服务端 {@link #store} 已把当前记录写入同一 JVM 的缓存，随后到达的 S2C 包若逐栈相等则不再
	 * 回写——避免历史/并发包用旧数据覆盖服务端刚写入的新记录。远程客户端无此问题（缓存本为空或
	 * 内容不同，正常写入）。
	 *
	 * @param dimensionKey 维度 id 字符串（如 {@code minecraft:overworld}），来自
	 *                     {@link OpenTableTracker#getDimensionKey()}（客户端拿不到 world 对象）
	 */
	public static void clientApplyIfChanged(String dimensionKey, BlockPos pos, List<ItemStack> inputs, ItemStack result) {
		synchronized (LOCK) {
			if (cache == null) {
				cache = new HashMap<>();
			}
			String k = key(dimensionKey, pos);
			GridData existing = cache.get(k);
			List<ItemStack> norm = normalize(inputs);
			ItemStack res = result == null ? ItemStack.EMPTY : result.copy();
			if (existing != null && sameAs(existing, norm, res)) {
				return;
			}
			cache.put(k, new GridData(norm, res));
		}
	}

	/** 现存记录与待写入内容是否逐格相等（物品+数量+组件全等）。 */
	private static boolean sameAs(GridData existing, List<ItemStack> inputs, ItemStack result) {
		if (!ItemStack.matches(result, existing.result())) {
			return false;
		}
		List<ItemStack> cur = existing.inputs();
		for (int i = 0; i < GRID_SIZE; i++) {
			if (!ItemStack.matches(
					i < cur.size() ? cur.get(i) : ItemStack.EMPTY,
					i < inputs.size() ? inputs.get(i) : ItemStack.EMPTY)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 客户端安全读取当前维度所有非空的保留记录（供渲染线程遍历，实现保留预览不依赖视线）。
	 * 仅扫内存缓存（不加载存储文件），键按「维度:坐标」前缀过滤当前维度；损坏键直接跳过。
	 *
	 * @param dimensionKey 当前客户端所在维度的 id 字符串（如 {@code minecraft:overworld}）
	 */
	public static List<StoredPreview> peekAll(String dimensionKey) {
		synchronized (LOCK) {
			if (cache == null) {
				return List.of();
			}
			String dimPrefix = dimensionKey + ":";
			List<StoredPreview> out = new ArrayList<>();
			for (Map.Entry<String, GridData> e : cache.entrySet()) {
				if (e.getKey().startsWith(dimPrefix) && e.getValue() != null && !e.getValue().isEmpty()) {
					BlockPos pos = posFromKey(e.getKey());
					if (pos != null) {
						out.add(new StoredPreview(pos, e.getValue()));
					}
				}
			}
			return out;
		}
	}

	/**
	 * 当前维度是否<b>至少有一条</b>存储记录（含全空记录）。免分配的 O(条目数) 前缀扫描——
	 * 供渲染线程做「漫游早退」：无 GUI 且当前维度无任何记录时，整帧跳过
	 * {@link #peekAll}/{@link #peekAllIncludingEmpty} 的全量遍历与会话（整合包里闲置工作台
	 * 不占存储缓存，此检查基本恒为 false，几乎零成本）。
	 */
	public static boolean hasAny(String dimensionKey) {
		synchronized (LOCK) {
			if (cache == null) {
				return false;
			}
			String dimPrefix = dimensionKey + ":";
			for (String key : cache.keySet()) {
				if (key.startsWith(dimPrefix)) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * 客户端安全读取当前维度<b>全部</b>保留记录（含全空记录），供渲染线程做「同步驱动的
	 * 过渡动画」——内容从非空变空/变化时启动退场。{@link #peekAll} 会过滤全空记录，而退场
	 * 恰恰要看到「记录已变成空」这一转变，故单列此方法（不落盘、不加载文件）。
	 *
	 * @param dimensionKey 当前客户端所在维度的 id 字符串
	 */
	public static List<StoredPreview> peekAllIncludingEmpty(String dimensionKey) {
		synchronized (LOCK) {
			if (cache == null) {
				return List.of();
			}
			String dimPrefix = dimensionKey + ":";
			List<StoredPreview> out = new ArrayList<>();
			for (Map.Entry<String, GridData> e : cache.entrySet()) {
				if (e.getKey().startsWith(dimPrefix) && e.getValue() != null) {
					BlockPos pos = posFromKey(e.getKey());
					if (pos != null) {
						out.add(new StoredPreview(pos, e.getValue()));
					}
				}
			}
			return out;
		}
	}

	/**
	 * 全部维度的非空记录快照（含维度键），供玩家加入服务器时逐条补发初始同步。
	 * 传入 MinecraftServer 的注册表管理器（JOIN 事件拿不到 world）：顺带触发文件加载——
	 * 服务器刚启动还没人开过桌时缓存未加载，直接补发会漏掉上个会话的全部记录。
	 */
	public static List<SyncEntry> peekAllForSync(RegistryAccess.Frozen registryManager) {
		synchronized (LOCK) {
			ensureLoaded(registryManager);
			if (cache == null) {
				return List.of();
			}
			List<SyncEntry> out = new ArrayList<>();
			for (Map.Entry<String, GridData> e : cache.entrySet()) {
				if (e.getValue() == null || e.getValue().isEmpty()) {
					continue;
				}
				int i = e.getKey().lastIndexOf(':');
				BlockPos pos = posFromKey(e.getKey());
				if (i < 0 || pos == null) {
					continue;
				}
				out.add(new SyncEntry(e.getKey().substring(0, i), pos, e.getValue()));
			}
			return out;
		}
	}

	/** 从存储键（{@code dimension:x,y,z}）反解出坐标；格式不符返回 {@code null}。 */
	private static BlockPos posFromKey(String key) {
		int i = key.lastIndexOf(':');
		if (i < 0) {
			return null;
		}
		String[] parts = key.substring(i + 1).split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new BlockPos(
					Integer.parseInt(parts[0]),
					Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void ensureLoaded(ServerLevel world) {
		ensureLoaded(world.getServer().registryAccess());
	}

	private static void ensureLoaded(RegistryAccess.Frozen registryManager) {
		if (loaded) {
			return;
		}
		loaded = true; // 先置位防重入
		cache = new HashMap<>();
		Path path = file();
		if (Files.exists(path)) {
			try {
				RegistryOps<JsonElement> ops = ops(registryManager);
				JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
				if (root != null && root.has("entries")) {
					for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("entries").entrySet()) {
						cache.put(e.getKey(), decodeGridData(ops, e.getValue()));
					}
				}
			} catch (Exception ignored) {
				// 文件损坏/无法解析：视为空存储
			}
		}
	}

	private static void save(ServerLevel world) {
		try {
			Path path = file();
			Files.createDirectories(path.getParent());
			RegistryOps<JsonElement> ops = ops(world);
			JsonObject root = new JsonObject();
			JsonObject entries = new JsonObject();
			for (Map.Entry<String, GridData> e : cache.entrySet()) {
				entries.add(e.getKey(), encodeGridData(ops, e.getValue()));
			}
			root.add("entries", entries);
			Files.writeString(path, GSON.toJson(root));
		} catch (IOException ignored) {
			// 写入失败仅影响该特性，不影响游戏运行
		}
	}

	private static JsonElement encodeGridData(RegistryOps<JsonElement> ops, GridData data) {
		JsonObject obj = new JsonObject();
		obj.add("inputs", encodeStacks(ops, data.inputs()));
		obj.add("result", encodeStack(ops, data.result()));
		return obj;
	}

	private static GridData decodeGridData(RegistryOps<JsonElement> ops, JsonElement element) {
		if (element != null && element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			List<ItemStack> inputs = decodeStacks(ops, obj.has("inputs") ? obj.get("inputs") : null);
			ItemStack result = decodeStack(ops, obj.has("result") ? obj.get("result") : null);
			return new GridData(inputs, result);
		}
		// 旧格式兼容：裸数组 = 仅有输入格，无结果
		return new GridData(decodeStacks(ops, element), ItemStack.EMPTY);
	}

	private static JsonElement encodeStack(RegistryOps<JsonElement> ops, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return JsonNull.INSTANCE;
		}
		DataResult<JsonElement> result = ItemStack.CODEC.encodeStart(ops, stack);
		return result.result().orElse(JsonNull.INSTANCE);
	}

	private static ItemStack decodeStack(RegistryOps<JsonElement> ops, JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return ItemStack.EMPTY;
		}
		DataResult<ItemStack> result = ItemStack.CODEC.parse(ops, element);
		return result.result().orElse(ItemStack.EMPTY);
	}

	private static JsonElement encodeStacks(RegistryOps<JsonElement> ops, List<ItemStack> stacks) {
		JsonArray arr = new JsonArray();
		for (int i = 0; i < GRID_SIZE; i++) {
			ItemStack stack = stacks != null && i < stacks.size() ? stacks.get(i) : null;
			arr.add(encodeStack(ops, stack));
		}
		return arr;
	}

	private static List<ItemStack> decodeStacks(RegistryOps<JsonElement> ops, JsonElement element) {
		List<ItemStack> stacks = new ArrayList<>(GRID_SIZE);
		JsonArray arr = element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
		for (int i = 0; i < GRID_SIZE; i++) {
			ItemStack stack = i < arr.size() ? decodeStack(ops, arr.get(i)) : ItemStack.EMPTY;
			stacks.add(stack);
		}
		return stacks;
	}
}
