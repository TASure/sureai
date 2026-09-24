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
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * {@link ChatRequest} / {@link ChatMessage} 构建路径微基准。
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -pl sure-ai-benchmark exec:java -Dexec.args="ChatRequestBenchmark.* -wi 3 -i 5 -f 1 -tu us"
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
public class ChatRequestBenchmark {

	/** 工具 JSON Schema 片段（预热数据）。 */
	private static final String WEATHER_PARAMS =
		"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";

	/**
	 * 用 Builder 构造含 system+user+assistant 消息、tools、temperature 的 ChatRequest。
	 *
	 * @return 模型名（防止死代码消除）
	 */
	@Benchmark
	public String buildChatRequest() {
		ChatMessage system = ChatMessage.system("你是一个 helpful 助手。");
		ChatMessage user = ChatMessage.user("今天北京天气如何？");
		ChatMessage assistant = ChatMessage.assistant(List.of(
			ToolCall.of("call-1", "get_weather", "{\"city\":\"北京\"}")));
		ChatMessage tool = ChatMessage.tool("call-1", "{\"temp\":22,\"weather\":\"晴\"}");
		ToolSpec spec = ToolSpec.of(ToolFunction.of("get_weather", "查询天气", WEATHER_PARAMS));
		ChatRequest req = ChatRequest.builder()
			.model("gpt-4o")
			.messages(system, user, assistant, tool)
			.temperature(0.7d)
			.maxTokens(1024)
			.tools(List.of(spec))
			.toolChoice("auto")
			.build();
		return req.model();
	}

	/**
	 * 构造各种类型的 ChatMessage（text / image / tool）。
	 *
	 * @return 消息条数
	 */
	@Benchmark
	public int buildChatMessage() {
		ChatMessage text = ChatMessage.user("请描述这张图片。");
		ChatMessage multimodal = ChatMessage.user(List.of(
			TextPart.of("图里有什么？"),
			ImagePart.ofUrl("https://example.com/p.png")));
		ChatMessage assistantText = ChatMessage.assistant("这是一只猫。");
		ChatMessage toolMsg = ChatMessage.tool("call-9", "result payload");
		return List.of(text, multimodal, assistantText, toolMsg).size();
	}
}
