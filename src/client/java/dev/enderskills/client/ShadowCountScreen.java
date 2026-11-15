package dev.enderskills.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.network.EsNetwork;

/** Sneak + Shadow key: how many shadows walk and fight with you (the server keeps it within what your level allows). */
public class ShadowCountScreen extends Screen {
	private static int last = -1;
	private int count;

	public ShadowCountScreen() {
		super(Component.translatable("enderskills.shadows.title"));
	}

	@Override
	protected void init() {
		int max = Math.max(1, Math.min(100, dev.enderskills.data.EsSettings.get().shadowMinionMax));
		if (last < 0 || last > max) {
			last = max;
		}
		count = last;
		int cx = width / 2;
		int cy = height / 2;
		addRenderableWidget(new AbstractSliderButton(cx - 100, cy - 10, 200, 20, Component.empty(), count / (double) max) {
			{
				updateMessage();
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.translatable("enderskills.shadows.slider", (int) Math.round(value * max)));
			}

			@Override
			protected void applyValue() {
				count = (int) Math.round(value * max);
			}
		});
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(cx - 50, cy + 18, 100, 20).build());
	}

	private void done() {
		last = count;
		if (ClientPlayNetworking.canSend(EsNetwork.ShadowCount.TYPE)) {
			ClientPlayNetworking.send(new EsNetwork.ShadowCount(count));
		}
		onClose();
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		switch (dev.enderskills.data.EsSettings.get().altarBackground) {
			case BLUR -> super.renderBackground(g, mouseX, mouseY, partialTick);
			case TRANSPARENT -> g.fill(0, 0, width, height, 0x30000000);
			default -> Cosmos.background(g, width, height);
		}
		Cosmos.panel(g, width / 2 - 112, height / 2 - 44, 224, 90, 0x9A4DFF);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, height / 2 - 34, 0xFFE0C8FF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
