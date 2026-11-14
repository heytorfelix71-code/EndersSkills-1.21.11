package dev.enderskills.dsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The skill config language of the original Ender Skills ("defaults/*.cfg"), ported 1:1:
 * a property ("COOLDOWN (") has a shape and min/max, and level sections ("{0 to 25} [") that override it.
 * Units: s = seconds (x20 ticks), h = hearts (x2 health), % = /100, b = blocks, e = endurance.
 */
public final class DslConfig {
	public int minLevel;
	public int maxLevel;
	/** Highest level you can buy (top_level), when set. */
	public int limitLevel = -1;
	public final Map<String, Property> map = new LinkedHashMap<>();

	public interface Gradient {
		double get(int min, int max, double start, double end, int level);
	}

	public static final class Property {
		Gradient gradient = clampToMinMax();
		double start;
		double end;
		final Map<String, Section> map = new LinkedHashMap<>();
	}

	public static final class Section {
		Gradient gradient;
		int min;
		int max;
		double start;
		double end;

		boolean applies(int level) {
			return level >= min && level <= max;
		}
	}

	public int buyableMax() {
		int max = limitLevel >= 0 ? limitLevel : maxLevel;
		return Math.min(max, 1000);
	}

	/** Value of a property at a level, times a multiplier (0 if the property is missing). */
	public double get(String name, int level, double multiplier) {
		Property p = map.get(name);
		if (p == null) {
			return 0.0;
		}
		Gradient gradient = p.gradient;
		double start = p.start;
		double end = p.end;
		int min = minLevel;
		int max = maxLevel;
		List<Section> sections = new ArrayList<>(p.map.values());
		for (int i = sections.size() - 1; i >= 0; i--) {
			Section s = sections.get(i);
			end = s.start; // (sic) the original overwrites "end" before testing each section
			if (s.applies(level)) {
				gradient = s.gradient;
				start = s.start;
				end = s.end;
				min = s.min;
				max = s.max;
				break;
			}
		}
		double lo = Math.min(start, end);
		double hi = Math.max(start, end);
		double v;
		try {
			v = gradient.get(min, max, start, end, level);
		} catch (RuntimeException e) {
			v = 0.0;
		}
		v = Math.max(lo, Math.min(hi, v));
		return v * multiplier;
	}

	public boolean has(String name) {
		return map.containsKey(name);
	}

	// ------------------------------------------------------------------ parsing

	public static DslConfig parse(List<String> lines) {
		DslConfig config = new DslConfig();
		Deque<Consumer<String>> stack = new ArrayDeque<>();
		stack.add(line -> {
			if (line.endsWith("(")) {
				Property property = new Property();
				config.map.putIfAbsent(line.substring(0, line.indexOf('(')).trim(), property);
				stack.add(propertyValues(config, property, stack));
			}
		});
		for (String raw : lines) {
			String line = clean(raw);
			if (line.isEmpty() || line.startsWith("v1.0")) {
				continue;
			}
			if (line.startsWith("min_level: ")) {
				config.minLevel = parseLevel(line.substring(11));
				continue;
			}
			if (line.startsWith("max_level: ")) {
				config.maxLevel = parseLevel(line.substring(11));
				continue;
			}
			if (line.startsWith("top_level: ")) {
				config.limitLevel = parseLevel(line.substring(11));
				continue;
			}
			if (!stack.isEmpty()) {
				stack.peekLast().accept(line);
			}
			if (line.startsWith("]") || line.startsWith(")")) {
				stack.pollLast();
			}
		}
		return config;
	}

	private static int parseLevel(String s) {
		return s.contains("infinite") ? Integer.MAX_VALUE : Integer.parseInt(s.trim());
	}

