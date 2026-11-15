package dev.enderskills.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.network.EsNetwork;
import dev.enderskills.network.EsNetwork.PortalAction;

/**
 * Portal menu (sneak + Portal key): every open pair with its two portals, close a pair, move a portal to typed
 * coordinates, and put the first portal of a new pair at coordinates (the next use of the ability links a portal to it).
 */
public class PortalScreen extends Screen {
	private static final int ROW = 24;

	private List<double[]> pairs = List.of();
	private double[] waiting = new double[0];
	private EditBox x;
	private EditBox y;
	private EditBox z;
	/** Pair being edited (-1 = the boxes make a new portal), and which of its portals (0 = A, 1 = B). */
	private int editId = -1;
	private int editWhich;
	private int scroll;

	private int left;
	private int panelW;
	private int top;
	private int rowsTop;
	private int shown;
	private int waitY;
	private int bottomTop;

	public PortalScreen() {
		super(Component.translatable("enderskills.portals.title"));
	}

	/** Opens the menu and asks the server for the list. */
	static void open() {
		Minecraft.getInstance().setScreen(new PortalScreen());
		send(PortalAction.LIST, 0, 0, 0, 0, 0);
	}

	/** The server's list arrived (after opening and after every change). */
	public static void receive(EsNetwork.PortalList list) {
		if (Minecraft.getInstance().screen instanceof PortalScreen s) {
			s.pairs = list.pairs();
			s.waiting = list.waiting();
			s.rebuild();
		}
	}

	private static void send(int action, int id, int which, double x, double y, double z) {
		if (ClientPlayNetworking.canSend(PortalAction.TYPE)) {
			ClientPlayNetworking.send(new PortalAction(action, id, which, x, y, z));
		}
	}

	@Override
	protected void init() {
		rebuild();
	}

	private void rebuild() {
		String sx = x == null ? "" : x.getValue();
		String sy = y == null ? "" : y.getValue();
		String sz = z == null ? "" : z.getValue();
		clearWidgets();
		panelW = Math.min(width - 16, 440);
		left = width / 2 - panelW / 2;
		top = 8;
		rowsTop = top + 22;
		int maxRows = Math.max(1, (height - top - 22 - 24 - 84 - 8) / ROW);
		scroll = Math.max(0, Math.min(scroll, pairs.size() - maxRows));
		shown = Math.min(pairs.size() - scroll, maxRows);
		int rowY = rowsTop;
		int bx = left + panelW - 8 - 170;
		for (int i = scroll; i < scroll + shown; i++) {
			double[] p = pairs.get(i);
			int id = (int) p[0];
			addRenderableWidget(Button.builder(Component.translatable("enderskills.portals.edit_a"), b -> startEdit(id, 0, p[1], p[2], p[3]))
					.bounds(bx, rowY, 56, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("enderskills.portals.edit_b"), b -> startEdit(id, 1, p[4], p[5], p[6]))
					.bounds(bx + 58, rowY, 56, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("enderskills.portals.close"), b -> send(PortalAction.CLOSE, id, 0, 0, 0, 0))
					.bounds(bx + 116, rowY, 54, 20).build());
			rowY += ROW;
		}
		waitY = rowsTop + Math.max(1, shown) * ROW + 2;
		if (waiting.length >= 3) {
			addRenderableWidget(Button.builder(Component.translatable("enderskills.portals.cancel_wait"), b -> send(PortalAction.CANCEL_WAIT, 0, 0, 0, 0, 0))
					.bounds(left + panelW - 8 - 80, waitY - 4, 80, 20).build());
		}
		bottomTop = waitY + 24;
		int cx = width / 2;
		x = addRenderableWidget(new EditBox(font, cx - 100, bottomTop + 12, 62, 20, Component.literal("X")));
		y = addRenderableWidget(new EditBox(font, cx - 31, bottomTop + 12, 62, 20, Component.literal("Y")));
		z = addRenderableWidget(new EditBox(font, cx + 38, bottomTop + 12, 62, 20, Component.literal("Z")));
		x.setHint(Component.literal("X"));
		y.setHint(Component.literal("Y"));
		z.setHint(Component.literal("Z"));
		x.setValue(sx);
		y.setValue(sy);
		z.setValue(sz);
		boolean editing = editId >= 0;
		addRenderableWidget(Button.builder(Component.translatable(editing ? "enderskills.portals.move" : "enderskills.portals.create"), b -> act())
				.bounds(cx - 152, bottomTop + 38, 100, 20).build());
		if (editing) {
			addRenderableWidget(Button.builder(Component.translatable("enderskills.portals.stop_edit"), b -> {
				editId = -1;
				rebuild();
			}).bounds(cx - 50, bottomTop + 38, 100, 20).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("enderskills.portals.close_all"), b -> send(PortalAction.CLOSE, -1, 0, 0, 0, 0))
				.bounds(cx + 52, bottomTop + 38, 100, 20).build());
	}

