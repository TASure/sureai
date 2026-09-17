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

package com.sure.ai.openai;

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * OpenAI 平台静态入口工具类。
 *
 * <p>双检锁懒加载单例：调用 {@link #init(String)} 或 {@link #init(AiConfig)} 显式初始化；
 * 未初始化时从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_OPENAI_API_KEY}（必填，缺失抛 {@link AiException}）</li>
 *   <li>{@code SURE_AI_OPENAI_BASE_URL}（可选，覆盖默认 baseUrl）</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class OpenAiUtil {

	/** 环境变量名：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_OPENAI_API_KEY";

	/** 环境变量名：baseUrl 覆盖。 */
	public static final String ENV_BASE_URL = "SURE_AI_OPENAI_BASE_URL";

	private static volatile OpenAiClient client;

	/** 初始化锁对象（避免静态 synchronized 暴露 class 锁）。 */
	private static final Object LOCK = new Object();

	/** 批处理客户端单例。 */
	private static volatile OpenAiBatchClient batchClient;

	/** 批处理客户端初始化锁。 */
	private static final Object BATCH_LOCK = new Object();

	private OpenAiUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 显式初始化。
	 *
	 * @param apiKey API Key
	 */
	public static void init(String apiKey) {
		synchronized (LOCK) {
			client = new OpenAiClient(AiConfig.of(apiKey));
		}
	}

	/**
	 * 用完整配置显式初始化。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		synchronized (LOCK) {
			client = new OpenAiClient(config);
		}
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_OPENAI_API_KEY}
	 */
	public static OpenAiClient client() {
		OpenAiClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					c = loadFromEnv();
					client = c;
				}
			}
		}
		return c;
	}

	/** 从环境变量构造客户端。 */
	private static OpenAiClient loadFromEnv() {
		return new OpenAiClient(buildConfigFromEnv());
	}

	/**
	 * 从环境变量读取 API Key（必填）与 baseUrl（可选）构造配置。
	 *
	 * @return 配置
	 * @throws AiException 未设置 API Key 环境变量
	 */
	static AiConfig buildConfigFromEnv() {
		String apiKey = System.getenv(ENV_API_KEY);
		if (apiKey == null || apiKey.isBlank()) {
			throw new AiException("未设置环境变量 " + ENV_API_KEY
				+ "，请先调用 OpenAiUtil.init(apiKey) 或配置该环境变量");
		}
		return buildConfig(apiKey, System.getenv(ENV_BASE_URL));
	}

	/**
	 * 用 API Key 与可选 baseUrl 构造配置（baseUrl 为空白时忽略）。
	 *
	 * @param apiKey  API Key
	 * @param baseUrl baseUrl，可空
	 * @return 配置
	 */
	static AiConfig buildConfig(String apiKey, String baseUrl) {
		AiConfig.Builder b = AiConfig.builder().apiKey(apiKey);
		if (baseUrl != null && !baseUrl.isBlank()) {
			b.baseUrl(baseUrl);
		}
		return b.build();
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  模型 ID
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
	 * 便捷向量：单条文本。
	 *
	 * @param model 模型 ID
	 * @param text  文本
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(String model, String text) {
		return client().embed(model, text);
	}

	/**
	 * 向量。
	 *
	 * @param request 向量请求
	 * @return 向量响应
	 */
	public static EmbeddingResponse embed(EmbeddingRequest request) {
		return client().embed(request);
	}

	/**
	 * 便捷图像生成：仅模型与提示词。
	 *
	 * @param model  模型 ID（如 {@link OpenAiModels#DALL_E_3}）
	 * @param prompt 提示词
	 * @return 图像响应
	 */
	public static ImageResponse image(String model, String prompt) {
		return client().generate(model, prompt);
	}

	/**
	 * 图像生成。
	 *
	 * @param request 图像请求
	 * @return 图像响应
	 */
	public static ImageResponse image(ImageRequest request) {
		return client().generate(request);
	}

	/**
	 * 视频生成（内部异步轮询，同步返回）。
	 *
	 * @param model  模型 ID（如 {@link OpenAiModels#SORA_2}）
	 * @param prompt 提示词
	 * @return 视频响应
	 */
	public static VideoResponse video(String model, String prompt) {
		return client().generate(VideoRequest.of(model, prompt));
	}

	/**
	 * 视频生成。
	 *
	 * @param request 视频请求
	 * @return 视频响应
	 */
	public static VideoResponse video(VideoRequest request) {
		return client().generate(request);
	}

	/**
	 * 语音合成（TTS）。
	 *
	 * @param model 模型 ID（如 {@link OpenAiModels#TTS_1}）
	 * @param text  待合成文本
	 * @param voice 音色（如 alloy/nova）
	 * @return TTS 响应（二进制音频）
	 */
	public static TtsResponse tts(String model, String text, String voice) {
		return client().synthesize(model, text, voice);
	}

	/**
	 * 语音合成。
	 *
	 * @param request TTS 请求
	 * @return TTS 响应
	 */
	public static TtsResponse tts(TtsRequest request) {
		return client().synthesize(request);
	}

	/**
	 * 语音识别（STT/转录）。
	 *
	 * @param model     模型 ID（如 {@link OpenAiModels#WHISPER_1}）
	 * @param audioData 音频二进制数据
	 * @return STT 响应（含转写文本）
	 */
	public static SttResponse stt(String model, byte[] audioData) {
		return client().transcribe(model, audioData);
	}

	/**
	 * 语音识别。
	 *
	 * @param request STT 请求
	 * @return STT 响应
	 */
	public static SttResponse stt(SttRequest request) {
		return client().transcribe(request);
	}

	// ==================== 批处理（Batches） ====================

	/**
	 * 获取批处理单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 批处理客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_OPENAI_API_KEY}
	 */
	public static OpenAiBatchClient batchClient() {
		OpenAiBatchClient c = batchClient;
		if (c == null) {
			synchronized (BATCH_LOCK) {
				c = batchClient;
				if (c == null) {
					c = new OpenAiBatchClient(buildConfigFromEnv());
					batchClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 提交批处理任务。
	 *
	 * @param request 批处理请求（须先上传 JSONL 取得 input_file_id）
	 * @return 初始任务响应
	 */
	public static BatchResponse batch(BatchRequest request) {
		return batchClient().createBatch(request);
	}

	/**
	 * 查询批处理任务状态。
	 *
	 * @param batchId 任务 ID
	 * @return 最新任务响应
	 */
	public static BatchResponse getBatch(String batchId) {
		return batchClient().getBatch(batchId);
	}

	/**
	 * 重置批处理单例客户端（测试清理用）。
	 */
	public static void resetBatchClient() {
		synchronized (BATCH_LOCK) {
			batchClient = null;
		}
	}
}
