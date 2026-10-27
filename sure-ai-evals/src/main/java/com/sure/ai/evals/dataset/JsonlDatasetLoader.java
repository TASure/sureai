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

package com.sure.ai.evals.dataset;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.evals.EvalException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;

/**
 * 逐行 JSON（JSONL）数据集加载器（零第三方依赖，复用 core 内置 {@link Json} 解析）。
 *
 * <p>每行一个 JSON 对象，识别字段：</p>
 * <ul>
 *   <li>{@code input} / {@code question} / {@code prompt}：字符串 → 单条 user 消息；
 *       数组 → 消息序列（字符串元素视为 user 消息；对象元素按 {@code role}/{@code content}
 *       还原为对应角色消息）；</li>
 *   <li>{@code expected} / {@code answer} / {@code reference}：期望输出；</li>
 *   <li>{@code id}：用例 ID（缺省自动生成）；</li>
 *   <li>{@code tags}：字符串数组；</li>
 *   <li>{@code expectedTools}：字符串数组（期望工具名序列）；</li>
 *   <li>{@code metadata}：对象，递归转为 {@code Map<String,Object>}。</li>
 * </ul>
 *
 * <p>空行跳过；单行 JSON 语法错误或根类型不是对象时按 {@link BadLineStrategy} 处理——
 * 默认 {@code SKIP} 跳过该行继续加载，{@code FAIL} 立即抛 {@link EvalException}（含行号）。
 * input 缺失的行同样按坏行处理。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class JsonlDatasetLoader implements DatasetLoader {

	/** 坏行处理策略。 */
	public enum BadLineStrategy {
		/** 跳过坏行，继续加载后续行。 */
		SKIP,
		/** 遇到坏行立即抛 {@link EvalException}。 */
		FAIL
	}

	private static final String[] INPUT_KEYS = { "input", "question", "prompt" };
	private static final String[] EXPECTED_KEYS = { "expected", "answer", "reference" };
	private static final String[] TOOL_KEYS = { "expectedTools", "expected_tools" };

	private final String datasetName;
	private final BadLineStrategy badLineStrategy;

	private JsonlDatasetLoader(Builder b) {
		this.datasetName = b.datasetName;
		this.badLineStrategy = b.badLineStrategy;
	}

	/**
	 * 使用默认配置构造加载器（坏行跳过）。
	 */
	public JsonlDatasetLoader() {
		this(builder());
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public EvalDataset load(Reader reader) {
		String text;
		try {
			text = DatasetLoader.readAllText(reader);
		} catch (IOException e) {
			throw new EvalException("读取 JSONL 数据集失败", e);
		}
		List<EvalCase> cases = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String raw = lines[i].trim();
			if (raw.isEmpty()) {
				continue;
			}
			JsonObject obj;
			try {
				JsonElement el = Json.parse(raw);
				if (!el.isObject()) {
					throw new EvalException("第 " + (i + 1) + " 行根类型不是 JSON 对象");
				}
				obj = el.getAsJsonObject();
			} catch (RuntimeException e) {
				handleBadLine(i, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
				continue;
			}
			List<ChatMessage> input = extractInput(obj);
			if (input.isEmpty()) {
				handleBadLine(i, "缺少 input/question/prompt");
				continue;
			}
			EvalCase.Builder cb = EvalCase.builder().input(input);
			JsonElement idEl = obj.get("id");
			if (idEl != null && idEl.isString() && !idEl.getAsString().isBlank()) {
				cb.id(idEl.getAsString().trim());
			}
			cb.expected(firstString(obj, EXPECTED_KEYS));
			cb.tags(stringArray(obj.get("tags")));
			cb.expectedTools(firstStringArray(obj, TOOL_KEYS));
			JsonElement metadata = obj.get("metadata");
			if (metadata != null && metadata.isObject()) {
				cb.metadata(toMap(metadata.getAsJsonObject()));
			}
			cases.add(cb.build());
		}
		return EvalDataset.builder()
			.name(this.datasetName)
			.cases(cases)
			.build();
	}

	private void handleBadLine(int lineIndex, String detail) {
		if (this.badLineStrategy == BadLineStrategy.FAIL) {
			throw new EvalException("JSONL 第 " + (lineIndex + 1) + " 行损坏: " + detail);
		}
	}

	private static List<ChatMessage> extractInput(JsonObject obj) {
		JsonElement el = firstElement(obj, INPUT_KEYS);
		if (el == null || el.isNull()) {
			return List.of();
		}
		if (el.isString()) {
			return List.of(ChatMessage.user(el.getAsString()));
		}
		if (el.isArray()) {
			List<ChatMessage> messages = new ArrayList<>();
			for (JsonElement item : el.getAsJsonArray()) {
				if (item.isString()) {
					messages.add(ChatMessage.user(item.getAsString()));
				} else if (item.isObject()) {
					JsonObject msg = item.getAsJsonObject();
					String role = "user";
					String content = "";
					JsonElement roleEl = msg.get("role");
					if (roleEl != null && roleEl.isString()) {
						role = roleEl.getAsString();
					}
					JsonElement contentEl = msg.get("content");
					if (contentEl != null && contentEl.isString()) {
						content = contentEl.getAsString();
					}
					messages.add(messageOf(role, content));
				}
			}
			return messages;
		}
		return List.of();
	}

	private static ChatMessage messageOf(String role, String content) {
		return switch (role.trim().toLowerCase()) {
			case "system" -> ChatMessage.system(content);
			case "assistant" -> ChatMessage.assistant(content);
			default -> ChatMessage.user(content);
		};
	}

	private static JsonElement firstElement(JsonObject obj, String[] keys) {
		for (String key : keys) {
			JsonElement el = obj.get(key);
			if (el != null && !el.isNull()) {
				return el;
			}
		}
		return null;
	}

	private static String firstString(JsonObject obj, String[] keys) {
		for (String key : keys) {
			JsonElement el = obj.get(key);
			if (el != null && el.isString() && !el.getAsString().isEmpty()) {
				return el.getAsString();
			}
		}
		return null;
	}

	private static List<String> firstStringArray(JsonObject obj, String[] keys) {
		for (String key : keys) {
			JsonElement el = obj.get(key);
			if (el != null && !el.isNull()) {
				return stringArray(el);
			}
		}
		return List.of();
	}

	private static List<String> stringArray(JsonElement el) {
		if (el == null || !el.isArray()) {
			return List.of();
		}
		JsonArray array = el.getAsJsonArray();
		List<String> result = new ArrayList<>();
		for (JsonElement item : array) {
			if (item.isString()) {
				result.add(item.getAsString());
			}
		}
		return result;
	}

	/**
	 * 递归把 JSON 对象转为普通 Map（数字转 long/double，数组转 List，对象转 Map）。
	 *
	 * @param obj JSON 对象
	 * @return 普通 Map
	 */
	static Map<String, Object> toMap(JsonObject obj) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : obj.entries()) {
			Object value = javaValue(entry.getValue());
			if (value != null) {
				map.put(entry.getKey(), value);
			}
		}
		return map;
	}

	private static Object javaValue(JsonElement el) {
		if (el == null || el.isNull()) {
			return null;
		}
		if (el.isString()) {
			return el.getAsString();
		}
		if (el.isNumber()) {
			double d = el.getAsDouble();
			if (d == Math.rint(d) && !Double.isInfinite(d) && !Double.isNaN(d)) {
				return (long) d;
			}
			return d;
		}
		if (el.isBoolean()) {
			return el.getAsBoolean();
		}
		if (el.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonElement item : el.getAsJsonArray()) {
				list.add(javaValue(item));
			}
			return list;
		}
		if (el.isObject()) {
			return toMap(el.getAsJsonObject());
		}
		return el.toString();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String datasetName = "jsonl-dataset";
		private BadLineStrategy badLineStrategy = BadLineStrategy.SKIP;

		private Builder() {
		}

		/**
		 * 设置数据集名。
		 *
		 * @param name 名称
		 * @return this
		 */
		public Builder name(String name) {
			this.datasetName = name;
			return this;
		}

		/**
		 * 设置坏行处理策略。
		 *
		 * @param strategy 策略
		 * @return this
		 */
		public Builder badLineStrategy(BadLineStrategy strategy) {
			this.badLineStrategy = strategy;
			return this;
		}

		/**
		 * 构建加载器。
		 *
		 * @return 加载器
		 */
		public JsonlDatasetLoader build() {
			return new JsonlDatasetLoader(this);
		}
	}
}
