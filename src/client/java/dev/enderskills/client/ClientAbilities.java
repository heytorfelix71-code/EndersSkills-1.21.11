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
import dev.enderskills.logic.Progression;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.skill.Abilities;

/** Client side of the abilities: keys, the movement abilities (felt instantly) and the HUD. */
public final class ClientAbilities {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(EnderSkills.id("abilities"));
	private static final KeyMapping[] SLOT_KEYS = new KeyMapping[9];
	private static final int[] DEFAULT_KEYS = {InputConstants.KEY_Z, InputConstants.KEY_X, InputConstants.KEY_C,
			InputConstants.KEY_B, InputConstants.KEY_N, InputConstants.KEY_M, InputConstants.UNKNOWN.getValue(),
			InputConstants.UNKNOWN.getValue(), InputConstants.UNKNOWN.getValue()};
	private static KeyMapping dashKey;

	private static float endurance;
	private static float maxEndurance;
	private static final Map<String, Integer> COOLDOWNS = new HashMap<>();
	private static final Map<String, Integer> ACTIVE = new HashMap<>();

	private static boolean dashWasDown;
	private static int dashTapTimer;
	private static int dashTicks;
	private static Vec3 dashVelocity = Vec3.ZERO;
	private static boolean jumpWasDown;
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
		dashKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.enderskills.dash", InputConstants.Type.KEYSYM,
				InputConstants.KEY_LALT, CATEGORY));
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

	static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.isPaused()) {
			return;
		}
		countDown(COOLDOWNS);
		countDown(ACTIVE);
		SkillData data = data(player);
		dev.enderskills.logic.SpeedHooks.clientBoost = ACTIVE.containsKey("speed_boost") ? value(data, "speed_boost", "SPEED") : 0.0;
		if (data.skills().isEmpty()) {
			return;
		}
		boolean free = client.screen == null && !player.isSpectator();

		// ability keys
		List<String> slots = Abilities.slots(data);
		for (int i = 0; i < SLOT_KEYS.length; i++) {
			while (SLOT_KEYS[i].consumeClick()) {
				if (free && i < slots.size()) {
					send(slots.get(i), 0, 0);
				}
			}
		}

		// Dash / Warp: double tap the key, with W/A/S/D for the direction
		boolean dashDown = free && dashKey.isDown();
		if (dashTapTimer > 0) {
			dashTapTimer--;
		}
		if (dashDown && !dashWasDown) {
			if (dashTapTimer > 0) {
				dashTapTimer = 0;
				Vec3 dir = moveDirection(client, player);
				if (ready(data, "dash")) {
					double range = value(data, "dash", "RANGE");
					dashTicks = 5;
					dashVelocity = dir.scale(range / 5.0);
					COOLDOWNS.put("dash", (int) value(data, "dash", "COOLDOWN"));
					send("dash", dir.x, dir.z);
				} else if (ready(data, "warp")) {
					COOLDOWNS.put("warp", 5);
					send("warp", dir.x, dir.z);
				}
			} else {
				dashTapTimer = 7;
			}
		}
		dashWasDown = dashDown;
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
		}
		boolean jumpDown = free && client.options.keyJump.isDown();
		if (!jumpDown || !airborne) {
			hovering = false;
		}
		if (airborne && jumpDown && !jumpWasDown) {
			if (ready(data, "extra_jump") && jumpsUsed < (int) value(data, "extra_jump", "JUMPS")) {
				jumpsUsed++;
				Vec3 v = player.getDeltaMovement();
				player.setDeltaMovement(v.x, 0.55, v.z);
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
		int x = cfg.hudX;
		int y = cfg.hudY;
		int w = 90;
		g.fill(x, y, x + w, y + 7, 0xA0000000);
		g.fill(x + 1, y + 1, x + 1 + Math.round((w - 2) * Math.min(1F, endurance / maxEndurance)), y + 6, 0xFF000000 | cfg.hudBarColor);
		g.drawString(font, Component.translatable("enderskills.hud.endurance", Math.round(endurance), Math.round(maxEndurance)),
				x + w + 4, y, 0xFF000000 | cfg.hudTextColor, true);
		y += 12;
		List<String> slots = Abilities.slots(data);
		for (int i = 0; i < slots.size() && i < SLOT_KEYS.length; i++) {
			y = row(g, font, slots.get(i), SLOT_KEYS[i].getTranslatedKeyMessage().getString(), x, y);
		}
		for (String special : new String[] {"dash", "warp", "smash", "hover"}) {
			if (data.owns(special) && (COOLDOWNS.containsKey(special) || ACTIVE.containsKey(special))) {
				y = row(g, font, special, "", x, y);
			}
		}
	}

	private static int row(GuiGraphics g, Font font, String skill, String key, int x, int y) {
		dev.enderskills.data.EsSettings cfg = dev.enderskills.data.EsSettings.get();
		int cooldown = COOLDOWNS.getOrDefault(skill, 0);
		int active = ACTIVE.getOrDefault(skill, 0);
		g.fill(x, y, x + 18, y + 18, active > 0 ? 0xC0205030 : 0xA0000000);
		g.blit(RenderPipelines.GUI_TEXTURED, EnderSkills.id("textures/skill/" + skill + ".png"), x + 1, y + 1, 0.0F, 0.0F,
				16, 16, 32, 32, 32, 32, cooldown > 0 ? 0xFF606060 : 0xFFFFFFFF);
		String text = cooldown > 0 ? (cooldown + 19) / 20 + "s"
				: active > 1_000_000 ? "ON  " + key : active > 0 ? "▶ " + (active + 19) / 20 + "s" : key;
		g.drawString(font, text, x + 22, y + 5, cooldown > 0 ? 0xFF000000 | cfg.hudCooldownColor
				: active > 0 ? 0xFF000000 | cfg.hudActiveColor : 0xFF000000 | cfg.hudReadyColor, true);
		return y + 20;
	}
}
