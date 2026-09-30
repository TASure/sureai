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

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.agent.tool.builtin.CalculatorTool;
import com.sure.ai.agent.tool.builtin.DateTimeTool;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.ToolCall;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;

/**
 * 代码助手示例：流式输出 + Function Calling 工具调用。
 *
 * <p>演示两件事：</p>
 * <ol>
 *   <li><b>流式对话</b>：{@code chatStream} 逐 chunk 打印增量文本；</li>
 *   <li><b>Function Calling</b>：注册 {@code calculator}（白名单四则运算）与
 *       {@code datetime}（当前时间）两个工具，交由 {@link ReActAgent} 跑通
 *       「模型决策工具调用 → 工具执行 → 结果回灌 → 最终答案」完整链路。</li>
 * </ol>
 *
 * <p><b>双模式（可离线跑通）：</b>检测到 {@code SURE_AI_OPENAI_API_KEY} 时用真实 OpenAI；
 * 否则用 {@link OfflineModelClient} 脚本化模拟模型首轮返回 tool_calls、次轮给最终答案，
 * 全程零真实网络。</p>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class CodeAssistantDemo {

	private CodeAssistantDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		// --- 1. 注册工具：复用内置 calculator / datetime ---
		ToolRegistry registry = new ToolRegistry();
		CalculatorTool calculator = new CalculatorTool();
		registry.register(CalculatorTool.toToolFunction(), calculator);
		DateTimeTool dateTime = new DateTimeTool();
		registry.register(DateTimeTool.toToolFunction(), dateTime);
		System.out.println("[CodeAssistantDemo] 已注册工具：" + registry.size()
				+ " 个（calculator / datetime）");

		// --- 2. 选择模型客户端 ---
		boolean real = hasOpenAiKey();
		AiClient client;
		String model;
		if (real) {
			System.out.println("[CodeAssistantDemo] 检测到 Key，使用真实 OpenAI 模式。");
			OpenAiClient openAi = new OpenAiClient(AiConfig.builder()
					.apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());
			client = openAi;
			model = OpenAiModels.GPT_4O_MINI;
		} else {
			System.out.println("[CodeAssistantDemo] 未检测到 Key，使用离线 Fake 模型（零网络）。");
			client = new OfflineModelClient();
			model = "fake-model";
		}

		// --- 3. 流式输出演示 ---
		System.out.println();
		System.out.println("=== 流式对话（chatStream 逐 chunk）===");
		StringBuilder streamBuf = new StringBuilder();
		client.chatStream(ChatRequest.builder()
				.model(model)
				.messages(List.of(ChatMessage.user("用一句话说你是谁。")))
				.build(),
				chunk -> {
					if (chunk.deltaText() != null) {
						streamBuf.append(chunk.deltaText());
						System.out.print(chunk.deltaText());
					}
				});
		System.out.println();
		System.out.println("  拼接结果：" + streamBuf);

		// --- 4. Function Calling：ReAct 工具循环 ---
		System.out.println();
		System.out.println("=== Function Calling（ReAct 工具循环）===");
		ChatRequest base = ChatRequest.builder()
				.model(model)
				.messages(List.of(ChatMessage.system("你是一个会用工具的代码助手。")))
				.build();
		ReActAgent agent = new ReActAgent(client, base, registry, new PrintListener(),
				5, Duration.ofSeconds(30));
		String question = "帮我算 (12+30)*2，并告诉我现在的时间。";
		System.out.println("  用户问题：" + question);
		String answer = agent.run(question);
		System.out.println("  最终答案：" + answer);

		client.close();
		System.out.println();
		System.out.println("CodeAssistantDemo 完成。");
	}

	private static boolean hasOpenAiKey() {
		String key = System.getenv("SURE_AI_OPENAI_API_KEY");
		return key != null && !key.isBlank();
	}

	/** 打印每轮工具调用事件。 */
	private static final class PrintListener implements AgentListener {
		@Override
		public void onToolCall(ToolCall toolCall) {
			System.out.println("  -> 调用工具 " + toolCall.name() + " args=" + toolCall.argumentsJson());
		}

		@Override
		public void onToolResult(ToolCall toolCall, String result) {
			System.out.println("  <- 工具结果 " + toolCall.name() + " = " + result);
		}

		@Override
		public void onFinish(String finalAnswer) {
			System.out.println("  模型收尾。");
		}

		@Override
		public void onError(Throwable error) {
			System.out.println("  !! 异常 " + error.getMessage());
		}
	}

	/** 离线模型：流式逐字吐出；首轮返回两个 tool_calls，次轮给最终答案。 */
	private static final class OfflineModelClient implements AiClient {
		private int turn;

		@Override
		public String name() {
			return "offline-code-assistant";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.turn++;
			if (this.turn == 1) {
				List<ToolCall> calls = List.of(
						new ToolCall("call-calc", "calculator", "{\"expression\":\"(12+30)*2\"}"),
						new ToolCall("call-time", "datetime", "{\"zone\":\"Asia/Shanghai\"}"));
				return resp(ChatMessage.assistant(calls), "tool_calls");
			}
			return resp(ChatMessage.assistant("计算结果：(12+30)*2=84；当前时间见上方 datetime 工具输出。"),
					"stop");
		}

		private ChatResponse resp(ChatMessage message, String finish) {
			return ChatResponse.of("offline-resp", "fake-model",
					List.of(Choice.of(0, message, finish)),
					TokenUsage.of(10, 20, 30), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
			String text = "【离线流式】你好，我是 sureai 代码助手，能做四则运算并查询时间。";
			for (String piece : text.split("")) {
				consumer.accept(ChatStreamChunk.of("offline-stream", null, piece, null, null));
			}
		}

		@Override
		public void close() {
		}
	}
}
