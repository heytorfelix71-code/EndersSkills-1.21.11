package dev.enderskills.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.EnderSkills;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.logic.PhaseHooks;
import dev.enderskills.logic.Progression;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.skill.Abilities;
import dev.enderskills.skill.Skills;

/** Client side of the abilities: keys, the movement abilities (felt instantly) and the HUD. */
public final class ClientAbilities {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(EnderSkills.id("abilities"));
	private static final KeyMapping[] SLOT_KEYS = new KeyMapping[9];
	private static final int[] DEFAULT_KEYS = {InputConstants.KEY_Z, InputConstants.KEY_X, InputConstants.KEY_C,
			InputConstants.KEY_B, InputConstants.KEY_N, InputConstants.KEY_M, InputConstants.UNKNOWN.getValue(),
			InputConstants.UNKNOWN.getValue(), InputConstants.UNKNOWN.getValue()};
	private static KeyMapping dashKey;
	private static KeyMapping warpKey;
	private static KeyMapping portalMenuKey;
	private static KeyMapping shadowMenuKey;
	private static KeyMapping prevGroup;
	private static KeyMapping nextGroup;
	/** Ability group (tree) shown in the HUD and used by the ability keys. */
	private static int group;

	private static List<java.util.Map.Entry<Skills.Page, List<String>>> groups(SkillData data) {
		return Abilities.groups(data);
	}

	/** Abilities of the current group (empty when the player has none). */
	static List<String> groupSkills(SkillData data) {
		var list = groups(data);
		if (list.isEmpty()) {
			return List.of();
		}
		group = Math.floorMod(group, list.size());
		return list.get(group).getValue();
	}

	private static Skills.Page groupPage(SkillData data) {
		var list = groups(data);
		return list.isEmpty() ? null : list.get(Math.floorMod(group, list.size())).getKey();
	}

	private static float endurance;
	private static float maxEndurance;
	private static final Map<String, Integer> COOLDOWNS = new HashMap<>();
	private static final Map<String, Integer> ACTIVE = new HashMap<>();

	private static boolean dashWasDown;
	private static int dashTapTimer;
	private static int dashTicks;
	private static Vec3 dashVelocity = Vec3.ZERO;
	private static boolean jumpWasDown;
	private static int airTicks;
	private static int jumpsUsed;
	private static boolean hovering;
	private static boolean smashing;

	private ClientAbilities() {
	}

