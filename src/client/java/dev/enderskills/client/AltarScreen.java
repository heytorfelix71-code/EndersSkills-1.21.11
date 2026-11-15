package dev.enderskills.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.EnderSkills;
import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.DslConfig;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.logic.Progression;
import dev.enderskills.logic.XpUtil;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.skill.Abilities;
import dev.enderskills.skill.SkillTree;
import dev.enderskills.skill.Skills;

/**
 * Altar and Ender Book screen: classes and trees on the left, the tree in the middle, the details of the
 * hovered / selected skill on the right, XP storage at the bottom, over an animated End sky.
 */
public class AltarScreen extends Screen {
	private static final int NODE = 26;
	private static Skills.Tab lastTab = Skills.Tab.DEFENSE;
	private static int lastPage;

	private final float power;
	private final boolean ultimate;
	private final boolean book;
	private final int crystals;
	private Skills.Tab tab = lastTab;
	private int page = lastPage;
	private String selected;
	private boolean confirmReset;
	private int left;
	private int top;
	private int w;
	private int h;
	private final List<Btn> buttons = new ArrayList<>();

	private record Btn(int x, int y, int w, int h, Component label, boolean enabled, int accent, Runnable action) {
		boolean over(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record Node(String skill, int x, int y) {
		boolean over(double mx, double my) {
			return mx >= x && mx < x + NODE && my >= y && my < y + NODE;
		}
	}

	public AltarScreen(EsNetwork.OpenAltar payload) {
		super(Component.translatable("enderskills.altar.title"));
		this.book = payload.power() < 0;
		this.power = Math.max(0, payload.power());
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

	private Skills.Page currentPage() {
		List<Skills.Page> list = pages();
		return list.get(Math.min(page, list.size() - 1));
	}

	@Override
	protected void init() {
		w = Math.min(width - 12, 500);
		h = Math.min(height - 12, 280);
		left = (width - w) / 2;
		top = (height - h) / 2;
	}

	private void send(int action, String skill, int amount) {
		ClientPlayNetworking.send(new EsNetwork.AltarAction(action, skill, amount));
	}

	private int oneLevel() {
		Player player = minecraft.player;
		return player == null ? 0 : Math.max(7, XpUtil.total(player) - XpUtil.forLevel(Math.max(0, player.experienceLevel - 1)));
	}

	private boolean creativeFree() {
		return EsSettings.get().creativeFree && minecraft.player != null && minecraft.player.isCreative();
	}

	private enum Status { MAXED, AVAILABLE, NO_FUNDS, BLOCKED }

	private Status status(SkillData data, String skill) {
		String blocked = SkillTree.blocked(data, skill, power, ultimate);
		if ("max".equals(blocked)) {
			return Status.MAXED;
		}
		if (creativeFree()) {
			return Status.AVAILABLE;
		}
		if (blocked != null || book) {
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

	private int sideX() {
		return left + 8;
	}

	private int treeX() {
		return left + 112;
	}

	private int detailX() {
		return treeX() + 7 * 32 + 6;
	}

	private int nodeX(int column) {
		return treeX() + 4 + column * 32;
	}

	private int nodeY(int row) {
		return top + 52 + row * 34;
	}

	private List<Node> nodes() {
		List<Node> list = new ArrayList<>();
		Skills.Page p = currentPage();
		for (int slot = 0; slot < 6; slot++) {
			list.add(new Node(p.abilities().get(slot), nodeX(Skills.Page.column(slot)), nodeY(Skills.Page.row(slot))));
		}
		List<String> attrs = Skills.ATTRIBUTES.get(tab);
		for (int i = 0; i < attrs.size(); i++) {
			list.add(new Node(attrs.get(i), treeX() + 4 + i * 36 + (i >= 3 ? 8 : 0), top + h - 82));
		}
		return list;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Cosmos.background(g, width, height);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		buttons.clear();
		SkillData data = data();
		int accent = tab.color;
		Cosmos.panel(g, left, top, w, h, accent);

		// header
		g.drawString(font, Component.literal("✦ ").append(Component.translatable(book ? "item.enderskills.book" : "enderskills.altar.title"))
				.withColor(0xE8D4FF), left + 10, top + 8, 0xFFFFFFFF, true);
		Component mode = book ? Component.translatable("enderskills.altar.book_mode")
				: Component.translatable(ultimate ? "enderskills.altar.ultimate" : "enderskills.altar.power", Math.round(power * 100), crystals);
		g.drawString(font, mode, left + 10 + font.width("✦ ") + font.width(Component.translatable(book ? "item.enderskills.book" : "enderskills.altar.title")) + 10,
				top + 8, 0xFFA898C8, false);
		int lvX = left + w - 150;
		g.drawString(font, Component.translatable("enderskills.altar.level_short", data.enderLevel()), lvX, top + 6, 0xFFE8D4FF, true);
		Cosmos.bar(g, lvX, top + 16, 140, 5, (float) data.progress(), 0x7A2BD9, 0xD07CFF);

		drawSidebar(g, mouseX, mouseY);
		drawTree(g, data, mouseX, mouseY);

		Node hovered = null;
		for (Node n : nodes()) {
			if (n.over(mouseX, mouseY)) {
				hovered = n;
			}
		}
		String shown = hovered != null ? hovered.skill() : selected;
		drawDetails(g, data, shown);
		drawFooter(g, data);

		for (Btn b : buttons) {
			boolean over = b.over(mouseX, mouseY) && b.enabled();
			int fill = !b.enabled() ? 0x80201828 : over ? 0xE0000000 | darken(b.accent(), 0.55F) : 0xC0000000 | darken(b.accent(), 0.3F);
			Cosmos.chip(g, b.x(), b.y(), b.w(), b.h(), fill, b.enabled() ? 0xFF000000 | b.accent() : 0x80504060);
			g.drawCenteredString(font, b.label(), b.x() + b.w() / 2, b.y() + (b.h() - 8) / 2, b.enabled() ? 0xFFFFFFFF : 0xFF7A6E8A);
		}
	}

	private static int darken(int rgb, float f) {
		int r = (int) ((rgb >> 16 & 0xFF) * f);
		int gg = (int) ((rgb >> 8 & 0xFF) * f);
		int b = (int) ((rgb & 0xFF) * f);
		return r << 16 | gg << 8 | b;
	}

	private void drawSidebar(GuiGraphics g, int mouseX, int mouseY) {
		int x = sideX();
		int y = top + 30;
		for (Skills.Tab t : Skills.Tab.values()) {
			boolean on = t == tab;
			Skills.Tab target = t;
			buttons.add(new Btn(x, y, 96, 18, Component.translatable("enderskills.tab." + t.id), true, on ? t.color : darken(t.color, 0.7F), () -> {
				tab = target;
				lastTab = target;
				page = 0;
				lastPage = 0;
				selected = null;
			}));
			y += 21;
			if (on) {
				List<Skills.Page> list = pages();
				for (int i = 0; i < list.size(); i++) {
					int index = i;
					boolean current = i == page;
					int py = y;
					if (current) {
						g.fill(x + 4, py, x + 6, py + 13, 0xFF000000 | t.color);
					}
					g.drawString(font, Component.translatable("enderskills.page." + list.get(i).element()), x + 10, py + 3,
							current ? 0xFFFFFFFF : 0xFFA898C8, current);
					buttons.add(new Btn(x + 8, py, 88, 13, Component.empty(), true, 0, () -> {
						page = index;
						lastPage = index;
						selected = null;
					}));
					y += 15;
				}
				y += 4;
			}
		}
	}

	private void drawTree(GuiGraphics g, SkillData data, int mouseX, int mouseY) {
		Skills.Page p = currentPage();
		List<String> a = p.abilities();
		int lit = 0xFF000000 | tab.color;
		int dim = 0x50FFFFFF;
		Component title = Component.translatable("enderskills.page." + p.element()).withColor(tab.color);
		g.drawString(font, title, treeX() + 4, top + 32, 0xFFFFFFFF, true);
		// connections (bright when the skill at the end is owned)
		link(g, 0, 1, 2, 0, data.owns(a.get(1)) ? lit : dim);
		link(g, 0, 1, 2, 2, data.owns(a.get(2)) ? lit : dim);
		link(g, 2, 0, 4, 0, data.owns(a.get(3)) ? lit : dim);
		link(g, 2, 2, 4, 2, data.owns(a.get(4)) ? lit : dim);
		link(g, 2, 0, 4, 2, data.owns(a.get(4)) && data.owns(a.get(1)) ? lit : 0x28FFFFFF);
		link(g, 2, 2, 4, 0, data.owns(a.get(3)) && data.owns(a.get(2)) ? lit : 0x28FFFFFF);
		link(g, 4, 0, 6, 1, data.owns(a.get(5)) ? lit : dim);
		link(g, 4, 2, 6, 1, data.owns(a.get(5)) ? lit : dim);
		g.drawString(font, Component.translatable("enderskills.altar.attributes"), treeX() + 4, top + h - 96, 0xFF9A8AB0, false);
		for (Node n : nodes()) {
			drawNode(g, data, n, n.over(mouseX, mouseY));
		}
	}

	/** Line between two tree cells (column, row), drawn as a 2px stepped line. */
	private void link(GuiGraphics g, int c1, int r1, int c2, int r2, int color) {
		int x1 = nodeX(c1) + NODE / 2;
		int y1 = nodeY(r1) + NODE / 2;
		int x2 = nodeX(c2) + NODE / 2;
		int y2 = nodeY(r2) + NODE / 2;
		int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
		for (int i = 0; i <= steps; i += 1) {
			int x = x1 + (x2 - x1) * i / steps;
			int y = y1 + (y2 - y1) * i / steps;
			g.fill(x, y, x + 2, y + 2, color);
		}
	}

	private void drawNode(GuiGraphics g, SkillData data, Node n, boolean hover) {
		Status status = status(data, n.skill());
		boolean owned = data.owns(n.skill());
		double t = System.currentTimeMillis() / 50.0;
		int x = n.x();
		int y = n.y();
		int accent = tab.color;
		if (owned) { // soft glow
			for (int i = 3; i >= 1; i--) {
				Cosmos.chip(g, x - i, y - i, NODE + i * 2, NODE + i * 2, (0x14 * (4 - i)) << 24 | accent, 0);
			}
		}
		if (status == Status.AVAILABLE) {
			float pulse = 0.5F + 0.5F * (float) Math.sin(t * 0.25);
			Cosmos.chip(g, x - 2, y - 2, NODE + 4, NODE + 4, 0, ((int) (120 + 135 * pulse) << 24) | 0xFFD54F);
		}
		int border = n.skill().equals(selected) ? 0xFFFFFFFF : owned ? 0xFF000000 | accent
				: status == Status.NO_FUNDS ? 0xFFB08030 : hover ? 0xFF8070A0 : 0xFF3A2E4A;
		Cosmos.chip(g, x, y, NODE, NODE, owned ? 0xF0181026 : 0xF0100A18, border);
		g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + n.skill() + ".png"), x + 3, y + 3,
				0.0F, 0.0F, 20, 20, 32, 32, 32, 32, owned || status != Status.BLOCKED ? 0xFFFFFFFF : 0xFF5A5060);
		if (owned) {
			String lv = Integer.toString(data.level(n.skill()));
			int lw = font.width(lv);
			g.fill(x + NODE - lw - 3, y + NODE - 9, x + NODE, y + NODE, 0xD0000000);
			g.drawString(font, lv, x + NODE - lw - 1, y + NODE - 8, 0xFFFFFFFF, false);
		}
	}

	private void drawDetails(GuiGraphics g, SkillData data, String skill) {
		int x = detailX();
		int dw = left + w - 8 - x;
		int y = top + 30;
		Cosmos.chip(g, x, y, dw, h - 70, 0x90000000, 0x40000000 | tab.color);
		if (skill == null) {
			int ty = y + 8;
			for (FormattedCharSequence line : font.split(Component.translatable("enderskills.altar.hint"), dw - 12)) {
				g.drawString(font, line, x + 6, ty, 0xFFA898C8, false);
				ty += 10;
			}
			return;
		}
		Skills.Skill s = Skills.get(skill);
		g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + skill + ".png"), x + 6, y + 6, 0.0F, 0.0F,
				28, 28, 32, 32, 32, 32, 0xFFFFFFFF);
		int tx = x + 40;
		int ty = y + 8;
		for (FormattedCharSequence line : font.split(Component.translatable("skill.enderskills." + skill).withColor(0xF0E0FF), dw - 46)) {
			g.drawString(font, line, tx, ty, 0xFFFFFFFF, true);
			ty += 10;
		}
		Component kind = Component.translatable(s != null && s.isAbility()
				? (Abilities.implemented(skill) ? "enderskills.altar.kind.ability" : "enderskills.altar.kind.ability")
				: "enderskills.altar.kind.attribute");
		g.drawString(font, kind, tx, Math.max(ty, y + 22), 0xFF9A8AB0, false);
		ty = y + 40;
		for (FormattedCharSequence line : font.split(Component.translatable("skill.enderskills." + skill + ".desc"), dw - 12)) {
			g.drawString(font, line, x + 6, ty, 0xFFC8BCD8, false);
			ty += 10;
		}
		ty += 4;
		int level = data.level(skill);
		int max = SkillConfigs.maxLevel(skill);
		String maxText = max >= 1000 ? "∞" : Integer.toString(max);
		g.drawString(font, level >= 0 ? Component.translatable("enderskills.altar.level_of", level, maxText)
				: Component.translatable("enderskills.altar.locked"), x + 6, ty, level >= 0 ? 0xFFFFFFFF : 0xFF8A7E9A, false);
		ty += 10;
		Cosmos.bar(g, x + 6, ty, dw - 12, 4, level < 0 ? 0 : max >= 1000 ? Math.min(1F, level / 100F) : level / (float) Math.max(1, max),
				0x2A6CCF, tab.color);
		ty += 9;
		// stats: current -> next level
		int now = Math.max(0, level);
		int next = level < 0 ? 0 : Math.min(level + 1, max);
		DslConfig cfg = SkillConfigs.get(skill);
		for (String prop : cfg.map.keySet()) {
			if (prop.equals("XP") || ty > top + h - 70) {
				continue;
			}
			String a = format(prop, cfg.get(prop, now, 1.0));
			String b = format(prop, cfg.get(prop, next, 1.0));
			Component line = Component.translatable("enderskills.stat." + prop.toLowerCase()).append(": ")
					.append(Component.literal(a).withColor(0xFFFFFF))
					.append(level >= 0 && !a.equals(b) ? Component.literal(" → " + b).withColor(0x80FF80) : Component.empty());
			g.drawString(font, line, x + 6, ty, 0xFF9A8AB0, false);
			ty += 10;
		}
		if (s != null && s.isAbility()) {
			g.drawString(font, Component.translatable("enderskills.altar.power_mult", String.format("%.1f", powerMultiplier(data, skill))),
					x + 6, ty, 0xFFD07CFF, false);
		}
		// action
		Status status = status(data, skill);
		int by = top + h - 62;
		if (status == Status.MAXED) {
			g.drawCenteredString(font, Component.translatable("enderskills.altar.maxed"), x + dw / 2, by - 12, 0xFFFFD54F);
			return;
		}
		boolean free = creativeFree();
		String blocked = SkillTree.blocked(data, skill, power, ultimate);
		Component reason = book && !free ? Component.translatable("enderskills.altar.book_needs_altar")
				: blocked != null && !free ? Component.translatable("enderskills.altar.blocked." + blocked)
				: free ? Component.translatable("enderskills.altar.free")
				: Component.translatable("enderskills.altar.cost", SkillTree.xpCost(data, skill), SkillTree.levelsNeeded(data, skill));
		int rty = by - 22;
		for (FormattedCharSequence line : font.split(reason, dw - 12)) {
			g.drawString(font, line, x + 6, rty, status == Status.AVAILABLE ? 0xFF80FF80 : 0xFFFF8A8A, false);
			rty += 10;
		}
		buttons.add(new Btn(x + 6, by, dw - 12, 18, Component.translatable(data.owns(skill) ? "enderskills.altar.upgrade" : "enderskills.altar.unlock"),
				status == Status.AVAILABLE, tab.color, () -> send(EsNetwork.AltarAction.UPGRADE, skill, 0)));
	}

