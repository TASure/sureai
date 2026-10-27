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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.SingletonHolder;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.VideoRequest;

/**
 * {@link QwenUtil} 便捷重载与环境变量懒加载测试：image/video/tts/stt 重载、HOLDER env 工厂、
 * realtimeClient 单例工厂。
 *
 * <p>零真实网络：本地 HttpServer mock，env 反射注入。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenUtilExtraTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动 mock 并按路径路由。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/compatible-mode/v1";
		this.server.createContext("/", this::route);
	}

	/** 停止 mock 并重置各单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		QwenUtil.resetImageClient();
		QwenUtil.resetVideoClient();
		QwenUtil.resetRealtimeClient();
		resetHolder();
	}

	/** 统一路由：chat / image 提交轮询 / video 提交轮询 / TTS 原生端点。 */
	private void route(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		ex.getRequestBody().readAllBytes();
		String body;
		if (path.endsWith("/chat/completions")) {
			body = "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}";
		}
		else if (path.endsWith("/image-synthesis")) {
			body = "{\"output\":{\"task_id\":\"task-img\"}}";
		}
		else if (path.endsWith("/video-synthesis")) {
			body = "{\"output\":{\"task_id\":\"task-vid\"}}";
		}
		else if (path.matches(".*/api/v1/tasks/task-(img|vid)")) {
			String kind = path.contains("img") ? "task-img" : "task-vid";
			if (kind.equals("task-img")) {
				body = "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\"https://example.com/i.png\"}]}}";
			}
			else {
				body = "{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"https://example.com/v.mp4\"}}";
			}
		}
		else if (path.endsWith("/SpeechSynthesizer")) {
			body = "{\"output\":{\"audio\":{\"url\":\"https://example.com/a.mp3\"}}}";
		}
		else {
			body = "{}";
		}
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** 经环境变量懒加载 HOLDER 主客户端并对话。 */
	@Test
	public void testLoadFromEnv() throws Exception {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "sk-env");
			env.set(QwenUtil.ENV_BASE_URL, this.baseUrl);
			assertEquals("你好", QwenUtil.chat("qwen-plus", "hi").firstText());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** image 两个重载经 mock 提交并轮询。 */
	@Test
	public void testImageOverloads() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "sk");
			env.set(QwenUtil.ENV_BASE_URL, this.baseUrl);
			QwenUtil.resetImageClient();
			assertEquals("https://example.com/i.png", QwenUtil.image("wanx", "猫").firstUrl());
			assertEquals("https://example.com/i.png", QwenUtil.image(
				ImageRequest.builder().model("wanx").prompt("猫").build()).firstUrl());
		}
		finally {
			env.restore();
			QwenUtil.resetImageClient();
		}
	}

	/** video 两个重载经 mock 提交并轮询。 */
	@Test
	public void testVideoOverloads() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "sk");
			env.set(QwenUtil.ENV_BASE_URL, this.baseUrl);
			QwenUtil.resetVideoClient();
			assertEquals("https://example.com/v.mp4", QwenUtil.video("wan2.1", "猫").firstUrl());
			assertEquals("https://example.com/v.mp4", QwenUtil.video(
				VideoRequest.builder().model("wan2.1").prompt("猫").build()).firstUrl());
		}
		finally {
			env.restore();
			QwenUtil.resetVideoClient();
		}
	}

	/** tts 两个重载：便捷方法与请求对象。 */
	@Test
	public void testTtsOverloads() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "sk");
			env.set(QwenUtil.ENV_BASE_URL, this.baseUrl);
			resetHolder();
			assertEquals("https://example.com/a.mp3", QwenUtil.tts("cosyvoice", "你好", "v").url());
			assertEquals("https://example.com/a.mp3", QwenUtil.tts(
				TtsRequest.builder().model("cosyvoice").input("你好").voice("v").build()).url());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** stt 两个重载：便捷方法与请求对象。 */
	@Test
	public void testSttOverloads() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "sk");
			env.set(QwenUtil.ENV_BASE_URL, this.baseUrl);
			resetHolder();
			assertTrue(QwenUtil.stt("asr", new byte[] { 1, 2 }).rawJson().length() > 0);
			assertNotNull(QwenUtil.stt(SttRequest.builder().model("asr").audioData(new byte[] { 1, 2 }).build()));
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** realtimeClient 工厂经环境变量构造并可重置。 */
	@Test
	public void testRealtimeFactory() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "sk");
			env.set(QwenUtil.ENV_BASE_URL, this.baseUrl);
			QwenUtil.resetRealtimeClient();
			QwenRealtimeClient client = QwenUtil.realtimeClient("omni", new NoopListener());
			assertNotNull(client);
			assertEquals("qwen-realtime", client.name());
			QwenUtil.resetRealtimeClient();
		}
		finally {
			env.restore();
			QwenUtil.resetRealtimeClient();
		}
	}

	/** 反射重置 HOLDER。 */
	private static void resetHolder() {
		try {
			Field f = QwenUtil.class.getDeclaredField("HOLDER");
			f.setAccessible(true);
			((SingletonHolder<?>) f.get(null)).reset();
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 空监听器。 */
	private static final class NoopListener implements RealtimeEventListener {
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
	}
}
