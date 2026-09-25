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
import com.sure.ai.client.SingletonHolder;
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

	/** 主客户端容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<DoubaoClient> HOLDER =
		new SingletonHolder<>(DoubaoUtil::buildFromEnv);

	/** 视频生成客户端容器（方舟原生端点）。 */
	private static final SingletonHolder<DoubaoVideoClient> VIDEO =
		new SingletonHolder<>(DoubaoUtil::buildVideoClientFromEnv);

	/** TTS 客户端容器（openspeech 端点，鉴权头不同）。 */
	private static final SingletonHolder<DoubaoTtsClient> TTS =
		new SingletonHolder<>(() -> new DoubaoTtsClient(baseConfigFromEnv()));

	/** STT 客户端容器（openspeech 端点，异步 submit+query）。 */
	private static final SingletonHolder<DoubaoSttClient> STT =
		new SingletonHolder<>(() -> new DoubaoSttClient(baseConfigFromEnv()));

	/** Realtime 客户端容器（构造参数依赖调用参数，用 getOrCreate 懒加载）。 */
	private static final SingletonHolder<DoubaoRealtimeClient> REALTIME =
		new SingletonHolder<>(null);

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
		HOLDER.set(new DoubaoClient(AiConfig.of(apiKey)));
	}

	/**
	 * 用完整配置初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		HOLDER.set(new DoubaoClient(config));
	}

	/**
	 * 获取单例；未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoClient client() {
		return HOLDER.get();
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
		return VIDEO.get();
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
		VIDEO.reset();
	}

	/**
	 * 获取豆包 TTS 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return TTS 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoTtsClient ttsClient() {
		return TTS.get();
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
		TTS.reset();
	}

	/**
	 * 获取豆包 STT 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return STT 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static DoubaoSttClient sttClient() {
		return STT.get();
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
		STT.reset();
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
		return REALTIME.getOrCreate(() ->
			new DoubaoRealtimeClient(baseConfigFromEnv(), model, eventListener));
	}

	/**
	 * 重置 Realtime 单例客户端（测试清理用）。
	 */
	public static void resetRealtimeClient() {
		REALTIME.reset();
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
