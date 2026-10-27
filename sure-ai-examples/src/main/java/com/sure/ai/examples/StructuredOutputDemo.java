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
package com.sure.ai.examples;

import java.util.List;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

/**
 * 结构化输出（Structured Output）使用示例。
 *
 * <p>演示 OpenAI 兼容平台通过 {@code responseFormat("json_object")} 强制模型输出 JSON，
 * 再用 {@link JsonMapper} 将返回文本反序列化为 Java record，避免手工解析字符串。
 * 检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class StructuredOutputDemo {

	/** 结构化目标 record：字段名须与模型输出 JSON 的 key 一致。 */
	public record CityInfo(String city, String country, int population) {
	}

	private StructuredOutputDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI 结构化输出（json_object）+ JsonMapper ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			String prompt = "从下面这句话中提取城市信息，只输出 JSON，不要任何解释文字。"
				+ "JSON 必须包含三个字段：city(string)、country(string)、population(int，单位为人)。"
				+ "原文：东京是日本的首都，都心人口约 1396 万。";

			ChatRequest req = ChatRequest.builder()
				.model(OpenAiModels.GPT_4O_MINI)
				.messages(List.of(ChatMessage.user(prompt)))
				// OpenAI 兼容平台："json_object" 即 {"type":"json_object"}
				.responseFormat("json_object")
				.build();

			ChatResponse resp = OpenAiUtil.chat(req);
			String raw = resp.firstText();
			System.out.println("  模型原始输出：" + raw);

			JsonObject json = Json.parse(raw).getAsJsonObject();
			CityInfo info = JsonMapper.fromJson(json, CityInfo.class);
			System.out.println("  反序列化 record：city=" + info.city()
				+ "，country=" + info.country()
				+ "，population=" + info.population());
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
