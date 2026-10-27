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

package com.sure.ai.baidu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * {@link BaiduClient} 测试：本地 HttpServer mock token 与 chat 接口。
 *
 * @author sureai
 * @since 0.1.0
 */
public class BaiduClientTest {

	private static final String API_KEY = "test-ak";
	private static final String SECRET_KEY = "test-sk";
	private static final String TOKEN = "mock-access-token";

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger tokenHits = new AtomicInteger();
	private final AtomicReference<String> lastChatPath = new AtomicReference<>();
	private final AtomicReference<String> lastChatBody = new AtomicReference<>();
	private final AtomicReference<String> ttsContentType = new AtomicReference<>();
	private final AtomicReference<String> ttsBody = new AtomicReference<>();
	private final AtomicReference<String> sttBody = new AtomicReference<>();
	private final AtomicReference<String> sttPath = new AtomicReference<>();
	private final AtomicReference<String> oauthMethod = new AtomicReference<>();
	private final AtomicReference<String> oauthQuery = new AtomicReference<>();
	private final AtomicReference<String> oauthContentType = new AtomicReference<>();
	private final AtomicReference<String> oauthBody = new AtomicReference<>();
	private final AtomicReference<String> lastChatAuthz = new AtomicReference<>();
	private final AtomicReference<String> lastBizAuthz = new AtomicReference<>();

	/** TTS/STT 失败模式：null=正常，"ttsErr"=TTS 返回 JSON 错误，"sttErr"=STT 返回 err_no!=0。 */
	private String audioMode;

