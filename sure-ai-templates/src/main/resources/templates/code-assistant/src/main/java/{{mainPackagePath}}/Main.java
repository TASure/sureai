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

package {{mainPackage}};

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

/**
 * 代码助手模板（结构化输出 + 静态检查工具）。
 *
 * <p>内置 {@code check_code} 工具：对代码片段做纯规则静态检查（未闭合字符串 /
 * 缩进 / 过深嵌套），按行返回问题清单，纯逻辑零网络。设置
 * {@code SURE_AI_OPENAI_API_KEY} 后追加结构化输出示例（{@code json_object} +
 * {@link JsonMapper} 反序列化为 record）。</p>
 *
 * @author sureai
 */
public final class Main {

	/** 结构化目标 record：字段名须与模型输出 JSON 的 key 一致。 */
	public record ReviewResult(String verdict, int issues, String summary) {
	}

	private Main() {
		throw new AssertionError("No instances");
	}

	/**
	 * 静态代码检查（mock 规则引擎）：返回逐行问题清单，无问题返回 "OK"。
	 *
	 * @param code 代码文本（可含换行）
	 * @return 检查结果文本
	 */
	static String checkCode(String code) {
		if (code == null || code.isBlank()) {
			return "代码为空，无可检查内容。";
		}
		List<String> problems = new ArrayList<>();
		String[] lines = code.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int quotes = 0;
			for (char c : line.toCharArray()) {
				if (c == '"') {
					quotes++;
				}
			}
			if (quotes % 2 != 0) {
				problems.add("第 " + (i + 1) + " 行：字符串引号未闭合");
			}
			if (line.length() > 0 && !line.isBlank()
				&& !Character.isWhitespace(line.charAt(0))
				&& Character.isLowerCase(line.charAt(0))
				&& !line.startsWith("class") && !line.startsWith("interface")
				&& !line.startsWith("enum") && !line.startsWith("record")) {
				problems.add("第 " + (i + 1) + " 行：行首未缩进（类成员建议 1 级 Tab）");
			}
			if (line.length() > 100) {
				problems.add("第 " + (i + 1) + " 行：超过 100 字符，建议换行");
			}
		}
		return problems.isEmpty() ? "OK" : String.join("\n", problems);
	}

	/**
	 * 入口。
	 *
	 * @param args 未使用
	 */
	public static void main(String[] args) {
		String sample = "class Demo {\n"
			+ "  public static void main(String[] args) {\n"
			+ "    System.out.println(\"hello);\n"
			+ "  }\n"
			+ "}\n";
		System.out.println("静态检查结果：\n" + checkCode(sample));

		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("未设置 SURE_AI_OPENAI_API_KEY，已演示离线检查；设置后追加结构化输出。");
			return;
		}
		ChatRequest request = ChatRequest.builder()
			.model(OpenAiModels.GPT_4O_MINI)
			.messages(List.of(
				ChatMessage.system("你是代码审查助手，输出 JSON 不要解释文字。"
					+ "JSON 必须含三个字段：verdict(string)、issues(int)、summary(string)。"),
				ChatMessage.user("审查以下代码：\n" + sample)))
			.responseFormat("json_object")
			.build();
		ChatResponse response = OpenAiUtil.chat(request);
		ReviewResult result = JsonMapper.fromJson(
			Json.parse(response.firstText()).getAsJsonObject(), ReviewResult.class);
		System.out.println("结构化结果：verdict=" + result.verdict()
			+ "，issues=" + result.issues()
			+ "，summary=" + result.summary());
	}
}
