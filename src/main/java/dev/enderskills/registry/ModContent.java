package dev.enderskills.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.util.valueproviders.UniformInt;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import dev.enderskills.EnderSkills;
import dev.enderskills.block.AltarBlock;
import dev.enderskills.item.TokenItem;

/** Blocks, items and the creative tab. */
public final class ModContent {
	public static final List<Item> TAB_ITEMS = new ArrayList<>();

	public static final Block OAK_ALTAR = altar("oak_break_altar", false);
	public static final Block SPRUCE_ALTAR = altar("spruce_break_altar", false);
	public static final Block BIRCH_ALTAR = altar("birch_break_altar", false);
	public static final Block JUNGLE_ALTAR = altar("jungle_break_altar", false);
	public static final Block ACACIA_ALTAR = altar("acacia_break_altar", false);
	public static final Block DARK_OAK_ALTAR = altar("dark_oak_break_altar", false);
	public static final Block ULTIMATE_ALTAR = altar("altar_ultimate", true);
	public static final Block CRYSTAL = block("crystal", Block::new, BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_PURPLE).strength(1.5F, 6.0F).sound(SoundType.AMETHYST).noOcclusion()
			.lightLevel(s -> 7));
	public static final Block ENDER_ORE = block("ender_ore",
			p -> new DropExperienceBlock(UniformInt.of(2, 5), p), BlockBehaviour.Properties.of()
					.mapColor(MapColor.STONE).strength(3.0F, 3.0F).requiresCorrectToolForDrops());

	public static final Item TOKEN = item("token", TokenItem::new, new Item.Properties().rarity(Rarity.UNCOMMON));
	public static final Item ENDER_DUST = item("ender_dust", Item::new, new Item.Properties());
	public static final Item CRYSTAL_MATRIX = item("crystal_matrix", Item::new, new Item.Properties().rarity(Rarity.RARE).stacksTo(16));

	private ModContent() {
	}

	private static Block altar(String name, boolean ultimate) {
		return block(name, p -> new AltarBlock(ultimate, p), BlockBehaviour.Properties.of()
				.mapColor(MapColor.WOOD).strength(2.5F, 1200.0F).sound(SoundType.WOOD).noOcclusion()
				.lightLevel(s -> ultimate ? 10 : 5));
	}

	private static Block block(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, EnderSkills.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, EnderSkills.id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		TAB_ITEMS.add(item);
		return block;
	}

	private static Item item(String name, Function<Item.Properties, Item> factory, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, EnderSkills.id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
		TAB_ITEMS.add(item);
		return item;
	}

	public static void init() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, EnderSkills.id("items"), FabricItemGroup.builder()
				.title(Component.translatable("itemGroup.enderskills"))
				.icon(() -> new ItemStack(TOKEN))
				.displayItems((params, output) -> TAB_ITEMS.forEach(output::accept))
				.build());
	}

	public static boolean isAltar(Block block) {
		return block instanceof AltarBlock;
	}
}
