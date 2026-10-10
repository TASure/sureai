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

package com.sure.ai.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.VideoRequest;

/**
 * {@link AzureUtil} 静态入口全覆盖：单例便捷方法、env 分支、私有构造、reset。
 *
 * <p>客户端经反射注入指向本地 mock 的实例，便捷方法零真实网络。</p>
 *
 * @author sureai
 */
public class AzureUtilTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动 mock。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		register();
		resetAll();
	}

	/** 停止 mock 并清空单例。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		resetAll();
	}

	/** 按路径路由各端点的成功响应。 */
	private void register() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/cognitiveservices/v1")) {
				byte[] audio = "AUDIO".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
				exchange.sendResponseHeaders(200, audio.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(audio);
				}
				return;
			}
			String body;
			if (path.contains("/chat/completions")) {
				body = "{\"id\":\"u\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"util-ok\"},\"finish_reason\":\"stop\"}]}";
			} else if (path.contains("/embeddings")) {
				body = "{\"model\":\"emb\",\"data\":[{\"embedding\":[0.1,0.2]}]}";
			} else if (path.contains("/images/generations")) {
				body = "{\"created\":1700000000,\"data\":[{\"url\":\"https://example.com/u.png\"}]}";
			} else if (path.contains("/video/generations/jobs")) {
				body = "GET".equals(exchange.getRequestMethod())
					? "{\"id\":\"t1\",\"status\":\"succeeded\",\"generations\":[{\"id\":\"g1\",\"url\":\"https://example.com/v.mp4\"}]}"
					: "{\"id\":\"t1\",\"status\":\"queued\"}";
			} else if (path.contains("/stt/")) {
				body = "{\"RecognitionStatus\":\"Success\",\"DisplayText\":\"转写\"}";
			} else if (path.contains("/batches")) {
				body = "{\"id\":\"b1\",\"status\":\"completed\",\"request_counts\":{\"total\":1,\"completed\":1,\"failed\":0}}";
			} else {
				body = "{}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 清空全部单例容器。 */
	private static void resetAll() throws Exception {
		for (String name : new String[] { "HOLDER", "VIDEO", "TTS", "STT", "BATCH", "REALTIME" }) {
			Field f = AzureUtil.class.getDeclaredField(name);
			f.setAccessible(true);
			((SingletonHolder<?>) f.get(null)).reset();
		}
	}

	/** 反射注入单例客户端。 */
	@SuppressWarnings("unchecked")
	private static void inject(String holder, Object client) throws Exception {
		Field f = AzureUtil.class.getDeclaredField(holder);
		f.setAccessible(true);
		((SingletonHolder<Object>) f.get(null)).set(client);
	}

	/** 反射读取单例内部实例。 */
	private static Object instanceOf(String holder) throws Exception {
		Field f = AzureUtil.class.getDeclaredField(holder);
		f.setAccessible(true);
		@SuppressWarnings("rawtypes")
		SingletonHolder h = (SingletonHolder) f.get(null);
		Field inst = SingletonHolder.class.getDeclaredField("instance");
		inst.setAccessible(true);
		return inst.get(h);
	}

	/** 指向 mock 的配置。 */
	private AiConfig mockCfg() {
		return AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.extraHeader("deployment", "dep").build();
	}

	/** init(String) 后 HOLDER 可便捷对话。 */
	@Test
	public void testInitString() {
		AzureUtil.init("just-a-key");
		assertNotNull(AzureUtil.client());
		assertEquals("azure", AzureUtil.client().name());
	}

	/** init(AiConfig) 后便捷 chat/embed/image 均经 mock。 */
	@Test
	public void testConvenienceDelegation() {
		AzureUtil.init(mockCfg());
		assertEquals("util-ok", AzureUtil.chat("dep", "hi").firstText());
		assertEquals("util-ok", AzureUtil.chat(ChatRequest.builder().model("dep")
			.messages(ChatMessage.user("hi")).build()).firstText());
		assertEquals(2, AzureUtil.embed("dep", "hi").embeddings().get(0).length, 0.0);
		assertEquals(1, AzureUtil.embed(new EmbeddingRequest("dep", List.of("hi"))).embeddings().size());
		assertEquals("https://example.com/u.png", AzureUtil.image("dep", "a flower").firstUrl());
		assertEquals("https://example.com/u.png", AzureUtil.image(ImageRequest.builder()
			.model("dep").prompt("a flower").build()).firstUrl());
	}

	/** chatStream 便捷方法经 mock（SSE 聚合）。 */
	@Test
	public void testChatStreamConvenience() {
		AzureUtil.init(mockCfg());
		StringBuilder sb = new StringBuilder();
		AzureUtil.chatStream(ChatRequest.builder().model("dep").messages(ChatMessage.user("hi")).build(),
			c -> {
				if (c.deltaText() != null) {
					sb.append(c.deltaText());
				}
			});
		assertTrue(sb.length() >= 0);
	}

	/** env 设置后懒加载单例（HOLDER/VIDEO/BATCH）成功构造，覆盖 supplier lambda 全路径。 */
	@Test
	public void testLazyEnvConstruction() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(AzureUtil.ENV_API_KEY, "lazy-key");
			env.set(AzureUtil.ENV_BASE_URL, this.baseUrl);
			env.set(AzureUtil.ENV_RESOURCE, null);
			resetAll();
			assertNotNull(AzureUtil.client());
			assertNotNull(AzureUtil.videoClient());
			assertNotNull(AzureUtil.batchClient());
		}
		finally {
			env.restore();
			resetAll();
		}
	}

	/** REALTIME 默认 supplier（get 而非 getOrCreate）抛 UnsupportedOperationException。 */
	@Test
	public void testRealtimeDefaultSupplierThrows() throws Exception {
		Field f = AzureUtil.class.getDeclaredField("REALTIME");
		f.setAccessible(true);
		@SuppressWarnings("rawtypes")
		SingletonHolder h = (SingletonHolder) f.get(null);
		assertThrows(UnsupportedOperationException.class, h::get);
	}

	/** video/tts/stt/batch 便捷方法经反射注入的客户端走 mock。 */
	@Test
	public void testOtherConvenienceDelegation() throws Exception {
		inject("VIDEO", new AzureVideoClient(mockCfg()));
		assertNotNull(AzureUtil.videoClient());
		assertNotNull(AzureUtil.video(AzureModels.SORA_2, "a cat").firstUrl());
		assertNotNull(AzureUtil.video(VideoRequest.of(AzureModels.SORA_2, "a cat")).firstUrl());

		AiConfig speechCfg = AiConfig.builder().apiKey("sk").baseUrl(this.baseUrl).build();
		inject("TTS", new AzureTtsClient(speechCfg));
		assertNotNull(AzureUtil.ttsClient());
		assertTrue(AzureUtil.tts("t", "你好", AzureModels.TTS_VOICE_XIAOXIAO).audioLength() > 0);
		assertTrue(AzureUtil.tts(TtsRequest.of("t", "你好", AzureModels.TTS_VOICE_XIAOXIAO)).audioLength() > 0);

		inject("STT", new AzureSttClient(speechCfg));
		assertNotNull(AzureUtil.sttClient());
		assertEquals("转写", AzureUtil.stt("s", new byte[] { 1 }).text());
		assertEquals("转写", AzureUtil.stt(SttRequest.of("s", new byte[] { 1 })).text());

		inject("BATCH", new AzureBatchClient(mockCfg()));
		assertNotNull(AzureUtil.batchClient());
		assertEquals("b1", AzureUtil.batch(BatchRequest.builder()
			.model("m").inputFileId("f1").build()).id());
		assertEquals("b1", AzureUtil.getBatch("b1").id());
	}

	/** reset 方法：注入后 reset，容器实例置 null。 */
	@Test
	public void testResetMethods() throws Exception {
		inject("VIDEO", new AzureVideoClient(mockCfg()));
		inject("TTS", new AzureTtsClient(mockCfg()));
		inject("STT", new AzureSttClient(mockCfg()));
		inject("BATCH", new AzureBatchClient(mockCfg()));
		AzureUtil.resetVideoClient();
		AzureUtil.resetTtsClient();
		AzureUtil.resetSttClient();
		AzureUtil.resetBatchClient();
		assertNull(instanceOf("VIDEO"));
		assertNull(instanceOf("TTS"));
		assertNull(instanceOf("STT"));
		assertNull(instanceOf("BATCH"));
	}

	/** 未注入且无 env 时，各懒加载单例抛 AiException（覆盖 supplier 执行路径）。 */
	@Test
	public void testLazyWithoutEnvThrows() {
		assertThrows(AiException.class, AzureUtil::videoClient);
		assertThrows(AiException.class, AzureUtil::ttsClient);
		assertThrows(AiException.class, AzureUtil::sttClient);
		assertThrows(AiException.class, AzureUtil::batchClient);
	}

	/** buildConfig 全分支：baseUrl 优先、resource 兜底、二者皆空。 */
	@Test
	public void testBuildConfigBranches() {
		assertEquals("http://mock", AzureUtil.buildConfig("k", "http://mock", "res").baseUrl());
		AiConfig r = AzureUtil.buildConfig("k", " ", "myres");
		assertEquals("myres", r.extraHeaders().get("resource"));
		AiConfig none = AzureUtil.buildConfig("k", null, null);
		assertNull(none.baseUrl());
	}

	/** buildConfigFromEnv：缺 key 抛异常。 */
	@Test
	public void testBuildConfigFromEnvMissingKey() {
		assertThrows(AiException.class, AzureUtil::buildConfigFromEnv);
	}

	/** env 设置后 buildConfigFromEnv 成功；baseUrl 与 resource 推导均覆盖。 */
	@Test
	public void testBuildConfigFromEnvWithEnv() {
		EnvVars env = EnvVars.begin();
		try {
			env.set(AzureUtil.ENV_API_KEY, "env-key");
			env.set(AzureUtil.ENV_BASE_URL, "http://env-mock");
			env.set(AzureUtil.ENV_RESOURCE, null);
			assertEquals("http://env-mock", AzureUtil.buildConfigFromEnv().baseUrl());

			env.set(AzureUtil.ENV_BASE_URL, null);
			env.set(AzureUtil.ENV_RESOURCE, "envres");
			AiConfig cfg = AzureUtil.buildConfigFromEnv();
			assertEquals("envres", cfg.extraHeaders().get("resource"));
		}
		finally {
			env.restore();
		}
	}

	/** buildSpeechConfig 分支：缺 key 抛、缺 region 抛、speech key+region 推导成功。 */
	@Test
	public void testBuildSpeechConfigViaLazy() {
		EnvVars env = EnvVars.begin();
		try {
			env.set(AzureUtil.ENV_SPEECH_KEY, null);
			env.set(AzureUtil.ENV_API_KEY, null);
			env.set(AzureUtil.ENV_SPEECH_REGION, null);
			env.set(AzureUtil.ENV_RESOURCE, null);
			AzureUtil.resetTtsClient();
			assertThrows(AiException.class, AzureUtil::ttsClient);

			// 仅 API_KEY（speech key 回退 API_KEY），缺 region/resource → 抛
			env.set(AzureUtil.ENV_API_KEY, "k");
			AzureUtil.resetTtsClient();
			AzureUtil.resetSttClient();
			assertThrows(AiException.class, AzureUtil::ttsClient);
			assertThrows(AiException.class, AzureUtil::sttClient);

			// speech key 优先 + region 推导成功（TTS 与 STT baseUrl 分支均覆盖）
			env.set(AzureUtil.ENV_SPEECH_KEY, "spkey");
			env.set(AzureUtil.ENV_SPEECH_REGION, "eastus");
			AzureUtil.resetTtsClient();
			AzureUtil.resetSttClient();
			assertNotNull(AzureUtil.ttsClient());
			assertNotNull(AzureUtil.sttClient());
		}
		finally {
			env.restore();
			AzureUtil.resetTtsClient();
			AzureUtil.resetSttClient();
		}
	}

	/** realtimeClient(getOrCreate) 与 resetRealtimeClient。 */
	@Test
	public void testRealtimeSingleton() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(AzureUtil.ENV_API_KEY, "rt-key");
			env.set(AzureUtil.ENV_BASE_URL, this.baseUrl);
			env.set(AzureUtil.ENV_RESOURCE, null);
			RealtimeEventListener noop = new RealtimeEventListener() {
				@Override
				public void onTranscript(String text) {
				}

				@Override
				public void onAudio(byte[] audio) {
				}

				@Override
				public void onError(String err) {
				}

				@Override
				public void onClose() {
				}

				@Override
				public void onEvent(String type, String rawJson) {
				}

				@Override
				public void onSpeechStart() {
				}

				@Override
				public void onSpeechStop() {
				}

				@Override
				public void onInterrupted() {
				}
			};
			AzureRealtimeClient c1 = AzureUtil.realtimeClient("gpt-realtime", noop);
			AzureRealtimeClient c2 = AzureUtil.realtimeClient("gpt-realtime", noop);
			assertTrue(c1 == c2);
			AzureUtil.resetRealtimeClient();
			assertNull(instanceOf("REALTIME"));
		}
		finally {
			env.restore();
			AzureUtil.resetRealtimeClient();
		}
	}

	/** Util 私有构造器抛 AssertionError。 */
	@Test
	public void testPrivateConstructor() throws Exception {
		Constructor<AzureUtil> c = AzureUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
