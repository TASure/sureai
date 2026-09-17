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

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.realtime.RealtimeEventListener;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * 火山方舟（豆包 / Doubao）静态入口。
 *
 * <p>双检锁懒加载单例；首次使用前可通过 {@link #init(String)} / {@link #init(AiConfig)} 注入配置，
 * 否则从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_DOUBAO_API_KEY}：必填</li>
 *   <li>{@code SURE_AI_DOUBAO_BASE_URL}：可选，缺省 {@code https://ark.cn-beijing.volces.com/api/v3}</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class DoubaoUtil {

	/** 环境变量：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_DOUBAO_API_KEY";

	/** 环境变量：baseUrl。 */
	public static final String ENV_BASE_URL = "SURE_AI_DOUBAO_BASE_URL";

	/** 全局单例。 */
	private static volatile DoubaoClient client;

	/** 初始化锁。 */
	private static final Object LOCK = new Object();

	/** 视频生成客户端单例（方舟原生端点）。 */
	private static volatile DoubaoVideoClient videoClient;

	/** 视频客户端初始化锁。 */
	private static final Object VIDEO_LOCK = new Object();

	/** TTS 客户端单例（openspeech 端点，鉴权头不同）。 */
	private static volatile DoubaoTtsClient ttsClient;

	/** TTS 客户端初始化锁。 */
	private static final Object TTS_LOCK = new Object();

	/** STT 客户端单例（openspeech 端点，异步 submit+query）。 */
	private static volatile DoubaoSttClient sttClient;

	/** STT 客户端初始化锁。 */
	private static final Object STT_LOCK = new Object();

	/** Realtime 客户端单例（需事件监听，不提供静态便捷方法）。 */
	private static volatile DoubaoRealtimeClient realtimeClient;

	/** Realtime 客户端初始化锁。 */
	private static final Object REALTIME_LOCK = new Object();

	/** 工具类禁止实例化。 */
	private DoubaoUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 apiKey 初始化。
	 *
	 * @param apiKey 方舟 API Key
	 */
	public static void init(String apiKey) {
		synchronized (LOCK) {
			client = new DoubaoClient(AiConfig.of(apiKey));
		}
	}

	/**
	 * 用完整配置初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		synchronized (LOCK) {
			client = new DoubaoClient(config);
		}
	}

	/**
	 * 获取单例；未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoClient client() {
		DoubaoClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					c = buildFromEnv();
					client = c;
				}
			}
		}
		return c;
	}

	/** 从环境变量构建客户端。 */
	private static DoubaoClient buildFromEnv() {
		String key = System.getenv(ENV_API_KEY);
		if (key == null || key.isBlank()) {
			throw new AiException("env " + ENV_API_KEY + " is not set");
		}
		AiConfig.Builder b = AiConfig.builder().apiKey(key);
		String base = System.getenv(ENV_BASE_URL);
		if (base != null && !base.isBlank()) {
			b.baseUrl(base);
		}
		return new DoubaoClient(b.build());
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  推理接入点 ID（ep-xxx）或模型 ID
	 * @param prompt 用户输入
	 * @return 响应
	 */
	public static ChatResponse chat(String model, String prompt) {
		return client().chat(model, prompt);
	}

	/**
	 * 同步对话。
	 *
	 * @param request 请求
	 * @return 响应
	 */
	public static ChatResponse chat(ChatRequest request) {
		return client().chat(request);
	}

	/**
	 * 流式对话。
	 *
	 * @param request  请求
	 * @param consumer 分片消费者
	 */
	public static void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		client().chatStream(request, consumer);
	}

	/**
	 * 文本向量。
	 *
	 * @param request 向量请求
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(EmbeddingRequest request) {
		return client().embed(request);
	}

	/**
	 * 获取 Seedance 视频生成单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 视频客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoVideoClient videoClient() {
		DoubaoVideoClient c = videoClient;
		if (c == null) {
			synchronized (VIDEO_LOCK) {
				c = videoClient;
				if (c == null) {
					c = buildVideoClientFromEnv();
					videoClient = c;
				}
			}
		}
		return c;
	}

	/** 从环境变量构建视频客户端。 */
	private static DoubaoVideoClient buildVideoClientFromEnv() {
		return new DoubaoVideoClient(baseConfigFromEnv());
	}

	/**
	 * 便捷视频生成：仅模型与提示词。
	 *
	 * @param model  模型 ID（如 {@link DoubaoModels#SEEDANCE_2_5}）
	 * @param prompt 提示词
	 * @return 视频响应
	 */
	public static VideoResponse video(String model, String prompt) {
		return videoClient().generate(VideoRequest.of(model, prompt));
	}

	/**
	 * 视频生成。
	 *
	 * @param request 视频请求
	 * @return 视频响应
	 */
	public static VideoResponse video(VideoRequest request) {
		return videoClient().generate(request);
	}

	/**
	 * 重置视频单例客户端（测试清理用）。
	 */
	public static void resetVideoClient() {
		synchronized (VIDEO_LOCK) {
			videoClient = null;
		}
	}

	/**
	 * 获取豆包 TTS 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return TTS 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoTtsClient ttsClient() {
		DoubaoTtsClient c = ttsClient;
		if (c == null) {
			synchronized (TTS_LOCK) {
				c = ttsClient;
				if (c == null) {
					c = new DoubaoTtsClient(baseConfigFromEnv());
					ttsClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷语音合成：模型/文本/音色。
	 *
	 * @param model 模型 ID（如 {@link DoubaoModels#SEED_TTS_2_0}，鉴权资源 ID 固定）
	 * @param text  待合成文本
	 * @param voice 音色 ID（如 {@link DoubaoModels#DOUBAO_TTS_SPEAKER_DEFAULT}）
	 * @return 语音响应
	 */
	public static TtsResponse tts(String model, String text, String voice) {
		return ttsClient().synthesize(TtsRequest.of(model, text, voice));
	}

	/**
	 * 语音合成。
	 *
	 * @param request TTS 请求
	 * @return 语音响应
	 */
	public static TtsResponse tts(TtsRequest request) {
		return ttsClient().synthesize(request);
	}

	/**
	 * 重置 TTS 单例客户端（测试清理用）。
	 */
	public static void resetTtsClient() {
		synchronized (TTS_LOCK) {
			ttsClient = null;
		}
	}

	/**
	 * 获取豆包 STT 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return STT 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoSttClient sttClient() {
		DoubaoSttClient c = sttClient;
		if (c == null) {
			synchronized (STT_LOCK) {
				c = sttClient;
				if (c == null) {
					c = new DoubaoSttClient(baseConfigFromEnv());
					sttClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷语音识别：模型 + 音频数据。
	 *
	 * @param model     模型 ID（鉴权资源 ID 固定为 volc.bigasr.auc）
	 * @param audioData 音频二进制
	 * @return 识别响应
	 */
	public static SttResponse stt(String model, byte[] audioData) {
		return sttClient().transcribe(SttRequest.of(model, audioData));
	}

	/**
	 * 语音识别。
	 *
	 * @param request STT 请求
	 * @return 识别响应
	 */
	public static SttResponse stt(SttRequest request) {
		return sttClient().transcribe(request);
	}

	/**
	 * 重置 STT 单例客户端（测试清理用）。
	 */
	public static void resetSttClient() {
		synchronized (STT_LOCK) {
			sttClient = null;
		}
	}

	// ==================== Realtime（全双工语音对话） ====================

	/**
	 * 获取 Realtime 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * <p>Realtime 需要事件监听器，不提供静态便捷方法；单例首次以
	 * {@code (model, listener)} 构造，之后重复调用返回同一实例。</p>
	 *
	 * @param model        实时模型 ID
	 * @param eventListener 事件监听器
	 * @return Realtime 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoRealtimeClient realtimeClient(String model,
			RealtimeEventListener eventListener) {
		DoubaoRealtimeClient c = realtimeClient;
		if (c == null) {
			synchronized (REALTIME_LOCK) {
				c = realtimeClient;
				if (c == null) {
					c = new DoubaoRealtimeClient(baseConfigFromEnv(), model, eventListener);
					realtimeClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 重置 Realtime 单例客户端（测试清理用）。
	 */
	public static void resetRealtimeClient() {
		synchronized (REALTIME_LOCK) {
			realtimeClient = null;
		}
	}

	/** 从环境变量构建基础配置（视频/TTS/STT/Realtime 客户端共用 apiKey）。 */
	private static AiConfig baseConfigFromEnv() {
		String key = System.getenv(ENV_API_KEY);
		if (key == null || key.isBlank()) {
			throw new AiException("env " + ENV_API_KEY + " is not set");
		}
		return AiConfig.of(key);
	}
}
