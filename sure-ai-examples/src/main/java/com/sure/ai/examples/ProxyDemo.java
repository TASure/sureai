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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.proxy.ProxyConfig;
import com.sure.ai.proxy.SureAiProxy;

/**
 * OpenAI 兼容代理示例。
 *
 * <p>本 Demo 完全离线：注册一个 FakeAiClient，在端口 8080 启动 OpenAI 兼容 HTTP 代理，
 * 然后用 JDK HttpClient 走 loopback 完成一次 /v1/chat/completions 调用。</p>
 *
 * <p>生产环境把 FakeAiClient 换成任意平台客户端即可。任何 OpenAI SDK 把 baseUrl 指向
 * {@code http://localhost:8080/v1} 即可使用。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class ProxyDemo {

	private ProxyDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		try {
			demo();
		} catch (Exception e) {
			System.out.println("Proxy 示例执行异常：" + e.getMessage());
			System.out.println("（如果是端口占用，请修改 ProxyConfig 中的端口后重试）");
		}
	}

	private static void demo() throws Exception {
		System.out.println("=== 1. 注册离线 client 并创建 Gateway ===");
		ClientRegistry registry = new ClientRegistry();
		registry.register("fake", new FakeAiClient());
		GatewayClient gateway = new GatewayClient(registry);
		System.out.println("  已注册 fake client");

		System.out.println();
		System.out.println("=== 2. 创建 ProxyConfig（端口 8080）===");
		Properties props = new Properties();
		props.setProperty("proxy.port", "8080");
		props.setProperty("proxy.default.model", "fake-model");
		props.setProperty("proxy.models", "fake-model");
		props.setProperty("proxy.key.sk-demo-key", "demo-tenant");
		ProxyConfig config = ProxyConfig.fromProperties(props);
		System.out.println("  端口: " + config.port());
		System.out.println("  默认模型: " + config.defaultModel());
		System.out.println("  虚拟密钥: sk-demo-key → demo-tenant");

		System.out.println();
		System.out.println("=== 3. 启动代理 ===");
		SureAiProxy proxy = new SureAiProxy(gateway, registry, config);
		proxy.start();
		int port = proxy.boundPort();
		System.out.println("  代理已启动: http://localhost:" + port + "/v1");

		System.out.println();
		System.out.println("=== 4. loopback 自测：POST /v1/chat/completions ===");
		HttpClient client = HttpClient.newHttpClient();
		String body = """
			{"model":"fake-model","messages":[{"role":"user","content":"你好"}]}
			""";
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/v1/chat/completions"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer sk-demo-key")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp = client.send(request,
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		System.out.println("  HTTP " + resp.statusCode());
		System.out.println("  响应: " + resp.body());

		System.out.println();
		System.out.println("=== 5. curl 测试命令 ===");
		System.out.println("  curl http://localhost:" + port + "/v1/chat/completions \\");
		System.out.println("    -H \"Authorization: Bearer sk-demo-key\" \\");
		System.out.println("    -H \"Content-Type: application/json\" \\");
		System.out.println("    -d '{\"model\":\"fake-model\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}'");
		System.out.println();
		System.out.println("  curl http://localhost:" + port + "/v1/models \\");
		System.out.println("    -H \"Authorization: Bearer sk-demo-key\"");

		proxy.stop();
		System.out.println();
		System.out.println("Proxy 示例完成，代理已停止。");
	}

	/** 离线 fake client：返回固定回声。 */
	private static final class FakeAiClient implements AiClient {
		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			String text = request.messages().isEmpty() ? "" : request.messages().get(0).content();
			return ChatResponse.of("fake-id", request.model(),
				List.of(Choice.of(0, ChatMessage.assistant("echo: " + text), "stop")),
				null, null);
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}
}
