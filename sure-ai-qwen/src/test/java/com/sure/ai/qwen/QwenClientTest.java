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

package com.sure.ai.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.Capability;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;

/**
 * {@link QwenClient} 与 {@link QwenUtil} 集成测试：本地 HttpServer mock。
 *
 * <p>官方文档：https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class QwenClientTest {

	private HttpServer server;
	private String baseUrl;
	private final java.util.concurrent.atomic.AtomicReference<String> lastUri =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastAuth =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastBody =
		new java.util.concurrent.atomic.AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/compatible-mode/v1";
		resetUtil();
	}

	/** 停止服务。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		resetUtil();
	}

	/** 反射清空静态单例。 */
	private static void resetUtil() throws Exception {
		Field f = QwenUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端。 */
	private QwenClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("sk-qwen").baseUrl(this.baseUrl).build();
		return new QwenClient(cfg);
	}

	/** 注册 JSON 处理器。 */
	private void handle(int status, String responseBody) {
		handle(exchange -> respond(exchange, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastUri.set(exchange.getRequestURI().toString());
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			h.handle(exchange);
		});
	}

	/** 发送 JSON 响应。 */
	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 处理器函数式接口。 */
	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}

	/** 兼容模式 URL、Bearer 头、chat 响应解析。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"q1\",\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		QwenClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(QwenModels.QWEN_PLUS)
			.messages(ChatMessage.user("hi")).temperature(0.7).build());
		assertEquals("Bearer sk-qwen", this.lastAuth.get());
		assertTrue(this.lastUri.get().contains("/compatible-mode/v1/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"model\":\"qwen-plus\""));
		assertEquals("你好", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"q\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"q\",\"choices\":[{\"delta\":{\"content\":\"好\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"q\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		QwenClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(QwenModels.QWEN_TURBO).messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("你好", sb.toString());
		client.close();
	}

	/** embeddings。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"text-embedding-v3\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		QwenClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(QwenModels.TEXT_EMBEDDING_V3, List.of("hi")));
		assertTrue(this.lastUri.get().contains("/compatible-mode/v1/embeddings"));
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		QwenClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 400 映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"error\":\"bad\"}");
		QwenClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("qwen-max").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		QwenClient client = new QwenClient(AiConfig.of("sk"));
		assertEquals("qwen", client.name());
		client.close();
	}

	/** Util 便捷 chat 与 embed。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		QwenUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", QwenUtil.chat("qwen-plus", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util embed 便捷方法。 */
	@Test
	public void testUtilEmbed() {
		handle(200, "{\"model\":\"emb\",\"data\":[{\"embedding\":[1.0]}],"
			+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}");
		QwenUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals(1, QwenUtil.embed("text-embedding-v3", "hi").embeddings().size());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, QwenUtil::client);
	}

	/** buildConfig 分支。 */
	@Test
	public void testBuildConfig() {
		assertEquals("http://mock", QwenUtil.buildConfig("k", "http://mock").baseUrl());
		assertEquals(null, QwenUtil.buildConfig("k", "").baseUrl());
		assertThrows(AiException.class, QwenUtil::buildConfigFromEnv);
	}

	/** Models 私有构造器。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<QwenModels> c = QwenModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("qwen-max", QwenModels.QWEN_MAX);
	}

	/** TTS（CosyVoice）：原生端点返回 JSON 含 audio.url。 */
	@Test
	public void testTts() {
		handle(200, "{\"output\":{\"audio\":{\"url\":\"https://example.com/tts.mp3\"}},"
			+ "\"request_id\":\"req-tts\"}");
		QwenClient client = newClient();
		TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model(QwenModels.COSYVOICE_V3_5_PLUS).input("你好世界").voice("longanhuan_v3.6")
			.responseFormat("mp3").build());
		assertEquals("https://example.com/tts.mp3", resp.url());
		assertEquals("mp3", resp.format());
		assertTrue(this.lastUri.get().contains("/api/v1/services/audio/tts/SpeechSynthesizer"));
		assertTrue(this.lastBody.get().contains("\"model\":\"cosyvoice-v3.5-plus\""));
		assertTrue(this.lastBody.get().contains("\"text\":\"你好世界\""));
		assertTrue(this.lastBody.get().contains("\"voice\":\"longanhuan_v3.6\""));
		client.close();
	}

	/** STT（Qwen-ASR）：chat/completions input_audio base64 上报，解析 choices 文本。 */
	@Test
	public void testStt() {
		handle(200, "{\"id\":\"asr\",\"model\":\"qwen3-asr-flash\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"识别结果文本\"},\"finish_reason\":\"stop\"}]}");
		QwenClient client = newClient();
		byte[] audio = "fake-audio".getBytes(StandardCharsets.UTF_8);
		SttResponse resp = client.transcribe(SttRequest.builder()
			.model(QwenModels.QWEN3_ASR_FLASH).audioData(audio).build());
		assertEquals("识别结果文本", resp.text());
		assertTrue(this.lastUri.get().contains("/compatible-mode/v1/chat/completions"));
		assertTrue(this.lastBody.get().contains("\"type\":\"input_audio\""));
		String expectedB64 = Base64.getEncoder().encodeToString(audio);
		assertTrue(this.lastBody.get().contains(expectedB64));
		assertTrue(resp.rawJson().contains("qwen3-asr-flash"));
		client.close();
	}

	/** TTS / STT 模型常量。 */
	@Test
	public void testAudioModelsConstants() {
		assertNotNull(QwenModels.COSYVOICE_V3_5_PLUS);
		assertNotNull(QwenModels.COSYVOICE_V3_5_FLASH);
		assertNotNull(QwenModels.QWEN_AUDIO_TTS_PLUS);
		assertNotNull(QwenModels.QWEN3_ASR_FLASH);
	}

	/** 思考模式：reasoningEffort 映射为 enable_thinking=true + thinking_budget，不发送 reasoning_effort。 */
	@Test
	public void testThinkingEnable() {
		handle(200, "{\"id\":\"q\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model(QwenModels.QWEN_PLUS)
			.messages(ChatMessage.user("hi")).reasoningEffort("high").build());
		String body = this.lastBody.get();
		assertTrue("应启用思考模式", body.contains("\"enable_thinking\":true"));
		assertTrue("high 应映射较大 thinking_budget", body.contains("\"thinking_budget\":16384"));
		assertFalse("通义不支持 reasoning_effort，不应发送", body.contains("reasoning_effort"));
		client.close();
	}

	/** 思考模式：thinkingConfig=true（Boolean）→ enable_thinking=true。 */
	@Test
	public void testThinkingConfigBoolean() {
		handle(200, "{\"id\":\"q\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model(QwenModels.QWEN_PLUS)
			.messages(ChatMessage.user("hi")).thinkingConfig(Boolean.TRUE).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"enable_thinking\":true"));
		assertFalse(body.contains("reasoning_effort"));
		client.close();
	}

	/** Grounding：grounding 非 null → enable_search=true，且不注入 web_search 工具。 */
	@Test
	public void testGroundingEnableSearch() {
		handle(200, "{\"id\":\"q\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model(QwenModels.QWEN_PLUS)
			.messages(ChatMessage.user("hi")).grounding("web_search").build());
		String body = this.lastBody.get();
		assertTrue("应开启联网搜索", body.contains("\"enable_search\":true"));
		assertFalse("通义不用 web_search 工具", body.contains("web_search"));
		client.close();
	}

	/**
	 * capabilities() 精确声明：百炼兼容模式主 Client 支持 chat/stream/embed 及原生覆写的 TTS(CosyVoice)/STT(Qwen-ASR)；
	 * 不支持 IMAGE（见 {@link QwenImageClient}）、VIDEO（见 {@link QwenVideoClient}）、FINETUNE（未适配），
	 * 调用应 guard 快速失败。
	 */
	@Test
	public void testCapabilitiesDeclaration() throws Exception {
		QwenClient client = newClient();
		Set<Capability> caps = capabilitiesOf(client);
		for (Capability c : new Capability[]{Capability.CHAT, Capability.CHAT_STREAM,
			Capability.EMBED, Capability.TTS, Capability.STT, Capability.MODERATION}) {
			assertTrue("应声明能力 " + c, caps.contains(c));
		}
		assertFalse("百炼主 Client 不声明 IMAGE（见 QwenImageClient）", caps.contains(Capability.IMAGE));
		assertFalse("百炼主 Client 不声明 VIDEO（见 QwenVideoClient）", caps.contains(Capability.VIDEO));
		assertFalse("百炼主 Client 不声明 FINETUNE（未适配）", caps.contains(Capability.FINETUNE));
		assertEquals(6, caps.size());
		// 未声明能力 → guard 在发请求前抛 AiException
		assertThrows(AiException.class, () -> client.generate(ImageRequest.builder()
			.model("wanx").prompt("x").build()));
		assertThrows(AiException.class, () -> client.generate(VideoRequest.builder()
			.model("wanx2.1").prompt("x").build()));
		assertThrows(AiException.class, () -> client.createFineTune(
			FineTuneRequest.builder().model("qwen-max").trainingFileId("f-1").build()));
		client.close();
	}

	/** 反射调用 protected capabilities()。 */
	@SuppressWarnings("unchecked")
	private static Set<Capability> capabilitiesOf(Object client) throws Exception {
		Method m = AbstractAiClient.class.getDeclaredMethod("capabilities");
		m.setAccessible(true);
		return (Set<Capability>) m.invoke(client);
	}
}
