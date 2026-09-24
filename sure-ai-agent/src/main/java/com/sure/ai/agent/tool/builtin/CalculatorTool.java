/*
 * Copyright (c) 2026 sureai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sure.ai.agent.tool.builtin;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;

/**
 * 计算器工具：白名单四则运算（+ - * / 与括号），自研递归下降解析器求值。
 *
 * <p><b>安全：</b>不使用 eval / 反射 / ScriptEngine / 进程调用，不支持变量、函数、幂运算。
 * 词法仅识别数字与六个运算符，语法为标准算术表达式文法：</p>
 * <pre>
 *   expr   = term (('+'|'-') term)*
 *   term   = factor (('*'|'/') factor)*
 *   factor = ('-'|'+') factor | NUMBER | '(' expr ')'
 * </pre>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class CalculatorTool implements ToolHandler {

	/** 工具名。 */
	public static final String TOOL_NAME = "calculator";

	/**
	 * 返回工具函数声明。
	 *
	 * @return ToolFunction
	 */
	public static ToolFunction toToolFunction() {
		return ToolFunction.of(TOOL_NAME,
			"Evaluate an arithmetic expression using + - * / and parentheses. No variables or functions.",
			"""
			{
			  "type": "object",
			  "properties": {
			    "expression": { "type": "string", "description": "arithmetic expression, e.g. (1+2)*3" }
			  },
			  "required": ["expression"]
			}
			""");
	}

	@Override
	public String execute(JsonObject args) {
		if (args == null || !args.has("expression")) {
			return "Error: missing required parameter: expression";
		}
		String expression = args.getString("expression");
		try {
			Parser parser = new Parser(tokenize(expression));
			double value = parser.parseExpr();
			if (!parser.atEnd()) {
				throw new IllegalArgumentException("trailing tokens");
			}
			return format(value, parser.seenDecimal());
		} catch (ArithmeticException e) {
			return "Error: division by zero";
		} catch (RuntimeException e) {
			return "Error: invalid expression: " + e.getMessage();
		}
	}

	private static String format(double value, boolean seenDecimal) {
		boolean integral = !seenDecimal
			&& !Double.isInfinite(value)
			&& !Double.isNaN(value)
			&& value == Math.rint(value);
		return integral ? String.valueOf((long) value) : String.valueOf(value);
	}

	private static List<Token> tokenize(String input) {
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		int n = input.length();
		while (i < n) {
			char c = input.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			switch (c) {
				case '+':
					tokens.add(new Token(TokenType.PLUS, "+"));
					i++;
					break;
				case '-':
					tokens.add(new Token(TokenType.MINUS, "-"));
					i++;
					break;
				case '*':
					tokens.add(new Token(TokenType.STAR, "*"));
					i++;
					break;
				case '/':
					tokens.add(new Token(TokenType.SLASH, "/"));
					i++;
					break;
				case '(':
					tokens.add(new Token(TokenType.LPAREN, "("));
					i++;
					break;
				case ')':
					tokens.add(new Token(TokenType.RPAREN, ")"));
					i++;
					break;
				default:
					if (Character.isDigit(c) || c == '.') {
						int start = i;
						int dots = 0;
						while (i < n && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
							if (input.charAt(i) == '.') {
								dots++;
							}
							i++;
						}
						if (dots > 1) {
							throw new IllegalArgumentException("malformed number: " + input.substring(start, i));
						}
						tokens.add(new Token(TokenType.NUMBER, input.substring(start, i)));
					} else {
						throw new IllegalArgumentException("unexpected character: " + c);
					}
			}
		}
		tokens.add(new Token(TokenType.EOF, ""));
		return tokens;
	}

	/** 词法单元类型。 */
	private enum TokenType {
		/** 数字。 */
		NUMBER,
		/** 加。 */
		PLUS,
		/** 减。 */
		MINUS,
		/** 乘。 */
		STAR,
		/** 除。 */
		SLASH,
		/** 左括号。 */
		LPAREN,
		/** 右括号。 */
		RPAREN,
		/** 结束。 */
		EOF
	}

	/** 词法单元。 */
	private record Token(TokenType type, String text) {
	}

	/** 递归下降解析器。 */
	private static final class Parser {

		private final List<Token> tokens;
		private int pos;
		private boolean seenDecimal;

		Parser(List<Token> tokens) {
			this.tokens = tokens;
		}

		boolean atEnd() {
			return peek().type() == TokenType.EOF;
		}

		boolean seenDecimal() {
			return this.seenDecimal;
		}

		private Token peek() {
			return this.tokens.get(this.pos);
		}

		private Token consume() {
			return this.tokens.get(this.pos++);
		}

		private double parseExpr() {
			double value = parseTerm();
			while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
				Token op = consume();
				double right = parseTerm();
				value = op.type() == TokenType.PLUS ? value + right : value - right;
			}
			return value;
		}

		private double parseTerm() {
			double value = parseFactor();
			while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH) {
				Token op = consume();
				double right = parseFactor();
				if (op.type() == TokenType.SLASH) {
					if (right == 0.0) {
						throw new ArithmeticException("division by zero");
					}
					value = value / right;
				} else {
					value = value * right;
				}
			}
			return value;
		}

		private double parseFactor() {
			Token t = peek();
			switch (t.type()) {
				case MINUS:
					consume();
					return -parseFactor();
				case PLUS:
					consume();
					return parseFactor();
				case LPAREN:
					consume();
					double inner = parseExpr();
					if (peek().type() != TokenType.RPAREN) {
						throw new IllegalArgumentException("missing closing parenthesis");
					}
					consume();
					return inner;
				case NUMBER:
					consume();
					if (t.text().indexOf('.') >= 0) {
						this.seenDecimal = true;
					}
					return Double.parseDouble(t.text());
				default:
					throw new IllegalArgumentException("unexpected token: " + t.text());
			}
		}
	}
}