	private static String clean(String raw) {
		StringBuilder b = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			// box drawing frame and the blank braille "fake space" of the original files
			if ((c >= '─' && c <= '╿') || c == '⠀') {
				continue;
			}
			b.append(c);
		}
		String s = b.toString().trim();
		if (s.startsWith("#")) {
			return "";
		}
		int hash = s.indexOf('#');
		if (hash > 0) {
			s = s.substring(0, hash).trim();
		}
		return s;
	}

	private static Consumer<String> propertyValues(DslConfig config, Property p, Deque<Consumer<String>> stack) {
		return line -> {
			if (line.startsWith("shape: ")) {
				p.gradient = curve(p, line.substring(7));
			}
			if (line.startsWith("start: ")) {
				p.start = number(p, line.substring(7));
				p.end = p.start;
			}
			if (line.startsWith("min: ")) {
				p.start = number(p, line.substring(5));
				p.end = p.start;
			}
			if (line.startsWith("end: ")) {
				p.end = number(p, line.substring(5));
			}
			if (line.startsWith("max: ")) {
				p.end = number(p, line.substring(5));
			}
			if (line.startsWith("value: ")) {
				double v = number(p, line.substring(7));
				p.start = v;
				p.end = v;
			}
			if (line.endsWith("[")) {
				String key = line.substring(0, line.indexOf('[')).trim();
				String inner = key.substring(1, key.length() - 1);
				String[] range = inner.split("to");
				Section s = new Section();
				s.gradient = p.gradient;
				s.min = range[0].contains("%") ? 0 : Integer.parseInt(range[0].trim());
				s.max = config.maxLevel;
				s.start = p.start;
				s.end = p.end;
				if (range[0].contains("%")) {
					String t = range[0].trim();
					double f = Double.parseDouble(t.substring(0, t.indexOf('%'))) / 100.0;
					s.max = (int) Math.floor(f * config.maxLevel);
				}
				if (range.length > 1) {
					s.max = Integer.parseInt(range[1].trim());
				}
				p.map.putIfAbsent(key, s);
				stack.add(sectionValues(p, s));
			}
		};
	}

	private static Consumer<String> sectionValues(Property p, Section s) {
		return line -> {
			if (line.startsWith("shape: ")) {
				s.gradient = curve(p, line.substring(7));
			}
			if (line.startsWith("curve: ")) {
				s.gradient = curve(p, line.substring(7));
			}
			if (line.startsWith("start: ")) {
				s.start = number(p, line.substring(7));
				s.end = s.start;
			}
			if (line.startsWith("end: ")) {
				s.end = number(p, line.substring(5));
			}
			if (line.startsWith("return: ")) {
				double v = number(p, line.substring(8));
				s.start = v;
				s.end = v;
			}
			if (line.startsWith("value: ")) {
				double v = number(p, line.substring(7));
				s.start = v;
				s.end = v;
			}
		};
	}

	static double number(Property p, String raw) {
		String s = raw.trim();
		if (s.contains("PLACEHOLDER")) {
			return 0.0;
		}
		if (s.contains("infinite")) {
			return Double.MAX_VALUE;
		}
		if (s.endsWith("%")) {
			return Double.parseDouble(s.substring(0, s.indexOf('%')).trim()) / 100.0;
		}
		if (s.endsWith("s")) {
			return Double.parseDouble(s.substring(0, s.indexOf('s')).trim()) * 20.0;
		}
		if (s.endsWith("b")) {
			return Double.parseDouble(s.substring(0, s.indexOf('b')).trim());
		}
		if (s.endsWith("h")) {
			return Double.parseDouble(s.substring(0, s.indexOf('h')).trim()) * 2.0;
		}
		if (s.endsWith("e")) {
			return Double.parseDouble(s.substring(0, s.indexOf('e')).trim());
		}
		if (s.contains("{")) {
			String inner = s.substring(1, s.length() - 1);
			if (inner.equals("min")) {
				return p.start;
			}
			if (inner.equals("max")) {
				return p.end;
			}
			Section ref = p.map.get(s);
			return ref == null ? 0.0 : ref.end;
		}
		return Double.parseDouble(s);
	}

	static Gradient curve(Property p, String shape) {
		String[] parts = shape.trim().split(" ");
		String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);
		String kind = parts[0];
		if (kind.contains("{")) {
			return p.gradient;
		}
		return switch (kind) {
			case "flat" -> diagonal();
			case "ramp" -> ramp(p, args.length > 0 ? args[0] : "", args.length > 1 ? args[1] : "");
			case "curve" -> curveIncrement(args);
			case "multiply" -> multiples(number(p, args[0]));
			case "f(x," -> function(shape.split("->")[1]);
			case "solve" -> function(shape.split("for")[1]);
			default -> clampToMinMax();
		};
	}

	static Gradient clampToMinMax() {
		return (min, max, start, end, level) -> level == max ? end : start;
	}

	static Gradient diagonal() {
		return (min, max, start, end, level) -> {
			double l = clamp(level, min, max);
			return start + (end - start) * (l / max);
		};
	}

	static Gradient ramp(Property p, String a, String b) {
		double x;
		double y;
		switch (a) {
			case "positive" -> {
				x = 0.5;
				y = 0.5;
			}
			case "negative" -> {
				x = -0.5;
				y = 0.5;
			}
			default -> {
				x = number(p, a);
				y = number(p, b);
			}
		}
		final double knee = y;
		final double bend = x;
		return (min, max, start, end, level) -> {
			double l = clamp(level, min, max);
			double t = (l - min) / (double) (max - min);
			if (t <= knee) {
				t *= 1.0 + bend * (t / knee);
			} else {
				t *= 1.0 + bend * (1.0 - (t - knee));
			}
			return start + (end - start) * t;
		};
	}

	static Gradient curveIncrement(String[] args) {
		double k = Double.parseDouble(args[1]);
		boolean flip = !"positive".equals(args[0]);
		return (min, max, start, end, level) -> {
			double l = clamp(level, min, max);
			double t = (l - min) / (double) (max - min);
			double f = (Math.exp(k * t) - 1.0) / (Math.exp(k) - 1.0);
			return start + (flip ? 1.0 - f : f) * (end - start);
		};
	}

	static Gradient multiples(double factor) {
		return (min, max, start, end, level) -> level * factor;
	}

	static Gradient function(String expression) {
		String expr = expression.replace("{min}", "x").replace("{max}", "y").replace("{level}", "l")
				.replaceAll("([\\d.]+)%", "($1/100)");
		return (min, max, start, end, level) -> Expr.eval(expr, Map.of("x", (double) min, "y", (double) max, "l", (double) level));
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
