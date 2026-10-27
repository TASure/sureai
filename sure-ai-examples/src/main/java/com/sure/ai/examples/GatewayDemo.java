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

import com.sure.ai.client.AiClient;
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

/**
 * AI Gateway 示例。
 *
 * <p>本 Demo 完全离线：注册两个 FakeAiClient（分别模拟 platform-a 和 platform-b），
 * 演示 ClientRegistry 注册 → RoundRobin 路由 + FailoverConfig 故障转移 → GatewayClient 调用。
 * 生产环境把 FakeAiClient 换成任意平台客户端（OpenAiClient 等）即可。</p>
 *
 * <p>另附密钥池轮转的代码示例（注释形式，需真实 key 才能运行）。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class GatewayDemo {

	private GatewayDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== 1. 创建 ClientRegistry 并注册两个离线 client ===");
		ClientRegistry registry = new ClientRegistry();
		registry.register("platform-a", new FakeAiClient("platform-a"));
		registry.register("platform-b", new FakeAiClient("platform-b"));
		System.out.println("  已注册: platform-a / platform-b（各 1 实例）");

		System.out.println();
		System.out.println("=== 2. 配置路由策略 + 故障转移 ===");
		// 默认组合：显式平台 → 能力过滤 → 轮询
		RoutingStrategy strategy = new ExplicitRoutingStrategy(
			new CapabilityRoutingStrategy(
				new RoundRobinStrategy()));

		FailoverConfig failover = FailoverConfig.builder()
			.maxAttempts(3)
			.unhealthyCooldownMs(30_000L)
			.build();
		System.out.println("  策略: Explicit → Capability → RoundRobin");
		System.out.println("  故障转移: maxAttempts=3, cooldown=30s");

		System.out.println();
		System.out.println("=== 3. 创建 GatewayClient 并调用（轮询 3 次）===");
		GatewayClient gateway = new GatewayClient(registry, strategy, failover);

		ChatRequest req = ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.user("你好")))
			.build();

		for (int i = 1; i <= 3; i++) {
			ChatResponse resp = gateway.chat(req);
			System.out.printf("  第 %d 次调用 → %s%n", i, resp.firstText());
		}

		System.out.println();
		System.out.println("=== 4. 显式路由：强制指定平台 ===");
		ChatRequest forced = ChatRequest.builder()
			.model("fake-model")
			.messages(List.of(ChatMessage.user("你好")))
			.extra("platform", "platform-b")
			.build();
		ChatResponse resp = gateway.chat(forced);
		System.out.println("  extra[platform]=platform-b → " + resp.firstText());

		System.out.println();
		System.out.println("=== 5. 密钥池轮转示例（需真实 key，注释说明）===");
		System.out.println("  // ApiKeyProvider provider = new RoundRobinApiKeyProvider(");
		System.out.println("  //     List.of(\"sk-key-1\", \"sk-key-2\", \"sk-key-3\"));");
		System.out.println("  // KeyRotatingClientDecorator rotating = new KeyRotatingClientDecorator(");
		System.out.println("  //     provider,");
		System.out.println("  //     apiKey -> OpenAiClient.builder().apiKey(apiKey).build()");
		System.out.println("  // );");
		System.out.println("  // registry.register(\"openai\", rotating);");
		System.out.println("  // 401/429 时自动换 key 重试，详见 docs/gateway.md");

		gateway.close();
		System.out.println();
		System.out.println("Gateway 示例完成。");
	}

	/** 离线 fake client：返回自身平台名作为响应。 */
	private static final class FakeAiClient implements AiClient {
		private final String platform;

		FakeAiClient(String platform) {
			this.platform = platform;
		}

		@Override
		public String name() {
			return this.platform;
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			ChatMessage msg = ChatMessage.assistant("[" + this.platform + "] 收到请求: "
				+ request.model());
			return ChatResponse.of("fake-" + this.platform, request.model(),
				List.of(Choice.of(0, msg, "stop")),
				TokenUsage.of(10, 5, 15), null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
