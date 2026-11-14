package dev.enderskills.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.EnderSkills;
import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.logic.Progression;
import dev.enderskills.logic.XpUtil;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.skill.SkillTree;
import dev.enderskills.skill.Skills;

/** The altar: one tab per class, one page per element, a skill tree, and the altar's XP storage. */
public class AltarScreen extends Screen {
	private static final int W = 256;
	private static final int H = 236;
	private static final int NODE = 22;
	private static Skills.Tab lastTab = Skills.Tab.DEFENSE;
	private static int lastPage;

	private final float power;
	private final boolean ultimate;
	private final int crystals;
	private Skills.Tab tab = lastTab;
	private int page = lastPage;
	private String selected;
	private boolean confirmReset;
	private int left;
	private int top;
	private Button upgradeButton;
	private Button resetButton;

	public AltarScreen(EsNetwork.OpenAltar payload) {
		super(Component.translatable("enderskills.altar.title"));
		this.power = payload.power();
		this.ultimate = payload.ultimate();
		this.crystals = payload.crystals();
	}

	private SkillData data() {
		Player player = minecraft.player;
		return player == null ? SkillData.create() : player.getAttachedOrElse(Progression.DATA, SkillData.create());
	}

	private List<Skills.Page> pages() {
		return Skills.pages(tab);
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = (height - H) / 2;
		int x = left + 6;
		for (Skills.Tab t : Skills.Tab.values()) {
			Skills.Tab target = t;
			addRenderableWidget(Button.builder(Component.translatable("enderskills.tab." + t.id), b -> {
				tab = target;
				lastTab = target;
				page = 0;
				lastPage = 0;
				selected = null;
				rebuildWidgets();
			}).bounds(x, top + 18, 80, 16).build()).active = t != tab;
			x += 82;
		}
		addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1)).bounds(left + 6, top + 38, 16, 14).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1)).bounds(left + W - 22, top + 38, 16, 14).build());

		int by = top + H - 22;
		addRenderableWidget(Button.builder(Component.translatable("enderskills.altar.store"), b -> send(EsNetwork.AltarAction.STORE_XP, "",
				hasShiftDown() ? -1 : oneLevel())).bounds(left + 6, by, 60, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("enderskills.altar.take"), b -> send(EsNetwork.AltarAction.TAKE_XP, "",
				hasShiftDown() ? -1 : oneLevel())).bounds(left + 68, by, 60, 16).build());
		resetButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			if (confirmReset) {
				send(EsNetwork.AltarAction.RESET, "", 0);
				confirmReset = false;
				selected = null;
			} else {
				confirmReset = true;
			}
		}).bounds(left + 130, by, 120, 16).build());
		upgradeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			if (selected != null) {
				send(EsNetwork.AltarAction.UPGRADE, selected, 0);
			}
		}).bounds(left + W - 96, top + 190, 90, 16).build());
		updateButtons();
	}

	private static boolean hasShiftDown() {
		return net.minecraft.client.Minecraft.getInstance().hasShiftDown();
	}

	/** Points of one experience level (to store or take one level at a time). */
	private int oneLevel() {
		Player player = minecraft.player;
		if (player == null) {
			return 0;
		}
		return Math.max(7, XpUtil.total(player) - XpUtil.forLevel(Math.max(0, player.experienceLevel - 1)));
	}

	private void turn(int dir) {
		int n = pages().size();
		page = Math.floorMod(page + dir, n);
		lastPage = page;
		selected = null;
	}

	private void send(int action, String skill, int amount) {
		ClientPlayNetworking.send(new EsNetwork.AltarAction(action, skill, amount));
	}

	private void updateButtons() {
		SkillData data = data();
		int max = EsSettings.get().maxResetUnlocks;
		resetButton.setMessage(confirmReset
				? Component.translatable("enderskills.altar.reset.confirm").withStyle(ChatFormatting.RED)
				: Component.translatable("enderskills.altar.reset", Math.max(0, max - data.resets()), max));
		resetButton.active = !data.skills().isEmpty() && data.resets() < max;
		if (selected == null) {
			upgradeButton.visible = false;
			return;
		}
		upgradeButton.visible = true;
		upgradeButton.setMessage(Component.translatable(data.owns(selected) ? "enderskills.altar.upgrade" : "enderskills.altar.unlock"));
		upgradeButton.active = status(data, selected) == Status.AVAILABLE;
	}

	@Override
	public void tick() {
		updateButtons();
	}

	private enum Status { OWNED_MAX, AVAILABLE, NO_FUNDS, BLOCKED }

	private Status status(SkillData data, String skill) {
		String blocked = SkillTree.blocked(data, skill, power, ultimate);
		if ("max".equals(blocked)) {
			return Status.OWNED_MAX;
		}
		if (blocked != null) {
			return Status.BLOCKED;
		}
		Player player = minecraft.player;
		int xp = data.storedXp() + (player == null ? 0 : XpUtil.total(player));
		if (data.enderLevel() < SkillTree.levelsNeeded(data, skill) || xp < SkillTree.xpCost(data, skill)) {
			return Status.NO_FUNDS;
		}
		return Status.AVAILABLE;
	}

	// ------------------------------------------------------------------ layout

	private int nodeX(int column) {
		return left + 16 + column * 34;
	}

	private int nodeY(int row) {
		return top + 58 + row * 26;
	}

	private record Node(String skill, int x, int y) {
	}

	private List<Node> nodes() {
		List<Node> list = new ArrayList<>();
		Skills.Page p = pages().get(Math.min(page, pages().size() - 1));
		for (int slot = 0; slot < 6; slot++) {
			list.add(new Node(p.abilities().get(slot), nodeX(Skills.Page.column(slot)), nodeY(Skills.Page.row(slot))));
		}
		List<String> attrs = Skills.ATTRIBUTES.get(tab);
		for (int i = 0; i < attrs.size(); i++) {
			list.add(new Node(attrs.get(i), left + 16 + i * 34 + (i >= 3 ? 12 : 0), top + 146));
		}
		return list;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		g.fill(left, top, left + W, top + H, 0xF0100818);
		g.renderOutline(left, top, W, H, 0xFF000000 | tab.color);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		SkillData data = data();
		g.drawString(font, title, left + 8, top + 6, 0xFFE0C8FF, false);
		Component powerText = Component.translatable(ultimate ? "enderskills.altar.ultimate" : "enderskills.altar.power",
				Math.round(power * 100), crystals);
		g.drawString(font, powerText, left + W - 8 - font.width(powerText), top + 6, 0xFFB0A0D0, false);

		Skills.Page p = pages().get(Math.min(page, pages().size() - 1));
		Component pageTitle = Component.translatable("enderskills.page." + p.element());
		g.drawString(font, pageTitle, left + (W - font.width(pageTitle)) / 2, top + 41, 0xFF000000 | tab.color, true);

		// connections of the tree
		int line = 0xFF6A5A80;
		hLine(g, nodeX(0), nodeX(2), nodeY(1), line);
		vLine(g, nodeX(1) + NODE / 2, nodeY(0), nodeY(2), line);
		hLine(g, nodeX(2), nodeX(4), nodeY(0), line);
		hLine(g, nodeX(2), nodeX(4), nodeY(2), line);
		vLine(g, nodeX(3) + NODE / 2, nodeY(0), nodeY(2), line);
		hLine(g, nodeX(4), nodeX(6), nodeY(1), line);
		vLine(g, nodeX(5) + NODE / 2, nodeY(0), nodeY(2), line);
		g.drawString(font, Component.translatable("enderskills.altar.attributes"), left + 16, top + 134, 0xFF9A8AB0, false);

		Node hovered = null;
		for (Node n : nodes()) {
			drawNode(g, data, n);
			if (mouseX >= n.x() && mouseX < n.x() + NODE && mouseY >= n.y() && mouseY < n.y() + NODE) {
				hovered = n;
			}
		}

		// player info
		Player player = minecraft.player;
		int xp = player == null ? 0 : XpUtil.total(player);
		g.drawString(font, Component.translatable("enderskills.altar.level", data.enderLevel(), Math.round(data.progress() * 100)),
				left + 8, top + 176, 0xFFE0C8FF, false);
		g.drawString(font, Component.translatable("enderskills.altar.stored", data.storedXp()), left + 8, top + 188, 0xFF80FF20, false);
		g.drawString(font, Component.translatable("enderskills.altar.your_xp", xp), left + 8, top + 200, 0xFF80FF20, false);

		if (selected != null) {
			drawSelected(g, data);
		}
		if (hovered != null) {
			g.setComponentTooltipForNextFrame(font, tooltip(data, hovered.skill()), mouseX, mouseY);
		}
	}

	private void hLine(GuiGraphics g, int x1, int x2, int y, int color) {
		g.fill(x1 + NODE / 2, y + NODE / 2, x2 + NODE / 2 + 1, y + NODE / 2 + 1, color);
	}

	private void vLine(GuiGraphics g, int x, int y1, int y2, int color) {
		g.fill(x, y1 + NODE / 2, x + 1, y2 + NODE / 2 + 1, color);
	}

	private void drawNode(GuiGraphics g, SkillData data, Node n) {
		Status status = status(data, n.skill());
		boolean owned = data.owns(n.skill());
		int border = owned ? 0xFF40D060 : switch (status) {
			case AVAILABLE -> 0xFFFFD040;
			case NO_FUNDS -> 0xFFB08030;
			default -> 0xFF404040;
		};
		if (n.skill().equals(selected)) {
			border = 0xFFFFFFFF;
		}
		g.fill(n.x(), n.y(), n.x() + NODE, n.y() + NODE, owned ? 0xFF1E3A26 : 0xFF1A1424);
		g.renderOutline(n.x(), n.y(), NODE, NODE, border);
		g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + n.skill() + ".png"), n.x() + 3, n.y() + 3,
				0.0F, 0.0F, 16, 16, 32, 32, 32, 32, owned || status != Status.BLOCKED ? 0xFFFFFFFF : 0xFF707070);
		if (owned) {
			String lv = Integer.toString(data.level(n.skill()));
			g.drawString(font, lv, n.x() + NODE - font.width(lv) - 1, n.y() + NODE - 8, 0xFFFFFFFF, true);
		}
	}

	private void drawSelected(GuiGraphics g, SkillData data) {
		int x = left + W - 120;
		int y = top + 170;
		g.drawString(font, Component.translatable("skill.enderskills." + selected), x, y, 0xFFFFFFFF, false);
		Status status = status(data, selected);
		Component line = status == Status.OWNED_MAX
				? Component.translatable("enderskills.altar.maxed")
				: Component.translatable("enderskills.altar.cost", SkillTree.xpCost(data, selected), SkillTree.levelsNeeded(data, selected));
		g.drawString(font, line, x, y + 10, status == Status.AVAILABLE ? 0xFF80FF20 : 0xFFD08080, false);
	}

	private List<Component> tooltip(SkillData data, String skill) {
		List<Component> lines = new ArrayList<>();
		Skills.Skill s = Skills.get(skill);
		lines.add(Component.translatable("skill.enderskills." + skill).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		lines.add(Component.translatable("skill.enderskills." + skill + ".desc").withStyle(ChatFormatting.GRAY));
		int level = data.level(skill);
		int max = SkillConfigs.maxLevel(skill);
		if (level >= 0) {
			lines.add(Component.translatable("enderskills.altar.level_of", level, max >= 1000 ? "∞" : max).withStyle(ChatFormatting.WHITE));
		} else {
			lines.add(Component.translatable("enderskills.altar.locked").withStyle(ChatFormatting.DARK_GRAY));
		}
		if (s != null && !s.isAbility() && level >= 0) {
			lines.add(Component.translatable("enderskills.altar.value", format(skill, level)).withStyle(ChatFormatting.AQUA));
		}
		if (s != null && s.isAbility() && !dev.enderskills.skill.Abilities.implemented(skill)) {
			lines.add(Component.translatable("enderskills.altar.not_yet").withStyle(ChatFormatting.GOLD));
		}
		String blocked = SkillTree.blocked(data, skill, power, ultimate);
		if (blocked != null && !"max".equals(blocked)) {
			lines.add(Component.translatable("enderskills.altar.blocked." + blocked).withStyle(ChatFormatting.RED));
		} else if (blocked == null) {
			MutableComponent cost = Component.translatable("enderskills.altar.cost", SkillTree.xpCost(data, skill),
					SkillTree.levelsNeeded(data, skill));
			lines.add(cost.withStyle(ChatFormatting.GREEN));
		}
		return lines;
	}

	/** Shown value of an attribute (percent, hearts, endurance...). */
	private static String format(String attribute, int level) {
		double v = SkillConfigs.value(attribute, "MODIFIER", level);
		return switch (attribute) {
			case "heart_boost" -> String.format("+%.1f ❤", v / 2.0);
			case "endurance" -> String.format("+%.0f", v);
			case "knockback" -> String.format("+%.2f", v);
			default -> String.format("+%.1f%%", v * 100.0);
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (Node n : nodes()) {
				if (event.x() >= n.x() && event.x() < n.x() + NODE && event.y() >= n.y() && event.y() < n.y() + NODE) {
					selected = n.skill();
					confirmReset = false;
					updateButtons();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