	private void startEdit(int id, int which, double px, double py, double pz) {
		editId = id;
		editWhich = which;
		x.setValue(Long.toString((long) Math.floor(px)));
		y.setValue(Long.toString((long) Math.floor(py)));
		z.setValue(Long.toString((long) Math.floor(pz)));
		rebuild();
	}

	private void act() {
		Player p = minecraft.player;
		if (p == null) {
			return;
		}
		try {
			double tx = CoordScreen.parse(x.getValue(), p.getX());
			double ty = CoordScreen.parse(y.getValue(), p.getY());
			double tz = CoordScreen.parse(z.getValue(), p.getZ());
			if (editId >= 0) {
				send(PortalAction.MOVE, editId, editWhich, Math.floor(tx) + 0.5, ty, Math.floor(tz) + 0.5);
				editId = -1;
			} else {
				send(PortalAction.WAIT_AT, 0, 0, Math.floor(tx) + 0.5, ty, Math.floor(tz) + 0.5);
			}
			rebuild();
		} catch (NumberFormatException e) {
			x.setTextColor(0xFFFF6060);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && pairs.size() > shown) {
			scroll += scrollY > 0 ? -1 : 1;
			rebuild();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private static String pos(double a, double b, double c) {
		return (long) Math.floor(a) + " " + (long) Math.floor(b) + " " + (long) Math.floor(c);
	}

	private static String time(double seconds) {
		if (seconds < 0) {
			return "∞";
		}
		int s = (int) seconds;
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		switch (dev.enderskills.data.EsSettings.get().altarBackground) {
			case BLUR -> super.renderBackground(g, mouseX, mouseY, partialTick);
			case TRANSPARENT -> g.fill(0, 0, width, height, 0x30000000);
			default -> Cosmos.background(g, width, height);
		}
		Cosmos.panel(g, left, top, panelW, bottomTop + 72 - top, 0x9A4DFF);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, top + 7, 0xFFE0C8FF);
		int rowY = rowsTop;
		if (pairs.isEmpty()) {
			g.drawString(font, Component.translatable("enderskills.portals.none"), left + 10, rowY + 6, 0xFF9A8AB0);
		}
		for (int i = scroll; i < scroll + shown; i++) {
			double[] p = pairs.get(i);
			boolean edA = editId == (int) p[0] && editWhich == 0;
			boolean edB = editId == (int) p[0] && editWhich == 1;
			g.drawString(font, "#" + (int) p[0] + "  A: " + pos(p[1], p[2], p[3]) + "   (" + time(p[7]) + ")", left + 10, rowY + 1,
					edA ? 0xFFFFE070 : 0xFFE0C8FF);
			g.drawString(font, "      B: " + pos(p[4], p[5], p[6]), left + 10, rowY + 11, edB ? 0xFFFFE070 : 0xFFE0C8FF);
			rowY += ROW;
		}
		if (pairs.size() > shown) {
			g.drawString(font, (scroll + 1) + "-" + (scroll + shown) + " / " + pairs.size(), left + panelW - 60, top + 7, 0xFF9A8AB0);
		}
		if (waiting.length >= 3) {
			g.drawString(font, Component.translatable("enderskills.portals.waiting", pos(waiting[0], waiting[1], waiting[2])), left + 10, waitY + 2,
					0xFFB98CFF);
		}
		Component label = editId >= 0
				? Component.translatable("enderskills.portals.move_label", editWhich == 0 ? "A" : "B", editId)
				: Component.translatable("enderskills.portals.new_label");
		g.drawCenteredString(font, label, width / 2, bottomTop, 0xFFE0C8FF);
		g.drawCenteredString(font, Component.translatable("enderskills.coords.hint"), width / 2, bottomTop + 61, 0xFF9A8AB0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
