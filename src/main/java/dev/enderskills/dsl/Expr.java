package dev.enderskills.dsl;

import java.util.Map;

/**
 * Tiny math expression evaluator for the "solve for ..." config shapes and the Ender level function.
 * Supports + - * / ^, parentheses, implicit multiplication ("2 x"), the constants e and pi,
 * variables and a few functions (sqrt, abs, min, max, floor, ceil, log, exp).
 */
public final class Expr {
	private final String s;
	private final Map<String, Double> vars;
	private int pos;

	private Expr(String s, Map<String, Double> vars) {
		this.s = s;
		this.vars = vars;
	}

	public static double eval(String expression, Map<String, Double> vars) {
		Expr e = new Expr(expression, vars);
		double v = e.sum();
		e.skip();
		if (e.pos < e.s.length()) {
			throw new IllegalArgumentException("Unexpected '" + e.s.charAt(e.pos) + "' in " + expression);
		}
		return v;
	}

	private void skip() {
		while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
			pos++;
		}
	}

	private boolean eat(char c) {
		skip();
		if (pos < s.length() && s.charAt(pos) == c) {
			pos++;
			return true;
		}
		return false;
	}

	private double sum() {
		double v = product();
		while (true) {
			if (eat('+')) {
				v += product();
			} else if (eat('-')) {
				v -= product();
			} else {
				return v;
			}
		}
	}

	private double product() {
		double v = power();
		while (true) {
			if (eat('*')) {
				v *= power();
			} else if (eat('/')) {
				v /= power();
			} else if (startsFactor()) {
				v *= power(); // implicit multiplication: "2 x", "2(x+1)"
			} else {
				return v;
			}
		}
	}

	private boolean startsFactor() {
		skip();
		if (pos >= s.length()) {
			return false;
		}
		char c = s.charAt(pos);
		return Character.isLetterOrDigit(c) || c == '(' || c == '.';
	}

	private double power() {
		double base = unary();
		if (eat('^')) {
			return Math.pow(base, power());
		}
		return base;
	}

	private double unary() {
		if (eat('-')) {
			return -unary();
		}
		if (eat('+')) {
			return unary();
		}
		return atom();
	}

	private double atom() {
		skip();
		if (eat('(')) {
			double v = sum();
			eat(')');
			return v;
		}
		int start = pos;
		if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
			while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
				pos++;
			}
			return Double.parseDouble(s.substring(start, pos));
		}
		while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
			pos++;
		}
		String name = s.substring(start, pos);
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Bad expression: " + s);
		}
		if (eat('(')) {
			double a = sum();
			double b = Double.NaN;
			if (eat(',')) {
				b = sum();
			}
			eat(')');
			return switch (name) {
				case "sqrt" -> Math.sqrt(a);
				case "abs" -> Math.abs(a);
				case "floor" -> Math.floor(a);
				case "ceil" -> Math.ceil(a);
				case "log", "ln" -> Math.log(a);
				case "exp" -> Math.exp(a);
				case "min" -> Math.min(a, b);
				case "max" -> Math.max(a, b);
				default -> throw new IllegalArgumentException("Unknown function " + name);
			};
		}
		Double v = vars.get(name);
		if (v != null) {
			return v;
		}
		return switch (name) {
			case "e" -> Math.E;
			case "pi" -> Math.PI;
			default -> throw new IllegalArgumentException("Unknown variable " + name);
		};
	}
}
