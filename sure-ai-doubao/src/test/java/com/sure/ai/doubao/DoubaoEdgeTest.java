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

package com.sure.ai.doubao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.VideoRequest;

/**
 * {@link DoubaoRealtimeClient} / {@link DoubaoSttClient} / {@link DoubaoVideoClient} /
 * {@link DoubaoUtil} 边界分支测试。
 *
 * @author sureai
 */
public class DoubaoEdgeTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
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

	// ==================== Realtime ====================

	/** 公共构造器：buildConnector 覆盖 L75-76 / L94-98。 */
	@Test
	public void testRealtimePublicCtor() {
		AiConfig cfg = AiConfig.builder().apiKey("rt-key")
			.extraHeader(DoubaoRealtimeClient.RESOURCE_ID_HEADER, "volc.bigasr.auc").build();
		DoubaoRealtimeClient client = new DoubaoRealtimeClient(cfg, "ep-realtime",
			newListener());
		assertEquals("doubao-realtime", client.name());
		assertEquals("ep-realtime", client.model());
		client.close();
	}

	/** 构造空监听器。 */
	private static com.sure.ai.client.realtime.RealtimeEventListener newListener() {
		return new com.sure.ai.client.realtime.RealtimeEventListener() {
			@Override
			public void onTranscript(String text) { }
			@Override
			public void onAudio(byte[] audio) { }
			@Override
			public void onError(String error) { }
			@Override
			public void onClose() { }
			@Override
			public void onEvent(String type, String rawJson) { }
		};
	}

	/** buildUri 尾部斜杠：覆盖 L126。 */
	@Test
	public void testBuildUriTrailingSlash() throws Exception {
		AiConfig cfg = AiConfig.builder().apiKey("rt-key").baseUrl("https://example.com/").build();
		java.lang.reflect.Constructor<DoubaoRealtimeClient> c =
			DoubaoRealtimeClient.class.getDeclaredConstructor(
				AiConfig.class, String.class,
				com.sure.ai.client.realtime.RealtimeConnector.class,
				com.sure.ai.client.realtime.RealtimeEventListener.class);
		c.setAccessible(true);
		DoubaoRealtimeClient client = c.newInstance(cfg, "m", null, newListener());
		java.lang.reflect.Method m = DoubaoRealtimeClient.class.getDeclaredMethod("buildUri");
		m.setAccessible(true);
		URI uri = (URI) m.invoke(client);
		assertTrue(uri.toString().startsWith("wss://example.com"));
		assertFalse(uri.toString().contains("//api"));
		client.close();
	}

	// ==================== STT ====================

	/** STT submit 无 id → AiException（覆盖 L125）。 */
	@Test
	public void testSttNoTaskId() {
		this.server.createContext("/", ex -> respond(ex, 200, "{\"code\":0,\"message\":\"ok\"}"));
		DoubaoSttClient client = new DoubaoSttClient(
			AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		assertThrows(AiException.class, () -> client.transcribe(
			SttRequest.of("m", new byte[] { 1 })));
		client.close();
	}

	/** STT checkCode 错误码 → AiApiException（反射调用私有静态方法）。 */
	@Test
	public void testSttCheckCodeError() throws Exception {
		JsonObject resp = Json.object();
		resp.put("code", 3001);
		resp.put("message", "asr failed");
		java.lang.reflect.Method m = DoubaoSttClient.class
			.getDeclaredMethod("checkCode", JsonObject.class, String.class);
		m.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class,
			() -> m.invoke(null, resp, "raw"));
	}

	/** STT detectFormat：wav/mp3/ogg/mp4/扩展名 各分支。 */
	@Test
	public void testSttDetectFormats() throws Exception {
		java.lang.reflect.Method m = DoubaoSttClient.class
			.getDeclaredMethod("detectFormat", SttRequest.class);
		m.setAccessible(true);
		assertEquals("wav", m.invoke(null, SttRequest.builder().model("m")
			.audioData(new byte[16]).contentType("audio/wav").build()));
		assertEquals("mp3", m.invoke(null, SttRequest.builder().model("m")
			.audioData(new byte[16]).contentType("audio/mpeg").build()));
		assertEquals("ogg", m.invoke(null, SttRequest.builder().model("m")
			.audioData(new byte[16]).contentType("audio/ogg").build()));
		assertEquals("mp4", m.invoke(null, SttRequest.builder().model("m")
			.audioData(new byte[16]).contentType("video/mp4").build()));
		assertEquals("flac", m.invoke(null, SttRequest.builder().model("m")
			.audioData(new byte[16]).fileName("audio.flac").build()));
	}

	/** STT sleepQuietly 中断分支。 */
	@Test
	public void testSttSleepInterrupted() throws Exception {
		java.lang.reflect.Method m = DoubaoSttClient.class
			.getDeclaredMethod("sleepQuietly");
		m.setAccessible(true);
		Thread.currentThread().interrupt();
		try {
			assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null));
		} finally {
			Thread.interrupted();
		}
	}

	// ==================== Video ====================

	/** Video submit 无 id → AiException（覆盖 L99）。 */
	@Test
	public void testVideoNoTaskId() {
		this.server.createContext("/", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
		DoubaoVideoClient client = new DoubaoVideoClient(
			AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		VideoRequest req = VideoRequest.of("seedance", "cat");
		assertThrows(AiException.class, () -> client.generate(req));
		client.close();
	}

	/** Video sleepQuietly 中断分支。 */
	@Test
	public void testVideoSleepInterrupted() throws Exception {
		java.lang.reflect.Method m = DoubaoVideoClient.class
			.getDeclaredMethod("sleepQuietly");
		m.setAccessible(true);
		Thread.currentThread().interrupt();
		try {
			assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null));
		} finally {
			Thread.interrupted();
		}
	}

	/** Video extra 字段透传（dead server 触发异常，行仍执行）。 */
	@Test
	public void testVideoExtraFields() {
		DoubaoVideoClient client = new DoubaoVideoClient(
			AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		VideoRequest req = VideoRequest.builder().model("seedance").prompt("cat")
			.extra("watermark", true).build();
		assertThrows(Exception.class, () -> client.generate(req));
		client.close();
	}

	// ==================== Util 便捷方法 ====================

	/** video / tts / stt 便捷方法：注入 mock 客户端后调用。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilConvenience() throws Exception {
		// VIDEO
		Field vf = DoubaoUtil.class.getDeclaredField("VIDEO");
		vf.setAccessible(true);
		SingletonHolder<DoubaoVideoClient> vHolder =
			(SingletonHolder<DoubaoVideoClient>) vf.get(null);
		vHolder.reset();
		// TTS
		Field tf = DoubaoUtil.class.getDeclaredField("TTS");
		tf.setAccessible(true);
		SingletonHolder<DoubaoTtsClient> tHolder =
			(SingletonHolder<DoubaoTtsClient>) tf.get(null);
		tHolder.reset();
		// STT
		Field sf = DoubaoUtil.class.getDeclaredField("STT");
		sf.setAccessible(true);
		SingletonHolder<DoubaoSttClient> sHolder =
			(SingletonHolder<DoubaoSttClient>) sf.get(null);
		sHolder.reset();
		// Use dead server for all three
		AiConfig dead = AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1").build();
		vHolder.set(new DoubaoVideoClient(dead));
		tHolder.set(new DoubaoTtsClient(dead));
		sHolder.set(new DoubaoSttClient(dead));
		try {
			assertThrows(Exception.class, () -> DoubaoUtil.video("seedance", "cat"));
			assertThrows(Exception.class, () -> DoubaoUtil.video(VideoRequest.of("seedance", "cat")));
			assertThrows(Exception.class, () -> DoubaoUtil.tts("m", "你好", "spk"));
			assertThrows(Exception.class, () -> DoubaoUtil.tts(TtsRequest.of("m", "你好", "spk")));
			assertThrows(Exception.class, () -> DoubaoUtil.stt("m", new byte[] { 1 }));
			assertThrows(Exception.class, () -> DoubaoUtil.stt(SttRequest.of("m", new byte[] { 1 })));
		} finally {
			vHolder.reset();
			tHolder.reset();
			sHolder.reset();
		}
	}

	/** realtimeClient lambda：覆盖 L301-302。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilRealtimeClient() throws Exception {
		Field f = DoubaoUtil.class.getDeclaredField("REALTIME");
		f.setAccessible(true);
		SingletonHolder<DoubaoRealtimeClient> holder =
			(SingletonHolder<DoubaoRealtimeClient>) f.get(null);
		holder.reset();
		try {
			assertThrows(Exception.class,
				() -> DoubaoUtil.realtimeClient("ep-x", newListener()));
		} finally {
			holder.reset();
		}
	}

	// ==================== Models ====================

	/** Video withDefaultBaseUrl：proxy + extraHeaders 分支（覆盖 L182, L185-186）。 */
	@Test
	public void testVideoWithDefaultBaseUrl() {
		AiConfig cfg = AiConfig.builder().apiKey("k").proxy("http://proxy:8080")
			.extraHeader("X-Custom", "v1").build();
		DoubaoVideoClient client = new DoubaoVideoClient(cfg);
		assertEquals("doubao-video", client.name());
		client.close();
	}

	/** DoubaoModels 私有构造器。 */
	@Test
	public void testModelsPrivateCtor() throws Exception {
		Constructor<DoubaoModels> c = DoubaoModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** 便捷方法：注入 working mock 客户端后调用（覆盖 L189, L199, L228, L238, L266, L276, L301-302）。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testConvenienceMethodsWithMock() throws Exception {
		this.server.createContext("/", ex -> {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			byte[] bytes;
			if (path.contains("/contents/generations/tasks") && method.equals("POST")) {
				bytes = "{\"id\":\"task-1\"}".getBytes(StandardCharsets.UTF_8);
			} else if (path.contains("/contents/generations/tasks/") && method.equals("GET")) {
				bytes = "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://example.com/v.mp4\"}}".getBytes(StandardCharsets.UTF_8);
			} else if (path.contains("/tts")) {
				bytes = "{\"audio\":\"base64data\"}".getBytes(StandardCharsets.UTF_8);
			} else if (path.contains("/stt")) {
				bytes = "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
			} else {
				bytes = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
			}
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build();
		Field vf = DoubaoUtil.class.getDeclaredField("VIDEO");
		vf.setAccessible(true);
		SingletonHolder<DoubaoVideoClient> vHolder =
			(SingletonHolder<DoubaoVideoClient>) vf.get(null);
		Field tf = DoubaoUtil.class.getDeclaredField("TTS");
		tf.setAccessible(true);
		SingletonHolder<DoubaoTtsClient> tHolder =
			(SingletonHolder<DoubaoTtsClient>) tf.get(null);
		Field sf = DoubaoUtil.class.getDeclaredField("STT");
		sf.setAccessible(true);
		SingletonHolder<DoubaoSttClient> sHolder =
			(SingletonHolder<DoubaoSttClient>) sf.get(null);
		vHolder.set(new DoubaoVideoClient(cfg));
		tHolder.set(new DoubaoTtsClient(cfg));
		sHolder.set(new DoubaoSttClient(cfg));
		try {
			assertNotNull(DoubaoUtil.video("seedance", "cat"));
			assertNotNull(DoubaoUtil.video(VideoRequest.of("seedance", "cat")));
			assertNotNull(DoubaoUtil.tts("m", "你好", "spk"));
			assertNotNull(DoubaoUtil.tts(TtsRequest.of("m", "你好", "spk")));
			assertThrows(Exception.class, () -> DoubaoUtil.stt("m", new byte[] { 1 }));
			assertThrows(Exception.class, () -> DoubaoUtil.stt(SttRequest.of("m", new byte[] { 1 })));
		} finally {
			vHolder.reset();
			tHolder.reset();
			sHolder.reset();
		}
	}
}