	/** 启动本地服务，注册 token/chat/embeddings 响应。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.tokenHits.set(0);
		this.lastChatPath.set(null);
		this.lastChatBody.set(null);
		this.ttsContentType.set(null);
		this.ttsBody.set(null);
		this.sttBody.set(null);
		this.sttPath.set(null);
		this.oauthMethod.set(null);
		this.oauthQuery.set(null);
		this.oauthContentType.set(null);
		this.oauthBody.set(null);
		this.lastChatAuthz.set(null);
		this.lastBizAuthz.set(null);
		this.audioMode = null;
		registerHandlers();
		setSingleton(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		setSingleton(null);
	}

	/** 反射设置 Util 单例。 */
	@SuppressWarnings("unchecked")
	private static void setSingleton(BaiduClient c) {
		try {
			Field f = BaiduUtil.class.getDeclaredField("MAIN");
			f.setAccessible(true);
			SingletonHolder<BaiduClient> holder = (SingletonHolder<BaiduClient>) f.get(null);
			if (c == null) {
				holder.reset();
			} else {
				holder.set(c);
			}
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String query = exchange.getRequestURI().getQuery();
			if (path.equals("/oauth/2.0/token")) {
				this.tokenHits.incrementAndGet();
				this.oauthMethod.set(exchange.getRequestMethod());
				this.oauthQuery.set(query);
				this.oauthContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				byte[] tokenIn = exchange.getRequestBody().readAllBytes();
				this.oauthBody.set(new String(tokenIn, StandardCharsets.UTF_8));
				if ("tokenBad".equals(this.audioMode)) {
					respond(exchange, 500, "{\"error\":\"boom\"}", "application/json");
					return;
				}
				if ("tokenNoAccess".equals(this.audioMode)) {
					respond(exchange, 200, "{\"expires_in\":2592000}", "application/json");
					return;
				}
				String body = "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":2592000}";
				respond(exchange, 200, body, "application/json");
				return;
			}
			if (path.contains("/chat/")) {
				this.lastChatPath.set(path + (query == null ? "" : "?" + query));
				this.lastChatAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				this.lastBizAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.lastChatBody.set(new String(in, StandardCharsets.UTF_8));
				String req = this.lastChatBody.get();
				if (req.contains("\"stream\":true")) {
					String sse = "data: {\"id\":\"r\",\"result\":\"你\",\"is_end\":false}\n\n"
						+ "data: {\"id\":\"r\",\"result\":\"好\",\"is_end\":true}\n\n";
					respond(exchange, 200, sse, "text/event-stream");
				} else if (req.contains("\"__err__\"")) {
					respond(exchange, 400, "{\"error_code\":110,\"error_msg\":\"invalid token\"}",
						"application/json");
				} else {
					String body = "{\"id\":\"r1\",\"object\":\"chat.completion\",\"created\":1,"
						+ "\"result\":\"你好，我是文心\",\"need_clear_history\":false,"
						+ "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":6,\"total_tokens\":9}}";
					respond(exchange, 200, body, "application/json");
				}
				return;
			}
			if (path.contains("/embeddings/")) {
				this.lastBizAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				String body = "{\"id\":\"e\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}";
				respond(exchange, 200, body, "application/json");
				return;
			}
			if (path.equals("/text2audio")) {
				this.ttsContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.ttsBody.set(new String(in, StandardCharsets.UTF_8));
				if ("ttsErr".equals(this.audioMode)) {
					respond(exchange, 200,
						"{\"err_no\":3001,\"err_msg\":\"text length is too long\"}", "application/json");
					return;
				}
				byte[] audio = "ID3fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);
				respondBytes(exchange, 200, audio, "audio/mpeg");
				return;
			}
			if (path.equals("/server_api")) {
				this.sttPath.set(path);
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.sttBody.set(new String(in, StandardCharsets.UTF_8));
				if ("sttErr".equals(this.audioMode)) {
					respond(exchange, 200,
						"{\"err_no\":3301,\"err_msg\":\"audio quality error\"}", "application/json");
					return;
				}
				if ("sttEmpty".equals(this.audioMode)) {
					respond(exchange, 200,
						"{\"err_no\":0,\"err_msg\":\"success.\",\"result\":[]}", "application/json");
					return;
				}
				if ("sttNoResult".equals(this.audioMode)) {
					respond(exchange, 200,
						"{\"err_no\":0,\"err_msg\":\"success.\"}", "application/json");
					return;
				}
				respond(exchange, 200,
					"{\"err_no\":0,\"err_msg\":\"success.\",\"result\":[\"你好世界\"]}", "application/json");
				return;
			}
			if (path.contains("/finetune/create")) {
				this.lastBizAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.lastChatBody.set(new String(in, StandardCharsets.UTF_8));
				respond(exchange, 200, "{\"taskId\":\"ft-1\",\"status\":\"Running\",\"baseModel\":\"ernie-4.5\"}",
					"application/json");
				return;
			}
			if (path.contains("/finetune/get")) {
				this.lastBizAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				respond(exchange, 200, "{\"taskId\":\"ft-1\",\"status\":\"Done\","
					+ "\"baseModel\":\"ernie-4.5\",\"fineTunedModel\":\"ernie-4.5-sft-1\"}", "application/json");
				return;
			}
			if (path.contains("/files/upload")) {
				this.lastBizAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				if ("uploadNoId".equals(this.audioMode)) {
					respond(exchange, 200, "{\"fileName\":\"train.jsonl\"}", "application/json");
					return;
				}
				respond(exchange, 200, "{\"fileId\":\"file-123\",\"fileName\":\"train.jsonl\"}",
					"application/json");
				return;
			}
			respond(exchange, 404, "{}", "application/json");
		});
	}

	/** 发送响应。 */
	private static void respond(HttpExchange ex, int status, String body, String contentType) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 发送二进制响应。 */
	private static void respondBytes(HttpExchange ex, int status, byte[] body, String contentType)
			throws IOException {
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(body);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private BaiduClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY)
			.extraHeader(BaiduClient.TTS_URL_HEADER, this.baseUrl + "/text2audio")
			.extraHeader(BaiduClient.STT_URL_HEADER, this.baseUrl + "/server_api")
			.build();
		return new BaiduClient(cfg);
	}

	/** 正常 chat：解析 result 字段，请求体无 model 字段。 */
	@Test
	public void testChatResultParsing() {
		BaiduClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("ernie-4.0-turbo-8k")
			.messages(ChatMessage.user("你好")).build());
		assertEquals("你好，我是文心", resp.firstText());
		assertEquals(9, resp.usage().totalTokens());
		String body = this.lastChatBody.get();
		assertTrue(body.contains("\"role\":\"user\""));
		assertFalse("chat body must not contain model field", body.contains("\"model\""));
		client.close();
	}

	/** token 缓存：连续两次 chat，token 接口只调用一次。 */
	@Test
	public void testTokenFetchedOnlyOnce() {
		BaiduClient client = newClient();
		client.chat(ChatRequest.builder().model("ernie-3.5-8k").messages(ChatMessage.user("a")).build());
		client.chat(ChatRequest.builder().model("ernie-3.5-8k").messages(ChatMessage.user("b")).build());
		assertEquals(1, this.tokenHits.get());
		client.close();
	}

	/** OAuth 凭证走 POST body 表单，URL 查询串不含 client_secret/client_id。 */
	@Test
	public void testOauthCredentialsInRequestBody() {
		BaiduClient client = newClient();
		client.chat(ChatRequest.builder().model("ernie-speed-128k").messages(ChatMessage.user("hi")).build());
		assertEquals("POST", this.oauthMethod.get());
		assertEquals("application/x-www-form-urlencoded", this.oauthContentType.get());
		String body = this.oauthBody.get();
		assertNotNull(body);
		assertTrue("oauth body must contain grant_type: " + body,
			body.contains("grant_type=client_credentials"));
		assertTrue("oauth body must contain client_id: " + body,
			body.contains("client_id=" + API_KEY));
		assertTrue("oauth body must contain client_secret: " + body,
			body.contains("client_secret=" + SECRET_KEY));
		// URL 查询串不得携带任何 OAuth 凭证
		String q = this.oauthQuery.get();
		assertTrue("oauth query must be empty: " + q, q == null || q.isBlank());
		assertFalse("oauth query must not contain client_secret: " + q,
			q != null && q.contains("client_secret="));
		client.close();
	}

	/** 业务 API 用 Authorization: Bearer 头，URL 查询串不含 access_token。 */
	@Test
	public void testBusinessApiUsesBearerHeader() {
		BaiduClient client = newClient();
		client.chat(ChatRequest.builder().model("ernie-speed-128k").messages(ChatMessage.user("hi")).build());
		assertEquals("Bearer " + TOKEN, this.lastChatAuthz.get());
		assertTrue(this.lastChatPath.get().contains("/chat/ernie-speed-128k"));
		assertFalse("chat URL must not contain access_token: " + this.lastChatPath.get(),
			this.lastChatPath.get().contains("access_token"));
		client.close();
	}

	/** embed 业务接口同样走 Bearer 头，URL 不带 access_token。 */
	@Test
	public void testEmbedUsesBearerHeader() {
		BaiduClient client = newClient();
		client.embed(new EmbeddingRequest("embedding-v1", List.of("hi")));
		assertEquals("Bearer " + TOKEN, this.lastBizAuthz.get());
		client.close();
	}

	/** SSE 流式聚合 result 增量，is_end=true 结束。 */
	@Test
	public void testStreamAggregation() {
		BaiduClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model("ernie-lite-8k")
			.messages(ChatMessage.user("hi")).build(), chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("你好", sb.toString());
		assertEquals("stop", finish[0]);
		client.close();
	}

	/** 错误体 error_code/error_msg 映射到 AiApiException.errorCode。 */
	@Test
	public void testErrorCodeMapping() {
		BaiduClient client = newClient();
		ChatRequest req = ChatRequest.builder().model("ernie-3.5-8k")
			.messages(ChatMessage.user("__err__")).build();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(req));
		assertEquals(400, e.getHttpStatus());
		assertEquals("110", e.getErrorCode());
		assertTrue(e.getRawBody().contains("invalid token"));
		client.close();
	}

	/** embeddings。 */
	@Test
	public void testEmbeddings() {
		BaiduClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("embedding-v1", List.of("hi")));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		client.close();
	}

	/** 多模态：ImagePart 映射为 image_url（data URL 形式）。 */
	@Test
	public void testMultimodalImage() {
		BaiduClient client = newClient();
		List<MessagePart> parts = List.of(TextPart.of("看图"),
			ImagePart.ofBase64("aW1n", "image/png"));
		client.chat(ChatRequest.builder().model("ernie-4.0-turbo-8k")
			.messages(ChatMessage.user(parts)).build());
		String body = this.lastChatBody.get();
		assertTrue(body.contains("\"type\":\"image_url\""));
		assertTrue(body.contains("\"image_url\""));
		assertTrue(body.contains("\"url\":\"data:image/png;base64,aW1n\""));
		client.close();
	}

	/** 多模态：DocumentPart 直接抛 AiException（百度不支持 PDF）。 */
	@Test
	public void testDocumentPartThrows() {
		BaiduClient client = newClient();
		List<MessagePart> parts = List.of(
			DocumentPart.ofBase64("a.pdf", "application/pdf", "UE9E"));
		AiException e = assertThrows(AiException.class, () -> client.chat(
			ChatRequest.builder().model("ernie-4.0-turbo-8k")
				.messages(ChatMessage.user(parts)).build()));
		assertTrue(e.getMessage().contains("Baidu does not support document/PDF"));
		client.close();
	}

	/** 结构化输出：responseFormat 为字符串时直接透传 response_format。 */
	@Test
	public void testStructuredOutputString() {
		BaiduClient client = newClient();
		client.chat(ChatRequest.builder().model("ernie-4.0-turbo-8k")
			.messages(ChatMessage.user("json"))
			.responseFormat("json_object").build());
		String body = this.lastChatBody.get();
		assertTrue(body.contains("\"response_format\":\"json_object\""));
		client.close();
	}

	/** 结构化输出：含 type 的对象提取 type 字符串。 */
	@Test
	public void testStructuredOutputObject() {
		BaiduClient client = newClient();
		java.util.Map<String, Object> format = new java.util.LinkedHashMap<>();
		format.put("type", "json_object");
		client.chat(ChatRequest.builder().model("ernie-4.0-turbo-8k")
			.messages(ChatMessage.user("json"))
			.responseFormat(format).build());
		String body = this.lastChatBody.get();
		assertTrue(body.contains("\"response_format\":\"json_object\""));
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("baidu", newClient().name());
		assertEquals("https://aip.baidubce.com", BaiduClient.DEFAULT_BASE_URL);
	}

	/** Models 常量。 */
	@Test
	public void testModelsConstants() {
		assertEquals("ernie-4.0-turbo-8k", BaiduModels.ERNIE_4_0_TURBO_8K);
		assertEquals("embedding-v1", BaiduModels.EMBEDDING_V1);
		List<String> ids = List.of(BaiduModels.ERNIE_4_0_TURBO_8K, BaiduModels.ERNIE_3_5_8K,
			BaiduModels.ERNIE_SPEED_128K, BaiduModels.ERNIE_LITE_8K, BaiduModels.EMBEDDING_V1);
		for (String id : ids) {
			assertFalse(id.isEmpty());
		}
	}

	/** Util：init(ak, sk) + 便捷方法。 */
	@Test
	public void testUtilConvenience() {
		BaiduUtil.init(API_KEY, SECRET_KEY);
		// 指向真实默认 baseUrl 会联网，改为反射注入 mock 客户端
		setSingleton(newClient());
		ChatResponse resp = BaiduUtil.chat("ernie-3.5-8k", "hi");
		assertEquals("你好，我是文心", resp.firstText());
		assertNotNull(BaiduUtil.client());
	}

	/** Util：init(AiConfig) 不触网。 */
	@Test
	public void testUtilInitByConfig() {
		BaiduUtil.init(AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build());
		assertNotNull(BaiduUtil.client());
	}

	// ==================== TTS ====================

	/** TTS 成功：返回二进制 mp3，请求为 form-urlencoded 且含 tok/tex/per/aue。 */
	@Test
	public void testTts() {
		BaiduClient client = newClient();
		TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model("baidu").input("你好").voice(BaiduModels.TTS_PER_XIAOMEI)
			.responseFormat("mp3").speed(1.0).build());
		assertTrue(resp.audioLength() > 0);
		assertEquals("mp3", resp.format());
		assertTrue(this.ttsContentType.get().startsWith("application/x-www-form-urlencoded"));
		String body = this.ttsBody.get();
		assertTrue(body.contains("tok=" + TOKEN));
		assertTrue(body.contains("tex="));
		assertTrue(body.contains("per=" + BaiduModels.TTS_PER_XIAOMEI));
		assertTrue(body.contains("aue=" + BaiduModels.TTS_AUE_MP3));
		assertTrue(body.contains("ctp=1"));
		assertTrue(body.contains("lan=zh"));
		client.close();
	}

	/** TTS 错误：JSON 错误体抛 AiApiException，err_no 透传。 */
	@Test
	public void testTtsError() {
		this.audioMode = "ttsErr";
		BaiduClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.synthesize(
			TtsRequest.of("baidu", "x", BaiduModels.TTS_PER_XIAOMEI)));
		assertEquals("3001", e.getErrorCode());
		assertTrue(e.getMessage().contains("text length is too long"));
		client.close();
	}

	// ==================== STT ====================

	/** STT 成功：解析 result[0]，请求体含 speech base64 与 len。 */
	@Test
	public void testStt() {
		BaiduClient client = newClient();
		byte[] audio = new byte[] { 1, 2, 3, 4, 5, 6 };
		SttResponse resp = client.transcribe(SttRequest.of("baidu", audio));
		assertEquals("你好世界", resp.text());
		String body = this.sttBody.get();
		assertTrue(body.contains("\"speech\":\""));
		assertTrue(body.contains("\"len\":6"));
		assertTrue(body.contains("\"token\":\"" + TOKEN + "\""));
		assertTrue(body.contains("\"dev_pid\":1537"));
		client.close();
	}

	/** STT 错误：err_no!=0 抛 AiApiException。 */
	@Test
	public void testSttError() {
		this.audioMode = "sttErr";
		BaiduClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.transcribe(
			SttRequest.of("baidu", new byte[] { 9, 9 })));
		assertEquals("3301", e.getErrorCode());
		client.close();
	}

	/** Util 便捷 tts/stt 委托单例客户端。 */
	@Test
	public void testUtilAudioConvenience() {
		setSingleton(newClient());
		TtsResponse tts = BaiduUtil.tts("baidu", "hi", BaiduModels.TTS_PER_XIAOMEI);
		assertTrue(tts.audioLength() > 0);
		SttResponse stt = BaiduUtil.stt("baidu", new byte[] { 1, 2 });
		assertEquals("你好世界", stt.text());
	}

	/** 创建微调任务：POST finetune/create，映射 Running→running。 */
	@Test
	public void testCreateFineTune() {
		BaiduClient client = newClient();
		FineTuneResponse resp = client.createFineTune(FineTuneRequest.builder()
			.model("ernie-4.5").trainingFileId("file-123").suffix("sft-1").build());
		assertEquals("ft-1", resp.id());
		assertEquals("running", resp.status());
		assertEquals("ernie-4.5", resp.model());
		assertTrue(this.lastChatBody.get().contains("\"baseModel\":\"ernie-4.5\""));
		assertTrue(this.lastChatBody.get().contains("file-123"));
		client.close();
	}

	/** 查询微调任务：Done→succeeded，产出模型解析。 */
	@Test
	public void testGetFineTune() {
		BaiduClient client = newClient();
		FineTuneResponse resp = client.getFineTune("ft-1");
		assertEquals("ft-1", resp.id());
		assertEquals("succeeded", resp.status());
		assertEquals("ernie-4.5-sft-1", resp.fineTunedModel());
		assertTrue(resp.isCompleted());
		client.close();
	}

	/** 上传训练文件返回 file_id。 */
	@Test
	public void testUploadTrainingFile() {
		BaiduClient client = newClient();
		String fileId = client.uploadTrainingFile("train.jsonl", "{\"q\":\"a\"}".getBytes(StandardCharsets.UTF_8));
		assertEquals("file-123", fileId);
		assertEquals("Bearer " + TOKEN, this.lastBizAuthz.get());
		client.close();
	}

	// ==================== 边界分支（反射覆盖私有静态方法） ====================

	/** mapAue：null/blank→3、wav→6、pcm→4、缺省→3。 */
	@Test
	public void testMapAue() throws Exception {
		assertEquals(3, invokeMapAue(null));
		assertEquals(3, invokeMapAue("  "));
		assertEquals(6, invokeMapAue("wav"));
		assertEquals(4, invokeMapAue("pcm"));
		assertEquals(3, invokeMapAue("mp3"));
	}

	/** formatFromAue：4→pcm、6→wav、缺省→mp3。 */
	@Test
	public void testFormatFromAue() throws Exception {
		assertEquals("pcm", invokeFormatFromAue(4));
		assertEquals("wav", invokeFormatFromAue(6));
		assertEquals("mp3", invokeFormatFromAue(9));
	}

	/** mapSpeed：null→5、clamp 边界。 */
	@Test
	public void testMapSpeed() throws Exception {
		assertEquals(5, invokeMapSpeed(null));
		assertEquals(8, invokeMapSpeed(1.5));
		assertEquals(15, invokeMapSpeed(10.0));
		assertEquals(0, invokeMapSpeed(0.0));
	}

	/** parseTtsError：非 JSON → 原样 AiApiException。 */
	@Test
	public void testParseTtsErrorNonJson() throws Exception {
		java.lang.reflect.Method m = BaiduClient.class.getDeclaredMethod("parseTtsError", String.class);
		m.setAccessible(true);
		Object ex = m.invoke(null, "not-json{{");
		assertTrue(ex instanceof AiApiException);
	}

	/** resolveDevPid：Number/字符串/非法字符串/缺省。 */
	@Test
	public void testResolveDevPid() throws Exception {
		assertEquals(1537, invokeDevPid(SttRequest.builder().model("m").audioData(new byte[] { 1 }).build()));
		SttRequest num = SttRequest.builder().model("m").audioData(new byte[] { 1 }).extra("dev_pid", 1737).build();
		assertEquals(1737, invokeDevPid(num));
		SttRequest str = SttRequest.builder().model("m").audioData(new byte[] { 1 }).extra("dev_pid", "1936").build();
		assertEquals(1936, invokeDevPid(str));
		SttRequest bad = SttRequest.builder().model("m").audioData(new byte[] { 1 }).extra("dev_pid", "abc").build();
		assertEquals(1537, invokeDevPid(bad));
	}

	/** mapStatus：各状态串与缺省。 */
	@Test
	public void testMapStatus() throws Exception {
		assertEquals("running", invokeMapStatus("Running"));
		assertEquals("queued", invokeMapStatus("Pending"));
		assertEquals("succeeded", invokeMapStatus("Done"));
		assertEquals("failed", invokeMapStatus("Failed"));
		assertEquals("cancelled", invokeMapStatus("Cancelled"));
		assertEquals("unknown", invokeMapStatus(""));
		assertEquals("weird", invokeMapStatus("weird"));
	}

	private static int invokeMapAue(String f) throws Exception {
		java.lang.reflect.Method m = BaiduClient.class.getDeclaredMethod("mapAue", String.class);
		m.setAccessible(true);
		return (int) m.invoke(null, f);
	}

	private static String invokeFormatFromAue(int a) throws Exception {
		java.lang.reflect.Method m = BaiduClient.class.getDeclaredMethod("formatFromAue", int.class);
		m.setAccessible(true);
		return (String) m.invoke(null, a);
	}

	private static int invokeMapSpeed(Double s) throws Exception {
		java.lang.reflect.Method m = BaiduClient.class.getDeclaredMethod("mapSpeed", Double.class);
		m.setAccessible(true);
		return (int) m.invoke(null, s);
	}

	private static int invokeDevPid(SttRequest r) throws Exception {
		java.lang.reflect.Method m = BaiduClient.class.getDeclaredMethod("resolveDevPid", SttRequest.class);
		m.setAccessible(true);
		return (int) m.invoke(null, r);
	}

	private static String invokeMapStatus(String s) throws Exception {
		java.lang.reflect.Method m = BaiduClient.class.getDeclaredMethod("mapStatus", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, s);
	}

	/** TTS：pcm 格式映射。 */
	@Test
	public void testTtsDefaultsAndPcm() {
		BaiduClient client = newClient();
		com.sure.ai.model.TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model("baidu").input("你好").voice(BaiduModels.TTS_PER_XIAOMEI).responseFormat("pcm").build());
		assertEquals("pcm", resp.format());
		assertTrue(resp.audioLength() > 0);
		client.close();
	}

	/** chat：temperature/topP/max_tokens 透传。 */
	@Test
	public void testChatOptionalParams() {
		BaiduClient client = newClient();
		client.chat(ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi"))
			.temperature(0.5).topP(0.9).maxTokens(128).build());
		String body = this.lastChatBody.get();
		assertTrue(body.contains("\"temperature\":0.5"));
		assertTrue(body.contains("\"top_p\":0.9"));
		assertTrue(body.contains("\"max_output_tokens\":128"));
		client.close();
	}

	/** 缺 secretKey：fetchToken 抛 AiException。 */
	@Test
	public void testMissingSecretKey() {
		BaiduClient client = new BaiduClient(AiConfig.builder().apiKey("ak").baseUrl(this.baseUrl).build());
		assertThrows(AiException.class, () -> client.chat(
			ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** STT：result 为空数组 → 空文本。 */
	@Test
	public void testSttEmptyResult() {
		this.audioMode = "sttEmpty";
		BaiduClient client = newClient();
		SttResponse resp = client.transcribe(SttRequest.of("baidu", new byte[] { 1, 2 }));
		assertEquals("", resp.text());
		client.close();
	}

	/** 微调：suffix 与 hyperparameters 透传。 */
	@Test
	public void testFineTuneWithHyperparams() {
		BaiduClient client = newClient();
		java.util.Map<String, Object> hp = new java.util.LinkedHashMap<>();
		hp.put("epoch", 3);
		FineTuneResponse resp = client.createFineTune(FineTuneRequest.builder()
			.model("ernie-4.5").trainingFileId("file-123").suffix("sft-1").hyperparameters(hp).build());
		assertTrue(this.lastChatBody.get().contains("\"modelName\":\"sft-1\""));
		assertTrue(this.lastChatBody.get().contains("\"hyperParameters\""));
		assertEquals("ft-1", resp.id());
		client.close();
	}

	// ==================== 网络错误路径 ====================

	/** TTS 网络不可达 → IOException 映射 AiTimeoutException。 */
	@Test
	public void testTtsNetworkError() {
		BaiduClient client = new BaiduClient(AiConfig.builder().apiKey("ak")
			.baseUrl("http://127.0.0.1:1").extraHeader(BaiduClient.SECRET_KEY_HEADER, "sk").build());
		assertThrows(com.sure.ai.exception.AiTimeoutException.class,
			() -> client.synthesize(TtsRequest.of("baidu", "x", BaiduModels.TTS_PER_XIAOMEI)));
		client.close();
	}

	/** STT 网络不可达 → IOException 映射 AiTimeoutException。 */
	@Test
	public void testSttNetworkError() {
		BaiduClient client = new BaiduClient(AiConfig.builder().apiKey("ak")
			.baseUrl("http://127.0.0.1:1").extraHeader(BaiduClient.SECRET_KEY_HEADER, "sk").build());
		assertThrows(com.sure.ai.exception.AiTimeoutException.class,
			() -> client.transcribe(SttRequest.of("baidu", new byte[] { 1 })));
		client.close();
	}

	/** chat 网络不可达 → AiTimeoutException。 */
	@Test
	public void testChatNetworkError() {
		BaiduClient client = new BaiduClient(AiConfig.builder().apiKey("ak")
			.baseUrl("http://127.0.0.1:1").extraHeader(BaiduClient.SECRET_KEY_HEADER, "sk").build());
		assertThrows(com.sure.ai.exception.AiTimeoutException.class,
			() -> client.chat(ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 线程已中断 → fetchToken send 抛 InterruptedException 映射 AiException。 */
	@Test
	public void testFetchTokenInterrupted() {
		BaiduClient client = newClient();
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, () -> client.chat(
				ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi")).build()));
		}
		finally {
			Thread.interrupted();
		}
		client.close();
	}

	/** STT result 字段缺失 → 空文本。 */
	@Test
	public void testSttResultMissing() {
		this.audioMode = "sttNoResult";
		BaiduClient client = newClient();
		SttResponse resp = client.transcribe(SttRequest.of("baidu", new byte[] { 1, 2 }));
		assertEquals("", resp.text());
		client.close();
	}

	/** token 端点 500 → AiAuthException。 */
	@Test
	public void testTokenBad() {
		this.audioMode = "tokenBad";
		BaiduClient client = newClient();
		assertThrows(com.sure.ai.exception.AiAuthException.class,
			() -> client.chat(ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** token 响应缺 access_token → AiAuthException。 */
	@Test
	public void testTokenNoAccess() {
		this.audioMode = "tokenNoAccess";
		BaiduClient client = newClient();
		assertThrows(com.sure.ai.exception.AiAuthException.class,
			() -> client.chat(ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** uploadTrainingFile 响应无 fileId → AiException。 */
	@Test
	public void testUploadNoFileId() {
		this.audioMode = "uploadNoId";
		BaiduClient client = newClient();
		assertThrows(AiException.class,
			() -> client.uploadTrainingFile("x.jsonl", "{}".getBytes(StandardCharsets.UTF_8)));
		client.close();
	}

	/** message content 为 null → 空串 content 分支。 */
	@Test
	public void testNullContent() {
		BaiduClient client = newClient();
		client.chat(ChatRequest.builder().model("ernie")
			.messages(ChatMessage.user((String) null)).build());
		assertTrue(this.lastChatBody.get().contains("\"content\":\"\""));
		client.close();
	}
}
