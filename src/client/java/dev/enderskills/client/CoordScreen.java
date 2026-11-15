package dev.enderskills.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.network.EsNetwork;

/** Type X Y Z for Portal (exit) or Teleport (destination). "~" means your own position, "~10" relative. */
public class CoordScreen extends Screen {
	private final String skill;
	private EditBox x;
	private EditBox y;
	private EditBox z;

	public CoordScreen(String skill) {
		super(Component.translatable("enderskills.coords.title"));
		this.skill = skill;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int cy = height / 2;
		x = addRenderableWidget(new EditBox(font, cx - 100, cy - 10, 62, 20, Component.literal("X")));
		y = addRenderableWidget(new EditBox(font, cx - 31, cy - 10, 62, 20, Component.literal("Y")));
		z = addRenderableWidget(new EditBox(font, cx + 38, cy - 10, 62, 20, Component.literal("Z")));
		x.setHint(Component.literal("X"));
		y.setHint(Component.literal("Y"));
		z.setHint(Component.literal("Z"));
		addRenderableWidget(Button.builder(Component.translatable("enderskills.coords.go"), b -> go())
				.bounds(cx - 50, cy + 18, 100, 20).build());
		setInitialFocus(x);
	}

	private static double parse(String text, double own) {
		String t = text.trim();
		if (t.isEmpty() || t.equals("~")) {
			return own;
		}
		if (t.startsWith("~")) {
			return own + Double.parseDouble(t.substring(1));
		}
		return Double.parseDouble(t);
	}

	private void go() {
		Player p = minecraft.player;
		if (p == null) {
			return;
		}
		try {
			double tx = parse(x.getValue(), p.getX());
			double ty = parse(y.getValue(), p.getY());
			double tz = parse(z.getValue(), p.getZ());
			if (ClientPlayNetworking.canSend(EsNetwork.UseAbilityAt.TYPE)) {
				ClientPlayNetworking.send(new EsNetwork.UseAbilityAt(skill, Math.floor(tx) + 0.5, ty, Math.floor(tz) + 0.5));
			}
			onClose();
		} catch (NumberFormatException e) {
			x.setTextColor(0xFFFF6060);
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		switch (dev.enderskills.data.EsSettings.get().altarBackground) {
			case BLUR -> super.renderBackground(g, mouseX, mouseY, partialTick);
			case TRANSPARENT -> g.fill(0, 0, width, height, 0x30000000);
			default -> Cosmos.background(g, width, height);
		}
		Cosmos.panel(g, width / 2 - 112, height / 2 - 44, 224, 100, 0x9A4DFF);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, Component.translatable("skill.enderskills." + skill).append(" - ").append(title), width / 2, height / 2 - 34, 0xFFE0C8FF);
		g.drawCenteredString(font, Component.translatable("enderskills.coords.hint"), width / 2, height / 2 + 44, 0xFF9A8AB0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
