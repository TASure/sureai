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
import java.util.function.Consumer;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;

/**
 * Agent 编排（ReAct 多工具循环）示例。
 *
 * <p>本 Demo 完全离线：内置一个 FakeAiClient 模拟模型行为——首轮返回 tool_calls
 * （先查天气、再算温差），工具结果回灌后第二轮给出最终答案，全程零真实网络。
 * 生产环境把 FakeAiClient 换成任意平台客户端（OpenAiCompatClient 等）即可。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class AgentDemo {

	private AgentDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		ToolRegistry registry = new ToolRegistry();

		// 工具1：模拟天气查询（不发真实网络）
		registry.register(ToolFunction.of("get_weather", "查询指定城市当前天气",
				"""
				{"type":"object","required":["city"],
				 "properties":{"city":{"type":"string","description":"城市名"}}}
				"""),
				arg -> {
					String city = arg.getString("city");
					return city + "：晴，气温 26℃，湿度 40%。";
				});

		// 工具2：简易四则运算（JDK 手写解析，无第三方）
		registry.register(ToolFunction.of("calculate", "计算两个整数的加法",
				"""
				{"type":"object","required":["a","b"],
				 "properties":{"a":{"type":"integer"},"b":{"type":"integer"}}}
				"""),
				arg -> String.valueOf(arg.getInt("a") + arg.getInt("b")));

		ChatRequest base = ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.system("你是一个会用工具的助手。")))
			.build();

		AiClient fake = new OfflineModelClient();
		ReActAgent agent = new ReActAgent(fake, base, registry, new PrintListener(),
				5, java.time.Duration.ofSeconds(30));

		System.out.println("[AgentDemo] === 开始 ReAct 编排 ===");
		String answer = agent.run("帮我查一下西安天气，并算 12+30");
		System.out.println("[AgentDemo] === 最终答案 === " + answer);
	}

	/** 打印每轮事件的监听器。 */
	private static final class PrintListener implements AgentListener {
		@Override
		public void onToolCall(ToolCall toolCall) {
			System.out.println("[AgentDemo] -> 调用工具 " + toolCall.name() + " args=" + toolCall.argumentsJson());
		}

		@Override
		public void onToolResult(ToolCall toolCall, String result) {
			System.out.println("[AgentDemo] <- 工具结果 " + toolCall.name() + " = " + result);
		}

		@Override
		public void onFinish(String finalAnswer) {
			System.out.println("[AgentDemo] 模型收尾");
		}

		@Override
		public void onError(Throwable error) {
			System.out.println("[AgentDemo] !! 异常 " + error.getMessage());
		}
	}

	/**
	 * 离线模型：首轮返回两个 tool_calls，第二轮返回最终答案。
	 */
	private static final class OfflineModelClient implements AiClient {
		private int turn;

		@Override
		public String name() {
			return "offline-demo";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.turn++;
			if (this.turn == 1) {
				List<ToolCall> calls = List.of(
						new ToolCall("call-wx", "get_weather", "{\"city\":\"西安\"}"),
						new ToolCall("call-calc", "calculate", "{\"a\":12,\"b\":30}"));
				return resp(ChatMessage.assistant(calls), "tool_calls");
			}
			return resp(ChatMessage.assistant("西安晴 26℃；12+30=42。"), "stop");
		}

		private ChatResponse resp(ChatMessage message, String finish) {
			return ChatResponse.of("demo-resp", "fake-model",
					List.of(Choice.of(0, message, finish)),
					TokenUsage.of(10, 20, 30), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
