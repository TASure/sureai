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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.deepseek.DeepSeekModels;
import com.sure.ai.gateway.CapabilityRoutingStrategy;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.ExplicitRoutingStrategy;
import com.sure.ai.gateway.FailoverConfig;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.gateway.RoundRobinStrategy;
import com.sure.ai.gateway.RoutingStrategy;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.qwen.QwenClient;
import com.sure.ai.qwen.QwenModels;

/**
 * 多平台统一网关对比演示。
 *
 * <p>把同一个问题通过 {@code ClientRegistry} 注册的 OpenAI / DeepSeek / 通义千问三家
 * 依次路由调用，并用对比表格输出三家回答，直观展示「统一网关 + 显式平台路由」。</p>
 *
 * <p><b>双模式（可离线跑通）：</b>某平台对应环境变量存在时注册真实客户端
 * （{@code SURE_AI_OPENAI_API_KEY} / {@code SURE_AI_DEEPSEEK_API_KEY} /
 * {@code SURE_AI_QWEN_API_KEY}）；缺省时为该平台注册一个 {@link FakePlatformClient}，
 * 返回带平台前缀的脚本化回答，全程零真实网络。</p>
 *
 * <p>路由策略：显式平台路由 → 能力过滤 → 轮询；通过 {@code extra("platform", ...)}
 * 强制把请求打到指定平台，实现「同问题三家对比」。</p>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class MultiPlatformGatewayDemo {

	private MultiPlatformGatewayDemo() {
		throw new AssertionError("No instances");
	}

	/** 参与对比的平台及其默认模型。 */
	private record Platform(String name, String defaultModel) {
	}

	private static final List<Platform> PLATFORMS = List.of(
			new Platform("openai", OpenAiModels.GPT_4O_MINI),
			new Platform("deepseek", DeepSeekModels.DEEPSEEK_CHAT),
			new Platform("qwen", QwenModels.QWEN_PLUS));

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== 1. 注册三家平台客户端 ===");
		ClientRegistry registry = new ClientRegistry();
		for (Platform platform : PLATFORMS) {
			AiClient client = buildClient(platform);
			registry.register(platform.name(), client);
			String mode = (client instanceof FakePlatformClient) ? "离线Fake" : "真实客户端";
			System.out.println("  注册 " + platform.name() + "（" + mode + "，默认模型 "
					+ platform.defaultModel() + "）");
		}

		System.out.println();
		System.out.println("=== 2. 装配网关：显式路由 → 能力过滤 → 轮询 + 故障转移 ===");
		RoutingStrategy strategy = new ExplicitRoutingStrategy(
				new CapabilityRoutingStrategy(new RoundRobinStrategy()));
		FailoverConfig failover = FailoverConfig.builder()
				.maxAttempts(3)
				.unhealthyCooldownMs(30_000L)
				.build();
		GatewayClient gateway = new GatewayClient(registry, strategy, failover);
		System.out.println("  策略: Explicit → Capability → RoundRobin；maxAttempts=3");

		System.out.println();
		System.out.println("=== 3. 同一问题，三家显式路由对比 ===");
		String question = "用一句话解释什么是 RAG。";
		List<Row> rows = new ArrayList<>();
		for (Platform platform : PLATFORMS) {
			ChatRequest req = ChatRequest.builder()
					.model(platform.defaultModel())
					.messages(List.of(ChatMessage.user(question)))
					.extra("platform", platform.name())
					.build();
			ChatResponse resp = gateway.chat(req);
			rows.add(new Row(platform.name(), platform.defaultModel(), resp.firstText()));
		}

		System.out.println("  问题：" + question);
		System.out.println();
		System.out.printf("  %-10s | %-22s | %s%n", "平台", "模型", "回答");
		System.out.println("  " + "-".repeat(8) + "+-" + "-".repeat(22) + "+-" + "-".repeat(40));
		for (Row row : rows) {
			System.out.printf("  %-10s | %-22s | %s%n", row.platform, row.model, row.answer);
		}

		gateway.close();
		System.out.println();
		System.out.println("MultiPlatformGatewayDemo 完成。");
	}

	/** 按平台构建客户端：有 Key 用真实客户端，否则用离线 Fake。 */
	private static AiClient buildClient(Platform platform) {
		String key = switch (platform.name()) {
			case "openai" -> System.getenv("SURE_AI_OPENAI_API_KEY");
			case "deepseek" -> System.getenv("SURE_AI_DEEPSEEK_API_KEY");
			case "qwen" -> System.getenv("SURE_AI_QWEN_API_KEY");
			default -> null;
		};
		if (key == null || key.isBlank()) {
			return new FakePlatformClient(platform.name());
		}
		AiConfig config = AiConfig.builder().apiKey(key).build();
		return switch (platform.name()) {
			case "openai" -> new OpenAiClient(config);
			case "deepseek" -> new DeepSeekClient(config);
			case "qwen" -> new QwenClient(config);
			default -> new FakePlatformClient(platform.name());
		};
	}

	/** 对比表格行。 */
	private record Row(String platform, String model, String answer) {
	}

	/** 离线 fake client：返回带平台前缀的脚本化回答，便于三家对比。 */
	private static final class FakePlatformClient implements AiClient {
		private final String platform;

		FakePlatformClient(String platform) {
			this.platform = platform;
		}

		@Override
		public String name() {
			return this.platform;
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String text = "[" + this.platform + "/fake] RAG 是检索增强生成：先从知识库检索相关文档，"
					+ "再把它作为上下文交给大模型作答（模型=" + request.model() + "）。";
			ChatMessage msg = ChatMessage.assistant(text);
			return ChatResponse.of("fake-" + this.platform, request.model(),
					List.of(Choice.of(0, msg, "stop")),
					TokenUsage.of(12, 30, 42), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
			// 离线 fake：逐字推送一个 chunk 序列，演示流式接线
			for (String piece : chat(request).firstText().split("")) {
				consumer.accept(ChatStreamChunk.of("fake-" + this.platform, null, piece, null, null));
			}
		}

		@Override
		public void close() {
		}
	}
}
