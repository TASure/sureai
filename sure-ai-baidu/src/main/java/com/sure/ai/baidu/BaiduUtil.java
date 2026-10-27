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

import java.util.function.Consumer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiException;
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

/**
 * 百度千帆（文心 ERNIE）静态入口。
 *
 * <p>双检锁懒加载单例；首次使用前可通过 {@link #init(String, String)} / {@link #init(AiConfig)}
 * 注入配置，否则从环境变量读取：</p>
 * <ul>
 *   <li>{@code SURE_AI_BAIDU_API_KEY}：千帆 API Key（必填）</li>
 *   <li>{@code SURE_AI_BAIDU_SECRET_KEY}：千帆 Secret Key（必填）</li>
 *   <li>{@code SURE_AI_BAIDU_BASE_URL}：可选，缺省 {@code https://aip.baidubce.com}</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class BaiduUtil {

	/** 环境变量：API Key。 */
	public static final String ENV_API_KEY = "SURE_AI_BAIDU_API_KEY";

	/** 环境变量：Secret Key。 */
	public static final String ENV_SECRET_KEY = "SURE_AI_BAIDU_SECRET_KEY";

	/** 环境变量：baseUrl。 */
	public static final String ENV_BASE_URL = "SURE_AI_BAIDU_BASE_URL";

	/** 主客户端容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<BaiduClient> MAIN =
		new SingletonHolder<>(BaiduUtil::buildFromEnv);

	/** 图像生成客户端容器。 */
	private static final SingletonHolder<BaiduImageClient> IMAGE =
		new SingletonHolder<>(BaiduUtil::buildImageClientFromEnv);

	/** 工具类禁止实例化。 */
	private BaiduUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用 API Key 与 Secret Key 初始化。
	 *
	 * @param apiKey    千帆 API Key
	 * @param secretKey 千帆 Secret Key
	 */
	public static void init(String apiKey, String secretKey) {
		AiConfig cfg = AiConfig.builder()
			.apiKey(apiKey)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, secretKey)
			.build();
		MAIN.set(new BaiduClient(cfg));
	}

	/**
	 * 用完整配置初始化（secretKey 需通过 extraHeader("secretKey", ...) 传入）。
	 *
	 * @param config 配置
	 */
	public static void init(AiConfig config) {
		MAIN.set(new BaiduClient(config));
	}

	/**
	 * 获取单例；未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static BaiduClient client() {
		return MAIN.get();
	}

	/** 从环境变量构建客户端。 */
	private static BaiduClient buildFromEnv() {
		String key = System.getenv(ENV_API_KEY);
		String secret = System.getenv(ENV_SECRET_KEY);
		if (key == null || key.isBlank()) {
			throw new AiException("env " + ENV_API_KEY + " is not set");
		}
		if (secret == null || secret.isBlank()) {
			throw new AiException("env " + ENV_SECRET_KEY + " is not set");
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(key)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, secret);
		String base = System.getenv(ENV_BASE_URL);
		if (base != null && !base.isBlank()) {
			b.baseUrl(base);
		}
		return new BaiduClient(b.build());
	}

	/**
	 * 便捷同步对话。
	 *
	 * @param model  模型 ID（URL 路径参数）
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
	 * 获取文心一格图像生成单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 图像客户端
	 * @throws AiException 环境变量缺失时抛出
	 */
	public static BaiduImageClient imageClient() {
		return IMAGE.get();
	}

	/** 从环境变量构建图像客户端：apiKey + secretKey 放入 extraHeaders。 */
	private static BaiduImageClient buildImageClientFromEnv() {
		String key = System.getenv(ENV_API_KEY);
		String secret = System.getenv(ENV_SECRET_KEY);
		if (key == null || key.isBlank()) {
			throw new AiException("env " + ENV_API_KEY + " is not set");
		}
		if (secret == null || secret.isBlank()) {
			throw new AiException("env " + ENV_SECRET_KEY + " is not set");
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(key)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, secret);
		String base = System.getenv(ENV_BASE_URL);
		if (base != null && !base.isBlank()) {
			b.baseUrl(base);
		}
		return new BaiduImageClient(b.build());
	}

	/**
	 * 便捷文生图。
	 *
	 * @param model  模型 ID（如 {@link BaiduModels#ERNIE_VILG_V2}）
	 * @param prompt 提示词
	 * @return 图像响应
	 */
	public static ImageResponse image(String model, String prompt) {
		return imageClient().generate(model, prompt);
	}

	/**
	 * 文生图。
	 *
	 * @param request 图像请求
	 * @return 图像响应
	 */
	public static ImageResponse image(ImageRequest request) {
		return imageClient().generate(request);
	}

	/**
	 * 便捷语音合成：模型/文本/音色。
	 *
	 * <p>百度短文本语音合成无 model 概念，{@code model} 参数可传入任意占位值；
	 * {@code voice} 映射到发音人 per（如 {@link BaiduModels#TTS_PER_XIAOMEI}）。</p>
	 *
	 * @param model 模型占位（百度忽略）
	 * @param text  待合成文本
	 * @param voice 发音人 per
	 * @return 语音合成响应（二进制音频）
	 */
	public static TtsResponse tts(String model, String text, String voice) {
		return client().synthesize(TtsRequest.of(model, text, voice));
	}

	/**
	 * 语音合成。
	 *
	 * @param request TTS 请求
	 * @return 语音合成响应（二进制音频）
	 */
	public static TtsResponse tts(TtsRequest request) {
		return client().synthesize(request);
	}

	/**
	 * 便捷语音识别：模型 + 音频数据。
	 *
	 * @param model     模型占位（百度忽略）
	 * @param audioData 音频二进制（pcm/wav/amr/m4a，≤60s，单声道 16kHz）
	 * @return 语音识别响应（转写文本）
	 */
	public static SttResponse stt(String model, byte[] audioData) {
		return client().transcribe(SttRequest.of(model, audioData));
	}

	/**
	 * 语音识别。
	 *
	 * @param request STT 请求
	 * @return 语音识别响应（转写文本）
	 */
	public static SttResponse stt(SttRequest request) {
		return client().transcribe(request);
	}

	/**
	 * 重置图像单例客户端（测试清理用）。
	 */
	public static void resetImageClient() {
		IMAGE.reset();
	}
}
