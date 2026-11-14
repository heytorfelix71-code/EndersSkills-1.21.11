package dev.enderskills.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Ender Skills progress of a player (immutable: every change makes a new copy, which also syncs it).
 *
 * @param skills     owned skills and their level (an ability starts at level 0 when unlocked)
 * @param order      abilities in the order they were unlocked (later trees cost more)
 * @param enderLevel Ender Level (EL): unlocking your N-th ability needs EL N
 * @param progress   progress towards the next Ender Level (from Ender Tokens)
 * @param storedXp   experience points kept in the altar
 * @param xpSpent    experience spent on skills (part of it comes back on a reset)
 * @param resets     how many times the player reset their skills
 */
public record SkillData(Map<String, Integer> skills, List<String> order, int enderLevel, double progress,
		int storedXp, int xpSpent, int resets) {
	public static final Codec<SkillData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("skills", Map.of()).forGetter(SkillData::skills),
			Codec.STRING.listOf().optionalFieldOf("order", List.of()).forGetter(SkillData::order),
			Codec.INT.optionalFieldOf("ender_level", 1).forGetter(SkillData::enderLevel),
			Codec.DOUBLE.optionalFieldOf("progress", 0.0).forGetter(SkillData::progress),
			Codec.INT.optionalFieldOf("stored_xp", 0).forGetter(SkillData::storedXp),
			Codec.INT.optionalFieldOf("xp_spent", 0).forGetter(SkillData::xpSpent),
			Codec.INT.optionalFieldOf("resets", 0).forGetter(SkillData::resets)
	).apply(i, SkillData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, SkillData> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

	public SkillData {
		skills = Map.copyOf(skills);
		order = List.copyOf(order);
	}

	public static SkillData create() {
		return new SkillData(Map.of(), List.of(), EsSettings.get().defaultEnderLevel, 0.0, 0, 0, 0);
	}

	public boolean owns(String skill) {
		return skills.containsKey(skill);
	}

	/** Level of a skill, or -1 if not owned. */
	public int level(String skill) {
		return skills.getOrDefault(skill, -1);
	}

	public long abilityCount() {
		return skills.keySet().stream().filter(s -> {
			dev.enderskills.skill.Skills.Skill k = dev.enderskills.skill.Skills.get(s);
			return k != null && k.isAbility();
		}).count();
	}

	public SkillData withSkill(String skill, int level, boolean newAbility) {
		Map<String, Integer> map = new LinkedHashMap<>(skills);
		map.put(skill, level);
		List<String> list = order;
		if (newAbility && !order.contains(skill)) {
			list = new ArrayList<>(order);
			list.add(skill);
		}
		return new SkillData(map, list, enderLevel, progress, storedXp, xpSpent, resets);
	}

	public SkillData withoutSkill(String skill) {
		Map<String, Integer> map = new LinkedHashMap<>(skills);
		map.remove(skill);
		List<String> list = new ArrayList<>(order);
		list.remove(skill);
		return new SkillData(map, list, enderLevel, progress, storedXp, xpSpent, resets);
	}

	public SkillData withLevel(int level, double progress) {
		return new SkillData(skills, order, level, progress, storedXp, xpSpent, resets);
	}

	public SkillData withStoredXp(int xp) {
		return new SkillData(skills, order, enderLevel, progress, Math.max(0, xp), xpSpent, resets);
	}

	public SkillData withSpent(int spent) {
		return new SkillData(skills, order, enderLevel, progress, storedXp, spent, resets);
	}

	public SkillData reset(int refund) {
		return new SkillData(Map.of(), List.of(), enderLevel, progress, storedXp + refund, 0, resets + 1);
	}
}
