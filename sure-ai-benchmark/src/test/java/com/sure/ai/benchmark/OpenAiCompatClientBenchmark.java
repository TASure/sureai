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

package com.sure.ai.benchmark;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * {@link OpenAiCompatClient#buildChatBody} 请求体序列化路径微基准。
 *
 * <p>纯对象操作，无网络：仅复用 client 实例调用 protected 方法构建 JsonObject 并序列化为字符串。</p>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -pl sure-ai-benchmark exec:java -Dexec.args="OpenAiCompatClientBenchmark.* -wi 3 -i 5 -f 1 -tu us"
 * </pre>
 *
 * @author sureai
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class OpenAiCompatClientBenchmark {

	/** 暴露 protected buildChatBody 的可见子类（仅测试用）。 */
	private static final class VisibleClient extends OpenAiCompatClient {
		VisibleClient(AiConfig config) {
			super(config);
		}

		JsonObject body(ChatRequest req, boolean stream) {
			return buildChatBody(req, stream);
		}
	}

	/** 被测客户端（不发网络请求，仅用于调用 buildChatBody）。 */
	private VisibleClient client;

	/** 预热请求：system + user + assistant(tool_calls) + tool 结果 + tools。 */
	private ChatRequest request;

	/**
	 * 初始化客户端与请求样例。
	 */
	@Setup
	public void setUp() {
		this.client = new VisibleClient(AiConfig.of("benchmark-key"));
		ChatMessage system = ChatMessage.system("你是一个天气助手。");
		ChatMessage user = ChatMessage.user("上海今天天气？");
		ChatMessage assistant = ChatMessage.assistant("我需要查询天气。");
		ChatMessage toolMsg = ChatMessage.tool("call-1", "{\"temp\":25}");
		ToolSpec spec = ToolSpec.of(ToolFunction.of("get_weather", "查天气",
			"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"));
		this.request = ChatRequest.builder()
			.model("gpt-4o")
			.messages(system, user, assistant, toolMsg)
			.temperature(0.5d)
			.maxTokens(512)
			.tools(List.of(spec))
			.toolChoice("auto")
			.build();
	}

	/**
	 * 序列化 ChatRequest 为 OpenAI 兼容 JSON 请求体（buildChatBody + stringify）。
	 *
	 * @return 请求体字符串
	 */
	@Benchmark
	public String serializeChatRequestBody() {
		JsonObject body = this.client.body(this.request, false);
		return Json.stringify(body);
	}
}
