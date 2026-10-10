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

package com.sure.ai.zhipu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.VideoRequest;

/**
 * {@link ZhipuUtil} / {@link ZhipuJwtGenerator} / {@link ZhipuVideoClient} /
 * {@link ZhipuRealtimeClient} / {@link ZhipuBatchClient} / {@link ZhipuModels} 边界测试。
 *
 * @author sureai
 */
public class ZhipuEdgeTest {

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

	/** 反射获取 SingletonHolder。 */
	@SuppressWarnings("unchecked")
	private static <T> SingletonHolder<T> holder(String field) throws Exception {
		Field f = ZhipuUtil.class.getDeclaredField(field);
		f.setAccessible(true);
		return (SingletonHolder<T>) f.get(null);
	}

	/** 便捷方法：注入 dead server 客户端后调用。 */
	@Test
	public void testConvenienceMethods() throws Exception {
		AiConfig dead = AiConfig.builder().apiKey("id.secret").baseUrl("http://127.0.0.1:1").build();
		holder("HOLDER").set(new ZhipuClient(dead));
		holder("VIDEO").set(new ZhipuVideoClient(dead));
		holder("BATCH").set(new ZhipuBatchClient(dead));
		try {
			assertThrows(Exception.class, () -> ZhipuUtil.image("cog", "cat"));
			assertThrows(Exception.class, () -> ZhipuUtil.image(
				ImageRequest.builder().model("cog").prompt("cat").build()));
			assertThrows(Exception.class, () -> ZhipuUtil.video("v", "cat"));
			assertThrows(Exception.class, () -> ZhipuUtil.video(VideoRequest.of("v", "cat")));
			assertThrows(Exception.class, () -> ZhipuUtil.tts("t", "你好", "spk"));
			assertThrows(Exception.class, () -> ZhipuUtil.tts(TtsRequest.of("t", "你好", "spk")));
			assertThrows(Exception.class, () -> ZhipuUtil.stt("a", new byte[] { 1 }));
			assertThrows(Exception.class, () -> ZhipuUtil.stt(SttRequest.of("a", new byte[] { 1 })));
			assertThrows(Exception.class, () -> ZhipuUtil.batch(null));
			assertThrows(Exception.class, () -> ZhipuUtil.getBatch("bid"));
		} finally {
			holder("HOLDER").reset();
			holder("VIDEO").reset();
			holder("BATCH").reset();
		}
	}

