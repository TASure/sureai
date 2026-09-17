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
 * Azure OpenAI 平台静态入口工具类。
 *
 * <p>双检锁懒加载单例：调用 {@link #init(String)} 或 {@link #init(AiConfig)} 显式初始化；
 * 未初始化时从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_AZURE_API_KEY}（必填，缺失抛 {@link AiException}）</li>
 *   <li>{@code SURE_AI_AZURE_BASE_URL}（可选，覆盖默认 baseUrl）</li>
 *   <li>{@code SURE_AI_AZURE_RESOURCE}（可选，baseUrl 缺省时推导默认地址）</li>
 * </ul>
 *
 * <p>部署名 deployment 与 api-version 需在传入的 {@link AiConfig#extraHeaders()} 中以
 * {@code "deployment"}、{@code "api-version"} 提供。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class AzureUtil {

	/** 环境变量名：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_AZURE_API_KEY";

	/** 环境变量名：baseUrl 覆盖。 */
	public static final String ENV_BASE_URL = "SURE_AI_AZURE_BASE_URL";

	/** 环境变量名：resource 名。 */
	public static final String ENV_RESOURCE = "SURE_AI_AZURE_RESOURCE";

	/** 环境变量名：Speech 资源密钥（TTS/STT），缺省回退 ENV_API_KEY。 */
	public static final String ENV_SPEECH_KEY = "SURE_AI_AZURE_SPEECH_KEY";

	/** 环境变量名：Speech 区域（TTS 端点 {region}.tts.speech.microsoft.com）。 */
	public static final String ENV_SPEECH_REGION = "SURE_AI_AZURE_SPEECH_REGION";

	private static volatile AzureClient client;

	/** 初始化锁对象。 */
	private static final Object LOCK = new Object();

	/** 视频生成客户端单例。 */
	private static volatile AzureVideoClient videoClient;

	/** 视频客户端初始化锁。 */
	private static final Object VIDEO_LOCK = new Object();

	/** TTS 客户端单例。 */
	private static volatile AzureTtsClient ttsClient;

	/** TTS 客户端初始化锁。 */
	private static final Object TTS_LOCK = new Object();

	/** STT 客户端单例。 */
	private static volatile AzureSttClient sttClient;

	/** STT 客户端初始化锁。 */
	private static final Object STT_LOCK = new Object();

	/** 批处理客户端单例。 */
	private static volatile AzureBatchClient batchClient;

	/** 批处理客户端初始化锁。 */
	private static final Object BATCH_LOCK = new Object();

	private AzureUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 显式初始化。
	 *
	 * @param apiKey API Key
	 */
	public static void init(String apiKey) {
		synchronized (LOCK) {
			client = new AzureClient(AiConfig.of(apiKey));
		}
	}

	/**
	 * 用完整配置显式初始化。
	 *
	 * @param config 配置（deployment/api-version 经 extraHeaders 传入）
	 */
	public static void init(AiConfig config) {
		synchronized (LOCK) {
			client = new AzureClient(config);
		}
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_AZURE_API_KEY}
	 */
	public static AzureClient client() {
		AzureClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					c = new AzureClient(buildConfigFromEnv());
					client = c;
				}
			}
		}
		return c;
	}

	/** 从环境变量构造配置。 */
	static AiConfig buildConfigFromEnv() {
		String apiKey = System.getenv(ENV_API_KEY);
		if (apiKey == null || apiKey.isBlank()) {
			throw new AiException("未设置环境变量 " + ENV_API_KEY
				+ "，请先调用 AzureUtil.init(apiKey) 或配置该环境变量");
		}
		return buildConfig(apiKey, System.getenv(ENV_BASE_URL), System.getenv(ENV_RESOURCE));
	}

	/**
	 * 用 API Key、可选 baseUrl 与可选 resource 构造配置。
	 *
	 * @param apiKey   API Key
	 * @param baseUrl  baseUrl（优先），可空
	 * @param resource resource 名（baseUrl 为空时使用），可空
	 * @return 配置
	 */
	static AiConfig buildConfig(String apiKey, String baseUrl, String resource) {
		AiConfig.Builder b = AiConfig.builder().apiKey(apiKey);
		if (baseUrl != null && !baseUrl.isBlank()) {
			b.baseUrl(baseUrl);
		} else if (resource != null && !resource.isBlank()) {
			b.extraHeader("resource", resource);
		}
		return b.build();
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  部署名（deployment）
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
	 * @param model 部署名
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
	 * 便捷图像生成：仅部署名与提示词。
	 *
	 * @param model  部署名（deployment）
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

	// ==================== 视频生成（Sora 2） ====================

	/**
	 * 获取视频生成单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 视频客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_AZURE_API_KEY}
	 */
	public static AzureVideoClient videoClient() {
		AzureVideoClient c = videoClient;
		if (c == null) {
			synchronized (VIDEO_LOCK) {
				c = videoClient;
				if (c == null) {
					c = new AzureVideoClient(buildConfigFromEnv());
					videoClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷视频生成：模型 + 提示词。
	 *
	 * @param model  模型（如 {@link AzureModels#SORA_2}）
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

	/** 重置视频单例客户端（测试清理用）。 */
	public static void resetVideoClient() {
		synchronized (VIDEO_LOCK) {
			videoClient = null;
		}
	}

	// ==================== TTS ====================

	/**
	 * 获取 TTS 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return TTS 客户端
	 * @throws AiException 缺少 Speech 密钥或区域时抛出
	 */
	public static AzureTtsClient ttsClient() {
		AzureTtsClient c = ttsClient;
		if (c == null) {
			synchronized (TTS_LOCK) {
				c = ttsClient;
				if (c == null) {
					c = new AzureTtsClient(buildSpeechConfig(false));
					ttsClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷语音合成：模型/文本/音色。
	 *
	 * @param model 模型占位（Azure TTS 按音色合成，忽略）
	 * @param text  待合成文本
	 * @param voice 音色名（如 {@link AzureModels#TTS_VOICE_XIAOXIAO}）
	 * @return 语音合成响应
	 */
	public static TtsResponse tts(String model, String text, String voice) {
		return ttsClient().synthesize(TtsRequest.of(model, text, voice));
	}

	/**
	 * 语音合成。
	 *
	 * @param request TTS 请求
	 * @return 语音合成响应
	 */
	public static TtsResponse tts(TtsRequest request) {
		return ttsClient().synthesize(request);
	}

	/** 重置 TTS 单例客户端（测试清理用）。 */
	public static void resetTtsClient() {
		synchronized (TTS_LOCK) {
			ttsClient = null;
		}
	}

	// ==================== STT ====================

	/**
	 * 获取 STT 单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return STT 客户端
	 * @throws AiException 缺少 Speech 密钥或区域时抛出
	 */
	public static AzureSttClient sttClient() {
		AzureSttClient c = sttClient;
		if (c == null) {
			synchronized (STT_LOCK) {
				c = sttClient;
				if (c == null) {
					c = new AzureSttClient(buildSpeechConfig(true));
					sttClient = c;
				}
			}
		}
		return c;
	}

	/**
	 * 便捷语音识别：模型 + 音频数据。
	 *
	 * @param model     模型占位（Azure STT 忽略）
	 * @param audioData 音频二进制
	 * @return 语音识别响应
	 */
	public static SttResponse stt(String model, byte[] audioData) {
		return sttClient().transcribe(SttRequest.of(model, audioData));
	}

	/**
	 * 语音识别。
	 *
	 * @param request STT 请求
	 * @return 语音识别响应
	 */
	public static SttResponse stt(SttRequest request) {
		return sttClient().transcribe(request);
	}

	/** 重置 STT 单例客户端（测试清理用）。 */
	public static void resetSttClient() {
		synchronized (STT_LOCK) {
			sttClient = null;
		}
	}

	// ==================== 批处理（Batches） ====================

	/**
	 * 获取批处理单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 批处理客户端
	 * @throws AiException 未初始化且未设置 {@code SURE_AI_AZURE_API_KEY}
	 */
	public static AzureBatchClient batchClient() {
		AzureBatchClient c = batchClient;
		if (c == null) {
			synchronized (BATCH_LOCK) {
				c = batchClient;
				if (c == null) {
					c = new AzureBatchClient(buildConfigFromEnv());
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

	/** 从环境变量构造 Speech（TTS/STT）配置。 */
	private static AiConfig buildSpeechConfig(boolean stt) {
		String key = System.getenv(ENV_SPEECH_KEY);
		if (key == null || key.isBlank()) {
			key = System.getenv(ENV_API_KEY);
		}
		if (key == null || key.isBlank()) {
			throw new AiException("未设置环境变量 " + ENV_SPEECH_KEY + " 或 " + ENV_API_KEY);
		}
		String region = System.getenv(ENV_SPEECH_REGION);
		if (region == null || region.isBlank()) {
			region = System.getenv(ENV_RESOURCE);
		}
		if (region == null || region.isBlank()) {
			throw new AiException("未设置环境变量 " + ENV_SPEECH_REGION + " 或 " + ENV_RESOURCE);
		}
		String baseUrl = stt
			? "https://" + region + ".cognitiveservices.azure.com"
			: "https://" + region + ".tts.speech.microsoft.com";
		return AiConfig.builder().apiKey(key).baseUrl(baseUrl).build();
	}
}