	static void init() {
		for (int i = 0; i < SLOT_KEYS.length; i++) {
			SLOT_KEYS[i] = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.ability_" + (i + 1),
					InputConstants.Type.KEYSYM, DEFAULT_KEYS[i], CATEGORY));
		}
		prevGroup = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.prev_group", InputConstants.Type.KEYSYM,
				InputConstants.KEY_LEFT, CATEGORY));
		nextGroup = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.next_group", InputConstants.Type.KEYSYM,
				InputConstants.KEY_RIGHT, CATEGORY));
		dashKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.dash", InputConstants.Type.KEYSYM,
				InputConstants.KEY_LALT, CATEGORY));
		warpKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.warp", InputConstants.Type.KEYSYM,
				InputConstants.KEY_RALT, CATEGORY));
		// the menus have a key of their own too (no Shift needed), unbound until you pick one
		portalMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.portal_menu", InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(), CATEGORY));
		shadowMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.shadow_menu", InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(), CATEGORY));
	}

	static void apply(EsNetwork.AbilitySync sync) {
		endurance = sync.endurance();
		maxEndurance = sync.maxEndurance();
		COOLDOWNS.clear();
		COOLDOWNS.putAll(sync.cooldowns());
		ACTIVE.clear();
		ACTIVE.putAll(sync.active());
	}

	static void reset() {
		COOLDOWNS.clear();
		ACTIVE.clear();
		maxEndurance = 0;
	}

	private static SkillData data(LocalPlayer player) {
		return player.getAttachedOrElse(Progression.DATA, SkillData.create());
	}

	private static double value(SkillData data, String skill, String property) {
		return SkillConfigs.value(skill, property, Math.max(0, data.level(skill)));
	}

	private static boolean ready(SkillData data, String skill) {
		return data.owns(skill) && COOLDOWNS.getOrDefault(skill, 0) <= 0 && endurance >= value(data, skill, "ENDURANCE");
	}

	private static void send(String skill, double dx, double dz) {
		if (ClientPlayNetworking.canSend(EsNetwork.UseAbility.TYPE)) {
			ClientPlayNetworking.send(new EsNetwork.UseAbility(skill, (float) dx, (float) dz));
		}
	}

	private static void countDown(Map<String, Integer> map) {
		map.replaceAll((k, v) -> v - 1);
		map.values().removeIf(v -> v <= 0);
	}

	// ------------------------------------------------------------------ input

	/**
	 * Speed Boost: the walk is pushed on the client (movement is the client's), so it always works with any other mod.
	 * The top speed becomes (1 + SPEED x setting) times what you already have, Speed passive included, so the two add up.
	 */
	private static void speedBoost(LocalPlayer player, SkillData data) {
		if (!ACTIVE.containsKey("speed_boost") || player.getAbilities().flying || player.isPassenger() || player.isInWater()
				|| player.isFallFlying()) {
			return;
		}
		net.minecraft.world.phys.Vec2 move = player.input.getMoveVector();
		net.minecraft.client.Options o = Minecraft.getInstance().options;
		if (move.lengthSquared() < 1.0E-4 && !(o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown())) {
			return;
		}
		double bonus = Math.max(0.0, value(data, "speed_boost", "SPEED") * dev.enderskills.data.EsSettings.get().speedBoostPower);
		if (bonus <= 0) {
			return;
		}
		double friction = 0.91;
		if (player.onGround()) {
			net.minecraft.core.BlockPos below = player.getBlockPosBelowThatAffectsMyMovement();
			friction *= player.level().getBlockState(below).getBlock().getFriction();
		}
		double k = (1.0 - (1.0 - friction) / (1.0 + bonus)) / friction;
		Vec3 dm = player.getDeltaMovement();
		player.setDeltaMovement(dm.x * k, dm.y, dm.z * k);
	}

	/**
	 * Hasten at its top level: through walls (and, holding Shift, down through blocks; Jump goes back up). The player
	 * has "no physics" only while holding Shift or at / inside a block, so you still stand on the ground otherwise.
	 */
	private static void phase(Minecraft client, LocalPlayer player, SkillData data, boolean free) {
		PhaseHooks.clientPlayerId = player.getId();
		int need = dev.enderskills.data.EsSettings.get().hastenPhaseLevel;
		boolean can = free && need > 0 && ACTIVE.containsKey("hasten") && !player.getAbilities().flying
				&& data.level("hasten") >= Math.min(need, SkillConfigs.maxLevel("hasten"));
		if (!can) {
			PhaseHooks.clientPhasing = false;
			return;
		}
		boolean down = client.options.keyShift.isDown();
		boolean up = client.options.keyJump.isDown();
		net.minecraft.world.phys.AABB box = player.getBoundingBox();
		boolean inside = !player.level().noCollision(player, box);
		boolean near = !player.level().noCollision(player, box.inflate(0.3, 0, 0.3).move(0, 0.1, 0));
		boolean noclip = down || inside || near;
		PhaseHooks.clientPhasing = noclip;
		player.resetFallDistance();
		if (noclip) {
			Vec3 v = player.getDeltaMovement();
			player.setDeltaMovement(v.x, down ? -0.35 : (up ? 0.35 : 0.0), v.z);
		}
	}

	/** 1.2K, 3.4M, 5.6B, 7.8T, 9.1Qa: energy can be as large as you like. */
	static String compact(double v) {
		if (v >= 1.0E15) {
			return "\u221E";
		}
		String[] suffix = {"", "K", "M", "B", "T", "Qa"};
		int i = 0;
		while (v >= 1000 && i < suffix.length - 1) {
			v /= 1000;
			i++;
		}
		return i == 0 ? Long.toString(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f%s", v, suffix[i]);
	}

	/** Fires on a double tap of its key (or on every press when double tap is off). */
	private static final class Tap {
		boolean wasDown;
		int timer;

		boolean fired(boolean down, boolean doubleTap) {
			if (timer > 0) {
				timer--;
			}
			boolean fire = false;
			if (down && !wasDown) {
				if (!doubleTap || timer > 0) {
					timer = 0;
					fire = true;
				} else {
					timer = 7;
				}
			}
			wasDown = down;
			return fire;
		}
	}

	private static final Tap DASH_TAP = new Tap();
	private static final Tap WARP_TAP = new Tap();
	/** The Teleport key being held (index of its slot), -1 = none: a shadow shows where it will land, release to go. */
	private static int teleportSlot = -1;

	/** About where the Teleport lands: along where you look, up to its range, settling on the ground like the server does. */
	private static Vec3 teleportSpot(LocalPlayer p, SkillData data) {
		dev.enderskills.data.EsSettings c = dev.enderskills.data.EsSettings.get();
		int cap = Math.max(1, Math.min(50, SkillConfigs.maxLevel("teleport")));
		double f = Math.min(1.0, Math.max(0, data.level("teleport")) / (double) cap);
		double start = Math.max(1.0, c.teleportRangeStart);
		double range = start * Math.pow(Math.max(start, c.teleportRangeMax) / start, f);
		range = Math.min(range, 160.0); // only what is loaded is shown
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1.0F).normalize();
		net.minecraft.world.level.Level level = p.level();
		net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(range)),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		double distance = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? range : eye.distanceTo(hit.getLocation()) - 0.4;
		Vec3 base = p.position();
		double eyeOffset = eye.y - base.y;
		for (double d = distance; d > 0.5; d -= 0.5) {
			Vec3 feet = eye.add(look.scale(d)).subtract(0, eyeOffset > 1 ? 0.2 : eyeOffset, 0);
			for (double up = 0; up <= 1.5; up += 0.5) {
				Vec3 spot = feet.add(0, up, 0);
				net.minecraft.world.phys.AABB box = p.getBoundingBox().move(spot.subtract(base));
				if (!level.noCollision(p, box)) {
					continue;
				}
				if (c.teleportGroundOnly) {
					for (int i = 0; i < 128; i++) {
						net.minecraft.world.phys.AABB low = p.getBoundingBox().move(spot.subtract(base)).move(0, -0.2, 0);
						if (!level.noCollision(p, low) || level.containsAnyLiquid(low)) {
							return spot;
						}
						spot = spot.add(0, -0.5, 0);
						if (spot.y < level.getMinY()) {
							break;
						}
					}
					continue;
				}
				return spot;
			}
		}
		return null;
	}

	/** How far the dash carries you: from the first to the last level of the ability. */
	private static double dashDistance(SkillData data) {
		dev.enderskills.data.EsSettings c = dev.enderskills.data.EsSettings.get();
		int cap = Math.max(1, Math.min(50, SkillConfigs.maxLevel("dash")));
		double f = Math.min(1.0, Math.max(0, data.level("dash")) / (double) cap);
		return Math.max(1.0, c.dashMinBlocks + (c.dashMaxBlocks - c.dashMinBlocks) * f);
	}

	static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.isPaused()) {
			return;
		}
		countDown(COOLDOWNS);
		countDown(ACTIVE);
		SkillData data = data(player);
		// Swim Speed: the water efficiency attribute (set by the server) plus this push, tuned so the top speed is exactly
		// (1 + bonus) times the normal one
		if (data.owns("swim_speed") && player.isInWater() && !player.getAbilities().flying && !dev.enderskills.dsl.SkillConfigs.disabled("swim_speed")) {
			double bonus = Math.max(0.0, Math.min(1.0, value(data, "swim_speed", "MODIFIER")) * dev.enderskills.data.EsSettings.get().swimSpeedPower);
			net.minecraft.client.Options o = client.options;
			boolean moving = player.input.getMoveVector().lengthSquared() > 1.0E-4 || o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown()
					|| o.keyRight.isDown() || o.keyJump.isDown();
			if (bonus > 0 && moving) {
				double eff = Math.max(0.0, Math.min(1.0, player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.WATER_MOVEMENT_EFFICIENCY)));
				double f = 0.8 + (0.54600006 - 0.8) * eff; // the water drag of this player
				double k = (1.0 - (1.0 - f) / (1.0 + bonus)) / f;
				net.minecraft.world.phys.Vec3 dm = player.getDeltaMovement();
				player.setDeltaMovement(dm.x * k, player.isSwimming() ? dm.y * k : dm.y, dm.z * k);
			}
		}
		if (data.skills().isEmpty()) {
			return;
		}
		boolean free = client.screen == null && !player.isSpectator();

		// ability keys
		while (prevGroup.consumeClick()) {
			if (free) {
				group--;
				groupShown = 60;
			}
		}
		while (nextGroup.consumeClick()) {
			if (free) {
				group++;
				groupShown = 60;
			}
		}
		if (groupShown > 0) {
			groupShown--;
		}
		List<String> slots = groupSkills(data);
		for (int i = 0; i < SLOT_KEYS.length; i++) {
			while (SLOT_KEYS[i].consumeClick()) {
				boolean shiftAtPress = ShiftOnPress.take(SLOT_KEYS[i]);
				if (free && i < slots.size()) {
					String skill = slots.get(i);
					// the Shift key itself (also with "toggle sneak", where the sneak key isn't "down"), or the sneak key
					boolean sneak = shiftAtPress || client.hasShiftDown() || client.options.keyShift.isDown();
					if (sneak && skill.equals("shadow")) {
						client.setScreen(new ShadowCountScreen()); // how many fighting shadows
					} else if (sneak && skill.equals("portal")) {
						PortalScreen.open(); // manage the portals: close, move, new portal at coordinates
					} else if (sneak && dev.enderskills.data.EsSettings.get().coordinateTargeting
							&& skill.equals("teleport")) {
						client.setScreen(new CoordScreen(skill));
					} else if (skill.equals("teleport")) {
						teleportSlot = i; // hold to see the shadow, release to go
					} else {
						send(skill, 0, 0);
					}
				}
			}
		}

		while (portalMenuKey.consumeClick()) {
			if (free && data.owns("portal")) {
				PortalScreen.open();
			}
		}
		while (shadowMenuKey.consumeClick()) {
			if (free && data.owns("shadow")) {
				client.setScreen(new ShadowCountScreen());
			}
		}

		// Dash / Warp: double tap the key, with W/A/S/D for the direction
		boolean doubleTap = dev.enderskills.data.EsSettings.get().dashDoubleTap;
		if (DASH_TAP.fired(free && dashKey.isDown(), doubleTap) && ready(data, "dash")) {
			// Dash (left Alt): a fast run in the direction you are moving, to get out of danger
			Vec3 dir = moveDirection(client, player);
			dashTicks = 5;
			dashVelocity = dir.scale(dashDistance(data) / 5.0);
			COOLDOWNS.put("dash", (int) value(data, "dash", "COOLDOWN"));
			send("dash", dir.x, dir.z);
		}
		// Warp (right Alt, double tap): a few blocks straight ahead onto the ground, through walls (the server does it)
		if (WARP_TAP.fired(free && warpKey.isDown(), doubleTap) && ready(data, "warp")) {
			COOLDOWNS.put("warp", 5);
			send("warp", 0, 0);
		}
		// Teleport: while its key is held a shadow shows where you will land, releasing it goes there
		if (teleportSlot >= 0) {
			if (!free || teleportSlot >= SLOT_KEYS.length) {
				teleportSlot = -1;
				dev.enderskills.client.ShapeRenderer.warpPreview = null;
			} else if (SLOT_KEYS[teleportSlot].isDown()) {
				dev.enderskills.client.ShapeRenderer.warpPreview = teleportSpot(player, data);
			} else {
				teleportSlot = -1;
				dev.enderskills.client.ShapeRenderer.warpPreview = null;
				send("teleport", 0, 0);
			}
		}
		if (dashTicks > 0) {
			dashTicks--;
			Vec3 v = player.getDeltaMovement();
			player.setDeltaMovement(dashVelocity.x, Math.max(v.y, 0.0), dashVelocity.z);
			if (dashTicks == 0) {
				player.setDeltaMovement(dashVelocity.x * 0.3, v.y, dashVelocity.z * 0.3);
			}
		}

		// in the air: Extra Jump (press jump), Hover (keep holding jump), Smash (hold sneak)
		boolean airborne = !player.onGround() && !player.isInWater() && !player.getAbilities().flying && !player.isPassenger()
				&& !player.onClimbable() && !player.isFallFlying();
		if (player.onGround()) {
			jumpsUsed = 0;
			smashing = false;
			airTicks = 0;
		} else if (airborne) {
			airTicks++;
		}
		boolean jumpDown = free && client.options.keyJump.isDown();
		if (!jumpDown || !airborne) {
			hovering = false;
		}
		// airTicks > 2: the press that made the normal jump from the ground is not an Extra Jump (it used to replace
		// the normal jump and its Jump Height bonus)
		if (airborne && airTicks > 2 && jumpDown && !jumpWasDown) {
			if (ready(data, "extra_jump") && jumpsUsed < (int) value(data, "extra_jump", "JUMPS")) {
				jumpsUsed++;
				Vec3 v = player.getDeltaMovement();
				double strength = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
				player.setDeltaMovement(v.x, 0.55 * Math.max(1.0, strength / 0.42), v.z);
				player.resetFallDistance();
				send("extra_jump", 0, 0);
			} else if (data.owns("hover") && (ACTIVE.containsKey("hover") || ready(data, "hover"))) {
				hovering = true;
				if (!ACTIVE.containsKey("hover")) {
					ACTIVE.put("hover", (int) value(data, "hover", "DURATION"));
					send("hover", 0, 0);
				}
			}
		}
		jumpWasDown = jumpDown;
		dev.enderskills.client.fx.OriginalFx.localHovering = hovering && ACTIVE.containsKey("hover");
		speedBoost(player, data);
		phase(client, player, data, free);
		if (hovering && ACTIVE.containsKey("hover")) {
			Vec3 v = player.getDeltaMovement();
			if (v.y < 0) {
				player.setDeltaMovement(v.x, v.y * 0.4, v.z);
			}
			player.resetFallDistance();
		}
		if (airborne && free && client.options.keyShift.isDown() && player.getDeltaMovement().y < 0) {
			if (!smashing && ready(data, "smash")) {
				smashing = true;
				COOLDOWNS.put("smash", (int) value(data, "smash", "COOLDOWN"));
				send("smash", 0, 0);
			}
			if (smashing) {
				Vec3 v = player.getDeltaMovement();
				player.setDeltaMovement(v.x * 0.8, Math.max(-3.5, v.y * 1.05 - 0.08), v.z * 0.8);
			}
		}
	}

	/** Horizontal direction of the held movement keys (forward when none is held). */
	private static Vec3 moveDirection(Minecraft client, LocalPlayer player) {
		double f = (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
		double s = (client.options.keyLeft.isDown() ? 1 : 0) - (client.options.keyRight.isDown() ? 1 : 0);
		if (f == 0 && s == 0) {
			f = 1;
		}
		double yaw = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		return forward.scale(f).add(left.scale(s)).normalize();
	}

	// ------------------------------------------------------------------ HUD

	private static final net.minecraft.resources.Identifier FRAME =
			net.minecraft.resources.Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png");

	/** Colored, pulsing frame (like freezing) while the player has an Ender Skills status effect. */
	static void overlay(GuiGraphics g, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.hideGui || !dev.enderskills.data.EsSettings.get().statusOverlay) {
			return;
		}
		int color = 0;
		var effects = new java.util.LinkedHashMap<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>, Integer>();
		effects.put(dev.enderskills.registry.ModEffects.STUNNED, 0xFFE070);
		effects.put(dev.enderskills.registry.ModEffects.ELECTRIFIED, 0x7FD4FF);
		effects.put(dev.enderskills.registry.ModEffects.ROOTED, 0x5A8C3A);
		effects.put(dev.enderskills.registry.ModEffects.BLEEDING, 0xB00000);
		effects.put(dev.enderskills.registry.ModEffects.BLACK_FLAME, 0x6030C0);
		effects.put(dev.enderskills.registry.ModEffects.GASSED, 0x6B8E23);
		for (var e : effects.entrySet()) {
			if (player.hasEffect(e.getKey())) {
				color = e.getValue();
				break;
			}
		}
		if (color == 0) {
			return;
		}
		float pulse = 0.45F + 0.25F * (float) Math.sin((player.tickCount + delta.getGameTimeDeltaPartialTick(true)) * 0.25);
		int alpha = (int) (255 * pulse) << 24;
		int w = g.guiWidth();
		int h = g.guiHeight();
		g.blit(RenderPipelines.GUI_TEXTURED, FRAME, 0, 0, 0.0F, 0.0F, w, h, w, h, alpha | color);
	}

	static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		dev.enderskills.data.EsSettings cfg = dev.enderskills.data.EsSettings.get();
		if (player == null || client.options.hideGui || maxEndurance <= 0 || !cfg.hudVisible) {
			return;
		}
		SkillData data = data(player);
		if (data.abilityCount() == 0) {
			return;
		}
		Font font = client.font;
		List<String> cells = new java.util.ArrayList<>(groupSkills(data));
		List<String> keys = new java.util.ArrayList<>();
		for (int i = 0; i < cells.size() && i < SLOT_KEYS.length; i++) {
			keys.add(SLOT_KEYS[i].getTranslatedKeyMessage().getString());
		}
		while (cells.size() > keys.size()) {
			cells.remove(cells.size() - 1);
		}
		if (cfg.hudShowSpecial) {
			// only the special / passive abilities of the tree shown right now (not of every tree)
			Skills.Page page = groupPage(data);
			if (page != null) {
				for (String special : page.abilities()) {
					if (data.owns(special) && dev.enderskills.skill.Abilities.special(special) && !cells.contains(special)) {
						cells.add(special);
						keys.add("");
					}
				}
			}
		}
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cfg.hudX, cfg.hudY);
		float scale = (float) Math.max(0.25, Math.min(4.0, cfg.hudScale));
		pose.scale(scale, scale);
		switch (cfg.hudStyle) {
			case LIST -> list(g, font, cells, keys, cfg);
			case ORIGINAL -> original(g, font, cells, cfg);
			default -> modern(g, font, cells, keys, cfg);
		}
		pose.popMatrix();
	}

	private static int panelAlpha(dev.enderskills.data.EsSettings cfg) {
		return (int) (255 * Math.max(0, Math.min(1, cfg.hudPanelOpacity))) << 24;
	}

	/** Modern: glass panel, gradient endurance bar, ability cells side by side. */
	private static void modern(GuiGraphics g, Font font, List<String> cells, List<String> keys, dev.enderskills.data.EsSettings cfg) {
		int width = Math.max(110, cells.size() * 22 + 6);
		if (cfg.hudPanelOpacity > 0) {
			Cosmos.chip(g, 0, 0, width, 46, panelAlpha(cfg) | 0x0A0614, (panelAlpha(cfg) >>> 1 & 0xFF000000) | cfg.hudBarColor);
		}
		g.drawString(font, "✦", 4, 4, 0xFF000000 | cfg.hudBarColor, true);
		Cosmos.bar(g, 13, 5, width - 50, 6, endurance / maxEndurance, cfg.hudBarColor, brighten(cfg.hudBarColor));
		String e = compact(endurance) + "/" + compact(maxEndurance);
		g.drawString(font, e, width - 4 - font.width(e), 4, 0xFF000000 | cfg.hudTextColor, true);
		for (int i = 0; i < cells.size(); i++) {
			cell(g, font, cells.get(i), keys.get(i), 4 + i * 22, 17, cfg);
		}
	}

	/** List (versions 1.0 to 1.2): thin bar and one ability per line. */
	private static void list(GuiGraphics g, Font font, List<String> cells, List<String> keys, dev.enderskills.data.EsSettings cfg) {
		int w = 90;
		g.fill(0, 0, w, 7, panelAlpha(cfg));
		g.fill(1, 1, 1 + Math.round((w - 2) * Math.min(1F, endurance / maxEndurance)), 6, 0xFF000000 | cfg.hudBarColor);
		g.drawString(font, compact(endurance) + "/" + compact(maxEndurance), w + 4, 0, 0xFF000000 | cfg.hudTextColor, true);
		int y = 12;
		for (int i = 0; i < cells.size(); i++) {
			String skill = cells.get(i);
			int cooldown = COOLDOWNS.getOrDefault(skill, 0);
			int active = ACTIVE.getOrDefault(skill, 0);
			g.fill(0, y, 18, y + 18, active > 0 ? 0xC0205030 : panelAlpha(cfg));
			g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + skill + ".png"), 1, y + 1, 0.0F, 0.0F,
					16, 16, 32, 32, 32, 32, cooldown > 0 ? 0xFF606060 : 0xFFFFFFFF);
			String text = cooldown > 0 ? (cooldown + 19) / 20 + "s" : active > 1_000_000 ? "ON  " + keys.get(i)
					: active > 0 ? "▶ " + (active + 19) / 20 + "s" : keys.get(i);
			g.drawString(font, text, 22, y + 5, cooldown > 0 ? 0xFF000000 | cfg.hudCooldownColor
					: active > 0 ? 0xFF000000 | cfg.hudActiveColor : 0xFF000000 | cfg.hudReadyColor, true);
			y += 20;
		}
	}

	private static final net.minecraft.resources.Identifier BOSS_BG = net.minecraft.resources.Identifier.withDefaultNamespace("boss_bar/blue_background");
	private static final net.minecraft.resources.Identifier BOSS_FG = net.minecraft.resources.Identifier.withDefaultNamespace("boss_bar/blue_progress");
	private static final net.minecraft.resources.Identifier NOTCH_BG = net.minecraft.resources.Identifier.withDefaultNamespace("boss_bar/notched_20_background");
	private static final net.minecraft.resources.Identifier NOTCH_FG = net.minecraft.resources.Identifier.withDefaultNamespace("boss_bar/notched_20_progress");

	/** Original Ender Skills (1.12.2): "Endurance" title with a blue notched boss bar, abilities in a column below. */
	private static void original(GuiGraphics g, Font font, List<String> cells, dev.enderskills.data.EsSettings cfg) {
		Component title = Component.translatable("enderskills.hud.title");
		g.drawString(font, title, 0, 0, 0xFFFFFFFF, true);
		String amount = Math.round(endurance) + "";
		g.drawString(font, amount, 182 - font.width(amount), 0, 0xFFFFFFFF, true);
		int filled = Math.round(182 * Math.min(1F, endurance / maxEndurance));
		g.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_BG, 0, 10, 182, 5);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, NOTCH_BG, 0, 10, 182, 5);
		if (filled > 0) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_FG, 182, 5, 0, 0, 0, 10, filled, 5);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, NOTCH_FG, 182, 5, 0, 0, 0, 10, filled, 5);
		}
		int y = 24;
		for (String skill : cells) {
			int cooldown = COOLDOWNS.getOrDefault(skill, 0);
			int active = ACTIVE.getOrDefault(skill, 0);
			g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/gui/skill_background.png"), 0, y, 0.0F, 0.0F,
					16, 16, 64, 64, 64, 64, 0xFFFFFFFF);
			g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + skill + ".png"), 1, y + 1, 0.0F, 0.0F,
					14, 14, 32, 32, 32, 32, cooldown > 0 ? 0xFF606060 : 0xFFFFFFFF);
			if (cooldown > 0) {
				g.drawString(font, (cooldown + 19) / 20 + "s", 20, y + 4, 0xFFFFFFFF, true);
			} else if (active > 0) {
				g.drawString(font, active > 1_000_000 ? "ON" : (active + 19) / 20 + "s", 20, y + 4, 0xFF80FF80, true);
			}
			y += 17;
		}
	}

	private static int brighten(int rgb) {
		int r = Math.min(255, (rgb >> 16 & 0xFF) + 90);
		int g = Math.min(255, (rgb >> 8 & 0xFF) + 90);
		int b = Math.min(255, (rgb & 0xFF) + 90);
		return r << 16 | g << 8 | b;
	}

	private static final Map<String, Integer> COOLDOWN_MAX = new HashMap<>();
	/** Ticks the group name stays bright after switching. */
	private static int groupShown;

	/** "◀ Mobility: Void ▶" above the hearts, food and XP bar. */
	static void groupLabel(GuiGraphics g, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		dev.enderskills.data.EsSettings cfg = dev.enderskills.data.EsSettings.get();
		if (player == null || client.options.hideGui || !cfg.hudVisible || !cfg.hudGroupLabel || player.isSpectator()) {
			return;
		}
		SkillData data = data(player);
		Skills.Page page = groupPage(data);
		if (page == null) {
			return;
		}
		int count = groups(data).size();
		Component name = Component.translatable("enderskills.hud.group", Component.translatable("enderskills.tab." + page.tab().id),
				Component.translatable("enderskills.page." + page.element()));
		Font font = client.font;
		int y = g.guiHeight() - (player.getAbilities().instabuild ? 46 : 62);
		int cx = g.guiWidth() / 2;
		int w = font.width(name);
		int alpha = groupShown > 0 ? 0xFF : 0xC0;
		Cosmos.chip(g, cx - w / 2 - 22, y - 3, w + 44, 14, 0x90000000, (alpha << 24) | page.tab().color);
		g.drawString(font, name, cx - w / 2, y, (alpha << 24) | 0xFFFFFF, true);
		if (count > 1) {
			g.drawString(font, "◀", cx - w / 2 - 16, y, (alpha << 24) | page.tab().color, true);
			g.drawString(font, "▶", cx + w / 2 + 9, y, (alpha << 24) | page.tab().color, true);
		}
	}

	private static void cell(GuiGraphics g, Font font, String skill, String key, int x, int y, dev.enderskills.data.EsSettings cfg) {
		int cooldown = COOLDOWNS.getOrDefault(skill, 0);
		int active = ACTIVE.getOrDefault(skill, 0);
		if (cooldown > COOLDOWN_MAX.getOrDefault(skill, 0)) {
			COOLDOWN_MAX.put(skill, cooldown);
		}
		if (cooldown <= 0) {
			COOLDOWN_MAX.remove(skill);
		}
		int border = active > 0 ? 0xFF000000 | cfg.hudActiveColor : cooldown > 0 ? 0x80000000 | cfg.hudCooldownColor : 0x90FFFFFF;
		if (active > 0) {
			float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 150.0);
			Cosmos.chip(g, x - 1, y - 1, 22, 22, ((int) (40 + 60 * pulse) << 24) | cfg.hudActiveColor, 0);
		}
		Cosmos.chip(g, x, y, 20, 20, 0xC0000000, border);
		g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + skill + ".png"), x + 2, y + 2, 0.0F, 0.0F,
				16, 16, 32, 32, 32, 32, cooldown > 0 ? 0xFF707070 : 0xFFFFFFFF);
		if (cooldown > 0) {
			int max = Math.max(cooldown, COOLDOWN_MAX.getOrDefault(skill, cooldown));
			int hgt = Math.round(16F * cooldown / max);
			g.fill(x + 2, y + 18 - hgt, x + 18, y + 18, 0xA0000000);
			String t = Integer.toString((cooldown + 19) / 20);
			g.drawString(font, t, x + 10 - font.width(t) / 2, y + 6, 0xFF000000 | cfg.hudCooldownColor, true);
		} else if (active > 0 && active < 1_000_000) {
			String t = Integer.toString((active + 19) / 20);
			g.drawString(font, t, x + 10 - font.width(t) / 2, y + 6, 0xFF000000 | cfg.hudActiveColor, true);
		}
		if (!key.isEmpty()) {
			String k = key.length() > 2 ? key.substring(0, 2) : key;
			g.drawString(font, k, x + 1, y + 21, 0xFF000000 | cfg.hudReadyColor, false);
		}
	}
}