	/** 便捷方法：注入 working mock 客户端后调用（覆盖 L177, L187, L222, L232, L251, L261, L272, L282, L318, L328）。 */
	@Test
	public void testConvenienceMethodsWithMock() throws Exception {
		this.server.createContext("/", ex -> {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			byte[] bytes;
			if (path.contains("/images/generations")) {
				bytes = "{\"data\":[{\"url\":\"https://example.com/img.png\"}]}".getBytes(StandardCharsets.UTF_8);
			} else if (path.contains("/videos/generations")) {
				bytes = "{\"id\":\"task-1\"}".getBytes(StandardCharsets.UTF_8);
			} else if (path.contains("/async-result/")) {
				bytes = "{\"task_status\":\"SUCCESS\",\"video_result\":[{\"url\":\"https://example.com/v.mp4\"}]}".getBytes(StandardCharsets.UTF_8);
			} else {
				bytes = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
			}
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		AiConfig cfg = AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl + "/api/paas/v4").build();
		holder("HOLDER").set(new ZhipuClient(cfg));
		holder("VIDEO").set(new ZhipuVideoClient(cfg));
		holder("BATCH").set(new ZhipuBatchClient(cfg));
		try {
			assertNotNull(ZhipuUtil.image("cog", "cat"));
			assertNotNull(ZhipuUtil.image(ImageRequest.builder().model("cog").prompt("cat").build()));
			assertNotNull(ZhipuUtil.video("v", "cat"));
			assertNotNull(ZhipuUtil.video(VideoRequest.of("v", "cat")));
			assertNotNull(ZhipuUtil.tts("t", "你好", "spk"));
			assertNotNull(ZhipuUtil.tts(TtsRequest.of("t", "你好", "spk")));
			assertNotNull(ZhipuUtil.stt("a", new byte[] { 1 }));
			assertNotNull(ZhipuUtil.stt(SttRequest.of("a", new byte[] { 1 })));
			assertThrows(NullPointerException.class, () -> ZhipuUtil.batch(null));
			assertNotNull(ZhipuUtil.getBatch("bid"));
		} finally {
			holder("HOLDER").reset();
			holder("VIDEO").reset();
			holder("BATCH").reset();
		}
	}

	/** buildFromEnv：env key 设置时覆盖 L120-125。 */
	@Test
	public void testBuildFromEnvWithKey() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "test-key-123");
			env.set(ZhipuUtil.ENV_BASE_URL, this.baseUrl);
			holder("HOLDER").reset();
			ZhipuClient c = ZhipuUtil.client();
			assertNotNull(c);
		} finally {
			env.restore();
			holder("HOLDER").reset();
		}
	}

	/** buildVideoClientFromEnv：env key 设置时覆盖 L204, L209。 */
	@Test
	public void testBuildVideoClientFromEnv() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "v-key");
			env.set(ZhipuUtil.ENV_VIDEO_BASE_URL, this.baseUrl);
			holder("VIDEO").reset();
			ZhipuVideoClient c = ZhipuUtil.videoClient();
			assertNotNull(c);
		} finally {
			env.restore();
			holder("VIDEO").reset();
		}
	}

	/** buildBatchConfigFromEnv：env key 设置时覆盖 L301, L306。 */
	@Test
	public void testBuildBatchConfigFromEnv() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "b-key");
			env.set(ZhipuUtil.ENV_BASE_URL, this.baseUrl);
			holder("BATCH").reset();
			ZhipuBatchClient c = ZhipuUtil.batchClient();
			assertNotNull(c);
		} finally {
			env.restore();
			holder("BATCH").reset();
		}
	}

	/** realtimeClient lambda：覆盖 L353-354。 */
	@Test
	public void testRealtimeClientLambdaOld() throws Exception {
		holder("REALTIME").reset();
		try {
			assertThrows(Exception.class, () -> ZhipuUtil.realtimeClient("rt-model",
				new RealtimeEventListener() {
					@Override public void onTranscript(String text) { }
					@Override public void onAudio(byte[] audio) { }
					@Override public void onError(String error) { }
					@Override public void onClose() { }
					@Override public void onEvent(String type, String rawJson) { }
				}));
		} finally {
			ZhipuUtil.resetRealtimeClient();
		}
	}

	/** REALTIME.reset() 后单例被清空，再次调用应生成新实例（覆盖 L361）。 */
	@Test
	public void testResetRealtimeClient() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "rt-id.rt-secret");
			holder("REALTIME").reset();
			RealtimeEventListener listener = new RealtimeEventListener() {
				@Override public void onTranscript(String text) { }
				@Override public void onAudio(byte[] audio) { }
				@Override public void onError(String error) { }
				@Override public void onClose() { }
				@Override public void onEvent(String type, String rawJson) { }
			};
			ZhipuRealtimeClient first = ZhipuUtil.realtimeClient("rt-model", listener);
			assertNotNull(first);
			ZhipuUtil.resetRealtimeClient();
			ZhipuRealtimeClient second = ZhipuUtil.realtimeClient("rt-model", listener);
			assertNotNull(second);
			assertNotSame("reset 后应生成新实例", first, second);
		} finally {
			env.restore();
			ZhipuUtil.resetRealtimeClient();
		}
	}

	/** VIDEO.reset() 后单例被清空，再次调用应生成新实例（覆盖 L239-240）。 */
	@Test
	public void testResetVideoClient() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "v-id.v-secret");
			holder("VIDEO").reset();
			ZhipuVideoClient first = ZhipuUtil.videoClient();
			assertNotNull(first);
			ZhipuUtil.resetVideoClient();
			ZhipuVideoClient second = ZhipuUtil.videoClient();
			assertNotNull(second);
			assertNotSame("reset 后应生成新实例", first, second);
		} finally {
			env.restore();
			holder("VIDEO").reset();
		}
	}

	/** buildVideoClientFromEnv：env key 缺失时覆盖 L204。 */
	@Test
	public void testBuildVideoClientFromEnvMissingKey() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "");
			holder("VIDEO").reset();
			assertThrows(AiException.class, () -> ZhipuUtil.videoClient());
		} finally {
			env.restore();
			holder("VIDEO").reset();
		}
	}

	/** ZhipuVideoClient：with_audio 与 extra 字段覆盖 L141, L144-145。 */
	@Test
	public void testVideoWithAudioAndExtra() throws Exception {
		this.server.createContext("/", ex -> {
			String path = ex.getRequestURI().getPath();
			byte[] bytes;
			if (path.contains("/videos/generations")) {
				bytes = "{\"id\":\"task-1\"}".getBytes(StandardCharsets.UTF_8);
			} else if (path.contains("/async-result/")) {
				bytes = "{\"task_status\":\"SUCCESS\",\"video_result\":[{\"url\":\"https://example.com/v.mp4\"}]}".getBytes(StandardCharsets.UTF_8);
			} else {
				bytes = "{}".getBytes(StandardCharsets.UTF_8);
			}
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		ZhipuVideoClient client = new ZhipuVideoClient(
			AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl).build());
		VideoRequest req = VideoRequest.builder().model("m").prompt("cat")
			.withAudio(true).extra("seed", 42).build();
		assertNotNull(client.generate(req));
		client.close();
	}

	/** batch(request) 覆盖 L318。 */
	@Test
	public void testBatchWithRequest() throws Exception {
		this.server.createContext("/", ex -> {
			byte[] bytes = "{\"id\":\"batch-1\",\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		com.sure.ai.model.BatchRequest req = com.sure.ai.model.BatchRequest.builder()
			.model("m").inputFileId("file-1").metadata("key1", "val1").build();
		holder("BATCH").set(new ZhipuBatchClient(
			AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl).build()));
		try {
			assertNotNull(ZhipuUtil.batch(req));
		} finally {
			holder("BATCH").reset();
		}
	}

	/** ZhipuRealtimeClient：buildUri 尾部斜杠覆盖 L97。 */
	@Test
	public void testRealtimeBuildUriTrailingSlash() throws Exception {
		AiConfig cfg = AiConfig.builder().apiKey("id.secret").baseUrl("https://example.com/").build();
		ZhipuRealtimeClient client = new ZhipuRealtimeClient(cfg, "rt-model",
			new RealtimeEventListener() {
				@Override public void onTranscript(String text) { }
				@Override public void onAudio(byte[] audio) { }
				@Override public void onError(String error) { }
				@Override public void onClose() { }
				@Override public void onEvent(String type, String rawJson) { }
			});
		java.lang.reflect.Method m = ZhipuRealtimeClient.class.getDeclaredMethod("buildUri");
		m.setAccessible(true);
		java.net.URI uri = (java.net.URI) m.invoke(client);
		assertNotNull(uri);
		client.close();
	}

	/** ZhipuRealtimeClient：handleMessage error 字符串覆盖 L150, L153。 */
	@Test
	public void testRealtimeHandleMessageError() throws Exception {
		java.util.List<String> errors = new java.util.ArrayList<>();
		ZhipuRealtimeClient client = new ZhipuRealtimeClient(
			AiConfig.builder().apiKey("id.secret").baseUrl("https://example.com").build(), "rt-model",
			new RealtimeEventListener() {
				@Override public void onTranscript(String text) { }
				@Override public void onAudio(byte[] audio) { }
				@Override public void onError(String error) { errors.add(error); }
				@Override public void onClose() { }
				@Override public void onEvent(String type, String rawJson) { }
			});
		java.lang.reflect.Method m = ZhipuRealtimeClient.class.getDeclaredMethod("handleMessage", String.class);
		m.setAccessible(true);
		m.invoke(client, "{\"type\":\"error\",\"error\":\"some error string\"}");
		m.invoke(client, "{\"type\":\"error\",\"message\":\"msg from message field\"}");
		assertTrue(errors.size() >= 1);
		client.close();
	}

	/** realtimeClient lambda 覆盖 L353-354。 */
	@Test
	public void testRealtimeClientLambda() throws Exception {
		com.sure.ai.zhipu.EnvVars env = com.sure.ai.zhipu.EnvVars.begin();
		try {
			env.set(ZhipuUtil.ENV_API_KEY, "rt-id.rt-secret");
			holder("REALTIME").reset();
			ZhipuRealtimeClient client = ZhipuUtil.realtimeClient("rt-model",
				new RealtimeEventListener() {
					@Override public void onTranscript(String text) { }
					@Override public void onAudio(byte[] audio) { }
					@Override public void onError(String error) { }
					@Override public void onClose() { }
					@Override public void onEvent(String type, String rawJson) { }
				});
			assertNotNull(client);
		} finally {
			env.restore();
			ZhipuUtil.resetRealtimeClient();
		}
	}

	/** ZhipuUtil 私有构造器。 */
	@Test
	public void testUtilPrivateCtor() throws Exception {
		Constructor<ZhipuUtil> c = ZhipuUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** ZhipuModels 私有构造器。 */
	@Test
	public void testModelsPrivateCtor() throws Exception {
		Constructor<ZhipuModels> c = ZhipuModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** ZhipuJwtGenerator：生成 token 覆盖 L50-51。 */
	@Test
	public void testJwtGenerator() {
		String token = ZhipuJwtGenerator.generate("test-id.test-secret");
		assertNotNull(token);
		assertTrue(token.split("\\.").length == 3);
	}

	/** ZhipuJwtGenerator 私有构造器。 */
	@Test
	public void testJwtPrivateCtor() throws Exception {
		Constructor<ZhipuJwtGenerator> c = ZhipuJwtGenerator.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** ZhipuVideoClient：submit 无 id → AiException。 */
	@Test
	public void testVideoNoTaskId() {
		this.server.createContext("/", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
		ZhipuVideoClient client = new ZhipuVideoClient(
			AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl).build());
		assertThrows(AiException.class, () -> client.generate(VideoRequest.of("m", "cat")));
		client.close();
	}

	/** ZhipuVideoClient：sleepQuietly 中断分支。 */
	@Test
	public void testVideoSleepInterrupted() throws Exception {
		java.lang.reflect.Method m = ZhipuVideoClient.class
			.getDeclaredMethod("sleepQuietly");
		m.setAccessible(true);
		Thread.currentThread().interrupt();
		try {
			assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null));
		} finally {
			Thread.interrupted();
		}
	}

	/** ZhipuBatchClient：createBatch 无 inputFileId → AiException。 */
	@Test
	public void testBatchNoInputFileId() {
		ZhipuBatchClient client = new ZhipuBatchClient(
			AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl).build());
		com.sure.ai.model.BatchRequest req = com.sure.ai.model.BatchRequest.builder()
			.model("m").addRequest(com.sure.ai.model.ChatRequest.builder()
				.model("m").messages(com.sure.ai.model.ChatMessage.user("hi")).build())
			.build();
		assertThrows(AiException.class, () -> client.createBatch(req));
		client.close();
	}

	/** ZhipuBatchClient：sleepQuietly 中断分支。 */
	@Test
	public void testBatchSleepInterrupted() throws Exception {
		java.lang.reflect.Method m = ZhipuBatchClient.class
			.getDeclaredMethod("sleepQuietly");
		m.setAccessible(true);
		Thread.currentThread().interrupt();
		try {
			assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null));
		} finally {
			Thread.interrupted();
		}
	}

	/** ZhipuRealtimeClient：公共构造器 + buildUri。 */
	@Test
	public void testRealtimeClientCtor() {
		AiConfig cfg = AiConfig.builder().apiKey("id.secret").baseUrl("https://example.com/").build();
		ZhipuRealtimeClient client = new ZhipuRealtimeClient(cfg, "rt-model",
			new RealtimeEventListener() {
				@Override public void onTranscript(String text) { }
				@Override public void onAudio(byte[] audio) { }
				@Override public void onError(String error) { }
				@Override public void onClose() { }
				@Override public void onEvent(String type, String rawJson) { }
			});
		assertEquals("zhipu-realtime", client.name());
		client.close();
	}
}
