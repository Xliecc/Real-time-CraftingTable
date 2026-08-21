package com.example.client.config;

import com.example.config.PreviewConfig;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Mod Menu 配置页入口（fabric.mod.json 的 {@code modmenu} entrypoint）。
 *
 * <p>依赖情况：
 * <ul>
 *   <li>Mod Menu 未安装：本 entrypoint 根本不会被加载，无影响。</li>
 *   <li>Mod Menu 已装但 Cloth Config 缺失：显示一个提示页（不会崩溃）。</li>
 *   <li>两者都装：弹出 Cloth Config 构建的配置页，编辑 {@link PreviewConfig} 并即时生效。</li>
 * </ul>
 *
 * <p>配置项按「通用 / 物品 / 结果」三个分类组织：只与合成材料相关的放「物品」，
 * 只与最终结果相关的放「结果」，两者共用的（浮动类型）放「通用」。
 *
 * <p>所有显示文本走 {@code assets/minecraft/lang/} 翻译键（前缀 {@code rtct.config.}），
 * 随游戏语言切换中/英。
 */
public final class PreviewModMenu implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
				return missingClothConfigScreen(parent);
			}
			return buildScreen(parent);
		};
	}

	/** 数字格式化的默认区域（固定小数点，避免不同系统对 %f 的中英文差异）。 */
	private static String fmt(String pattern, Object... args) {
		return String.format(Locale.ROOT, pattern, args);
	}

	private static Screen buildScreen(Screen parent) {
		PreviewConfig cfg = PreviewConfig.get();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("rtct.config.title"))
				.setSavingRunnable(cfg::save);
		ConfigEntryBuilder entry = builder.entryBuilder();

		// —— 分类：通用（材料与结果共用） ——
		ConfigCategory commonCat = builder.getOrCreateCategory(Text.translatable("rtct.config.cat.common"));

		// —— 模组总开关：关闭后完全不渲染合成预览（AFTER_ENTITIES 与阴影 pass 都跳过） ——
		commonCat.addEntry(entry.startBooleanToggle(
						Text.translatable("rtct.config.enable.name"), cfg.enabled)
				.setDefaultValue(true)
				.setSaveConsumer(value -> cfg.enabled = value)
				.build());

		commonCat.addEntry(entry.startEnumSelector(
						Text.translatable("rtct.config.floatingType.name"), PreviewConfig.FloatingMode.class, cfg.floatingMode)
				.setDefaultValue(PreviewConfig.FloatingMode.WAVE)
				.setEnumNameProvider(mode -> Text.translatable(switch ((PreviewConfig.FloatingMode) mode) {
					case NONE -> "rtct.config.floatingType.none";
					case WAVE -> "rtct.config.floatingType.wave";
					case SYNC -> "rtct.config.floatingType.sync";
				}))
				.setSaveConsumer(mode -> cfg.floatingMode = mode)
				.build());

		commonCat.addEntry(entry.startEnumSelector(
						Text.translatable("rtct.config.visualStyle.name"), PreviewConfig.IngredientStyle.class, cfg.ingredientStyle)
				.setDefaultValue(PreviewConfig.IngredientStyle.FLOAT)
				.setEnumNameProvider(mode -> Text.translatable(switch ((PreviewConfig.IngredientStyle) mode) {
					case FLOAT -> "rtct.config.visualStyle.float";
					case FLAT -> "rtct.config.visualStyle.flat";
				}))
				.setTooltip(Text.translatable("rtct.config.visualStyle.tooltip"))
				.setSaveConsumer(mode -> cfg.ingredientStyle = mode)
				.build());

		// 面板朝向：跟随玩家旋转（整块面板绕工作台中心轴转向玩家）/ 不随玩家旋转（开桌方向锁定）。
		commonCat.addEntry(entry.startEnumSelector(
						Text.translatable("rtct.config.facing.name"), PreviewConfig.FacingMode.class, cfg.facingMode)
				.setDefaultValue(PreviewConfig.FacingMode.FIXED)
				.setEnumNameProvider(mode -> Text.translatable(switch ((PreviewConfig.FacingMode) mode) {
					case FOLLOW_PLAYER -> "rtct.config.facing.follow";
					case FIXED -> "rtct.config.facing.fixed";
				}))
				.setTooltip(Text.translatable("rtct.config.facing.tooltip"))
				.setSaveConsumer(mode -> cfg.facingMode = mode)
				.build());

		// 朝向转场用时：仅「跟随玩家旋转」时生效（面板跨扇形转场的动画时长，越小转得越快）。
		// 新配置项同步规则：PreviewConfig 字段、setDefaultValue、run 与 PCL2 两份 JSON（只增不改）。
		commonCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.facingAnim.name"),
						(int) Math.round(cfg.facingAnimationSeconds * 10.0), 1, 11)
				.setDefaultValue(6)
				.setTextGetter(tenths -> Text.translatable("rtct.config.facingAnim.seconds",
						fmt("%.1f", tenths / 10.0)))
				.setSaveConsumer(tenths -> cfg.facingAnimationSeconds = tenths / 10.0)
				.build());

		commonCat.addEntry(entry.startBooleanToggle(
						Text.translatable("rtct.config.keep.name"), cfg.keepItemsWhenClosed)
				.setDefaultValue(false)
				.setTooltip(Text.translatable("rtct.config.keep.tooltip"))
				.setSaveConsumer(value -> cfg.keepItemsWhenClosed = value)
				.build());

		// 生长动画：材料/结果出现（放入新物品或更换物品）时从零放大到配置尺寸的弹出动画。
		commonCat.addEntry(entry.startBooleanToggle(
						Text.translatable("rtct.config.growth.name"), cfg.growthEnabled)
				.setDefaultValue(true)
				.setTooltip(Text.translatable("rtct.config.growth.tooltip"))
				.setSaveConsumer(value -> cfg.growthEnabled = value)
				.build());

		commonCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.growthTime.name"),
						(int) Math.round(cfg.growthSeconds * 100.0), 5, 55)
				.setDefaultValue(30)
				.setTextGetter(hundredths -> Text.translatable("rtct.config.growthTime.seconds",
						fmt("%.2f", hundredths / 100.0)))
				.setSaveConsumer(hundredths -> cfg.growthSeconds = hundredths / 100.0)
				.build());

		// 预览渲染距离：超过此距离的工作台预览不构建渲染状态（远处工作台白算=性能浪费）。
		// 0 = 不限（全图渲染）。默认 64 格——够「走近看」，同时省去远处工作台的每帧动画/方位。
		commonCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.renderDist.name"), cfg.renderDistance, 0, 128)
				.setDefaultValue(64)
				.setTextGetter(dist -> dist == 0
						? Text.translatable("rtct.config.renderDist.unlimited")
						: Text.translatable("rtct.config.renderDist.blocks", dist))
				.setTooltip(Text.translatable("rtct.config.renderDist.tooltip"))
				.setSaveConsumer(dist -> cfg.renderDistance = dist)
				.build());

		// —— 分类：物品（合成材料） ——
		ConfigCategory itemCat = builder.getOrCreateCategory(Text.translatable("rtct.config.cat.items"));

		itemCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.floatHeight.name"),
						(int) Math.round(cfg.floatHeight * 100.0), 1, 17)
				.setDefaultValue(9)
				.setTextGetter(hundredths -> Text.translatable("rtct.config.floatHeight.blocks",
						fmt("%.2f", hundredths / 100.0)))
				.setSaveConsumer(hundredths -> cfg.floatHeight = hundredths / 100.0)
				.build());

		itemCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.itemSize.name"),
						(int) Math.round(cfg.materialScale * 100.0), 2, 46)
				.setDefaultValue(24)
				.setTextGetter(hundredths -> Text.translatable("rtct.config.itemSize.blocks",
						fmt("%.2f", hundredths / 100.0)))
				.setSaveConsumer(hundredths -> cfg.materialScale = hundredths / 100.0)
				.build());

		itemCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.slotSpacing.name"),
						(int) Math.round(cfg.slotSpacing * 1000.0), 47, 327)
				.setDefaultValue(187)
				.setTextGetter(thousandths -> Text.translatable("rtct.config.slotSpacing.blocks",
						fmt("%.3f", thousandths / 1000.0)))
				.setSaveConsumer(thousandths -> cfg.slotSpacing = thousandths / 1000.0)
				.build());

		itemCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.floatSpeed.name"),
						(int) Math.round(cfg.floatSeconds), 1, 5)
				.setDefaultValue(3)
				.setTextGetter(sec -> Text.translatable("rtct.config.floatSpeed.seconds", sec))
				.setSaveConsumer(sec -> cfg.floatSeconds = sec)
				.build());

		itemCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.floatAmp.name"),
						(int) Math.round(cfg.floatAmplitude * 1000.0), 0, 8)
				.setDefaultValue(4)
				.setTextGetter(thousandths -> Text.translatable("rtct.config.floatAmp.blocks",
						fmt("%.3f", thousandths / 1000.0)))
				.setSaveConsumer(thousandths -> cfg.floatAmplitude = thousandths / 1000.0)
				.build());

		// —— 分类：结果（最终合成效果） ——
		ConfigCategory resultCat = builder.getOrCreateCategory(Text.translatable("rtct.config.cat.result"));

		resultCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.resultSize.name"),
						(int) Math.round(cfg.resultScale * 100.0), 20, 180)
				.setDefaultValue(100)
				.setTextGetter(hundredths -> Text.translatable("rtct.config.resultSize.blocks",
						fmt("%.2f", hundredths / 100.0)))
				.setSaveConsumer(hundredths -> cfg.resultScale = hundredths / 100.0)
				.build());

		resultCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.rotateSpeed.name"), cfg.rotationSeconds, 1, 15)
				.setDefaultValue(8)
				.setTextGetter(sec -> Text.translatable("rtct.config.rotateSpeed.seconds", sec))
				.setSaveConsumer(sec -> cfg.rotationSeconds = sec)
				.build());

		resultCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.resultHeight.name"),
						(int) Math.round(cfg.resultHeightGap * 100.0), 2, 42)
				.setDefaultValue(22)
				.setTextGetter(hundredths -> Text.translatable("rtct.config.resultHeight.blocks",
						fmt("%.2f", hundredths / 100.0)))
				.setSaveConsumer(hundredths -> cfg.resultHeightGap = hundredths / 100.0)
				.build());

		resultCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.resultFloatSpeed.name"),
						(int) Math.round(cfg.resultFloatSeconds), 1, 7)
				.setDefaultValue(4)
				.setTextGetter(sec -> Text.translatable("rtct.config.resultFloatSpeed.seconds", sec))
				.setSaveConsumer(sec -> cfg.resultFloatSeconds = sec)
				.build());

		resultCat.addEntry(entry.startIntSlider(
						Text.translatable("rtct.config.resultFloatAmp.name"),
						(int) Math.round(cfg.resultFloatAmplitude * 1000.0), 2, 58)
				.setDefaultValue(30)
				.setTextGetter(thousandths -> Text.translatable("rtct.config.resultFloatAmp.blocks",
						fmt("%.3f", thousandths / 1000.0)))
				.setSaveConsumer(thousandths -> cfg.resultFloatAmplitude = thousandths / 1000.0)
				.build());

		return builder.build();
	}

	private static Screen missingClothConfigScreen(Screen parent) {
		return new Screen(Text.translatable("rtct.config.missingTitle")) {
			@Override
			protected void init() {
				super.init();
				this.addDrawableChild(ButtonWidget.builder(Text.translatable("rtct.config.back"), button -> this.close())
						.dimensions(this.width / 2 - 50, this.height / 2, 100, 20)
						.build());
			}
		};
	}
}