	private double powerMultiplier(SkillData data, String skill) {
		EsSettings e = EsSettings.get();
		Skills.Page p = Skills.pageOf(skill);
		double group = p == null ? 1.0 : e.groupPower.getOrDefault(p.tab().id + "." + p.element(), 1.0);
		int cap = Math.max(1, Math.min(50, SkillConfigs.maxLevel(skill)));
		double f = Math.min(1.0, Math.max(0, data.level(skill)) / (double) cap);
		return e.abilityDamageMultiplier * group * (1.0 + e.maxLevelOverdrive * Math.pow(f, e.overdriveCurve));
	}

	/** A config value in readable units. */
	private static String format(String prop, double v) {
		if (prop.contains("COOLDOWN") || prop.contains("DURATION") || prop.contains("DELAY") || prop.contains("INTERVAL") || prop.equals("REGEN")
				|| prop.equals("STUN")) {
			return String.format("%.1fs", v / 20.0);
		}
		if (prop.startsWith("TRUE_") || prop.equals("HEAL") || prop.equals("SLOW") || prop.equals("SPEED") || prop.equals("REDUCTION")
				|| prop.equals("DAMAGE_MIRROR") || prop.equals("MODIFIER") && v < 5) {
			return String.format("%.0f%%", v * 100);
		}
		if (prop.contains("DAMAGE") || prop.contains("DOT") || prop.equals("HEALTH")) {
			return String.format("%.1f❤", v / 2.0);
		}
		if (prop.contains("RANGE") || prop.equals("SIZE") || prop.equals("WIDTH") || prop.equals("HEIGHT") || prop.equals("FORCE")
				|| prop.equals("DISPLACEMENT")) {
			return String.format("%.1f", v);
		}
		return v == Math.floor(v) ? Long.toString((long) v) : String.format("%.2f", v);
	}

