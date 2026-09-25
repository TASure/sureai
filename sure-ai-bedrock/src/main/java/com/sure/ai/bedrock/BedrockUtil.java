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

package com.sure.ai.bedrock;

import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * AWS Bedrock 静态工厂与一行调用入口。
 *
 * <p>环境变量解析（{@code SURE_AI_BEDROCK_*} 优先，缺失时回退 AWS 标准变量）：</p>
 * <ul>
 *   <li>{@code SURE_AI_BEDROCK_ACCESS_KEY} 优先于 {@code AWS_ACCESS_KEY_ID}（必填）；</li>
 *   <li>{@code SURE_AI_BEDROCK_SECRET_KEY} 优先于 {@code AWS_SECRET_ACCESS_KEY}（必填）；</li>
 *   <li>{@code SURE_AI_BEDROCK_SESSION_TOKEN} 优先于 {@code AWS_SESSION_TOKEN}（可选）；</li>
 *   <li>{@code SURE_AI_BEDROCK_REGION} 优先于 {@code AWS_REGION}，再兜底 {@code AWS_DEFAULT_REGION}（必填）；</li>
 *   <li>{@code SURE_AI_BEDROCK_MODEL}（可选默认模型）。</li>
 * </ul>
 *
 * <p>双检锁懒加载单例：调用 {@link #init} 显式初始化，或直接 {@link #client()}/{@link #chat} 触发
 * 从环境变量懒加载。需要挂载熔断/指标/限流等跨切面能力时，自行构建带配置的 {@link BedrockClient}
 * 后通过 {@link #init(BedrockClient)} 注入。</p>
 *
 * <p>缺凭证时抛出清晰的 {@link AiException}，便于调用方定位。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class BedrockUtil {

	/** 环境变量：Bedrock 专属 Access Key。 */
	public static final String ENV_ACCESS_KEY = "SURE_AI_BEDROCK_ACCESS_KEY";

	/** 环境变量：Bedrock 专属 Secret Key。 */
	public static final String ENV_SECRET_KEY = "SURE_AI_BEDROCK_SECRET_KEY";

	/** 环境变量：Bedrock 专属 Session Token。 */
	public static final String ENV_SESSION_TOKEN = "SURE_AI_BEDROCK_SESSION_TOKEN";

	/** 环境变量：Bedrock 专属 Region。 */
	public static final String ENV_REGION = "SURE_AI_BEDROCK_REGION";

	/** 环境变量：Bedrock 专属默认模型。 */
	public static final String ENV_MODEL = "SURE_AI_BEDROCK_MODEL";

	/** AWS 标准 Access Key 变量。 */
	public static final String AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";

	/** AWS 标准 Secret Key 变量。 */
	public static final String AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

	/** AWS 标准 Session Token 变量。 */
	public static final String AWS_SESSION_TOKEN = "AWS_SESSION_TOKEN";

	/** AWS 标准 Region 变量。 */
	public static final String AWS_REGION = "AWS_REGION";

	/** AWS 标准 Region 兜底变量。 */
	public static final String AWS_DEFAULT_REGION = "AWS_DEFAULT_REGION";

	/** 单例客户端。 */
	private static volatile BedrockClient client;

	/** 初始化锁对象。 */
	private static final Object LOCK = new Object();

	private BedrockUtil() {
		throw new AssertionError("No instances");
	}

	// ==================== 显式工厂（每次新建） ====================

	/**
	 * 从当前进程环境变量创建客户端（每次新建，不走单例）。
	 *
	 * @return 新客户端
	 * @throws AiException 缺少必填凭证/区域时抛出
	 */
	public static BedrockClient create() {
		return create(System.getenv());
	}

	/**
	 * 从给定环境变量映射创建客户端（便于测试注入，每次新建，不走单例）。
	 *
	 * @param env 环境变量映射
	 * @return 新客户端
	 * @throws AiException 缺少必填凭证/区域时抛出
	 */
	static BedrockClient create(Map<String, String> env) {
		String accessKey = firstNonBlank(env, ENV_ACCESS_KEY, AWS_ACCESS_KEY_ID);
		String secretKey = firstNonBlank(env, ENV_SECRET_KEY, AWS_SECRET_ACCESS_KEY);
		String sessionToken = firstNonBlankOrNull(env, ENV_SESSION_TOKEN, AWS_SESSION_TOKEN);
		String region = firstNonBlankOrNull(env, ENV_REGION, AWS_REGION, AWS_DEFAULT_REGION);
		String model = blankToNull(env.get(ENV_MODEL));

		if (accessKey == null) {
			throw new AiException("未设置 AWS Access Key：请配置 " + ENV_ACCESS_KEY
				+ " 或 " + AWS_ACCESS_KEY_ID);
		}
		if (secretKey == null) {
			throw new AiException("未设置 AWS Secret Key：请配置 " + ENV_SECRET_KEY
				+ " 或 " + AWS_SECRET_ACCESS_KEY);
		}
		if (region == null) {
			throw new AiException("未设置 AWS Region：请配置 " + ENV_REGION + " 或 "
				+ AWS_REGION + " 或 " + AWS_DEFAULT_REGION);
		}
		return new BedrockClient(accessKey, secretKey, sessionToken, region, model);
	}

	// ==================== 单例初始化 ====================

	/**
	 * 用凭证初始化单例（无 session token、无默认模型）。
	 *
	 * @param accessKey AWS Access Key ID
	 * @param secretKey AWS Secret Access Key
	 * @param region    AWS 区域
	 */
	public static void init(String accessKey, String secretKey, String region) {
		init(accessKey, secretKey, null, region, null);
	}

	/**
	 * 用完整凭证初始化单例。
	 *
	 * @param accessKey    AWS Access Key ID
	 * @param secretKey    AWS Secret Access Key
	 * @param sessionToken 临时会话令牌（可空）
	 * @param region       AWS 区域
	 * @param modelId      默认模型 ID（可空）
	 */
	public static void init(String accessKey, String secretKey, String sessionToken,
			String region, String modelId) {
		synchronized (LOCK) {
			client = new BedrockClient(accessKey, secretKey, sessionToken, region, modelId);
		}
	}

	/**
	 * 注入一个已构建好的客户端作为单例（用于挂载熔断/指标/限流等跨切面能力）。
	 *
	 * @param bedrockClient 预构建客户端
	 */
	public static void init(BedrockClient bedrockClient) {
		synchronized (LOCK) {
			client = bedrockClient;
		}
	}

	/**
	 * 获取单例客户端，未初始化时从环境变量懒加载。
	 *
	 * @return 客户端
	 * @throws AiException 未初始化且环境变量缺凭证时抛出
	 */
	public static BedrockClient client() {
		BedrockClient c = client;
		if (c == null) {
			synchronized (LOCK) {
				c = client;
				if (c == null) {
					c = create(System.getenv());
					client = c;
				}
			}
		}
		return c;
	}

	/** 重置单例客户端（主要用于测试与环境切换）。 */
	public static void resetClient() {
		synchronized (LOCK) {
			client = null;
		}
	}

	// ==================== 一行调用 ====================

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
	 * 便捷同步对话。
	 *
	 * @param request 请求
	 * @return 响应
	 */
	public static ChatResponse chat(ChatRequest request) {
		return client().chat(request);
	}

	/**
	 * 便捷流式对话。
	 *
	 * @param model    模型 ID
	 * @param prompt   用户输入
	 * @param consumer 分片消费者
	 */
	public static void chatStream(String model, String prompt, Consumer<ChatStreamChunk> consumer) {
		ChatRequest req = ChatRequest.builder()
			.model(model)
			.messages(java.util.List.of(ChatMessage.user(prompt)))
			.build();
		client().chatStream(req, consumer);
	}

	/**
	 * 便捷流式对话。
	 *
	 * @param request  请求
	 * @param consumer 分片消费者
	 */
	public static void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		client().chatStream(request, consumer);
	}

	// ==================== 环境变量工具 ====================

	/** 按顺序返回第一个非空非空白的值，全空返回 null。 */
	private static String firstNonBlank(Map<String, String> env, String... keys) {
		for (String key : keys) {
			String v = env.get(key);
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}

	/** {@link #firstNonBlank} 的别名（语义即可空）。 */
	private static String firstNonBlankOrNull(Map<String, String> env, String... keys) {
		return firstNonBlank(env, keys);
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}
}
