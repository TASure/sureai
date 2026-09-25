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

import com.sure.ai.exception.AiException;

/**
 * AWS Bedrock 静态工厂：从环境变量读取凭证并创建 {@link BedrockClient}。
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

	private BedrockUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 从当前进程环境变量创建客户端。
	 *
	 * @return 新客户端
	 * @throws AiException 缺少必填凭证/区域时抛出
	 */
	public static BedrockClient create() {
		return create(System.getenv());
	}

	/**
	 * 从给定环境变量映射创建客户端（便于测试注入）。
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