	private void drawFooter(GuiGraphics g, SkillData data) {
		int y = top + h - 34;
		Player player = minecraft.player;
		int xp = player == null ? 0 : XpUtil.total(player);
		g.fill(left + 8, y - 4, left + w - 8, y - 3, 0x40FFFFFF);
		g.drawString(font, Component.translatable("enderskills.altar.stored", data.storedXp()), left + 10, y + 2, 0xFF9CFF6A, false);
		g.drawString(font, Component.translatable("enderskills.altar.your_xp", xp), left + 10, y + 14, 0xFF6AD06A, false);
		int bx = left + 130;
		int accent = 0x4FAF7A;
		buttons.add(new Btn(bx, y + 2, 70, 18, Component.translatable("enderskills.altar.store"), true, accent,
				() -> send(EsNetwork.AltarAction.STORE_XP, "", minecraft.hasShiftDown() ? -1 : oneLevel())));
		buttons.add(new Btn(bx + 74, y + 2, 70, 18, Component.translatable("enderskills.altar.take"), true, accent,
				() -> send(EsNetwork.AltarAction.TAKE_XP, "", minecraft.hasShiftDown() ? -1 : oneLevel())));
		if (!book) {
			int max = EsSettings.get().maxResetUnlocks;
			Component label = confirmReset ? Component.translatable("enderskills.altar.reset.confirm")
					: Component.translatable("enderskills.altar.reset", Math.max(0, max - data.resets()), max);
			buttons.add(new Btn(left + w - 158, y + 2, 150, 18, label, !data.skills().isEmpty() && data.resets() < max,
					confirmReset ? 0xD04040 : 0x8A5A9A, () -> {
						if (confirmReset) {
							send(EsNetwork.AltarAction.RESET, "", 0);
							confirmReset = false;
							selected = null;
						} else {
							confirmReset = true;
						}
					}));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (Btn b : List.copyOf(buttons)) {
				if (b.enabled() && b.over(event.x(), event.y())) {
					b.action().run();
					minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
							net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
					return true;
				}
			}
			for (Node n : nodes()) {
				if (n.over(event.x(), event.y())) {
					selected = n.skill();
					confirmReset = false;
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
