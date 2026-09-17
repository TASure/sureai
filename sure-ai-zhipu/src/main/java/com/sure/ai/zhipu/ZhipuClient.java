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

import java.net.http.HttpRequest;
import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;

/**
 * 智谱 AI 开放平台客户端。
 *
 * <p>请求体/响应体与 OpenAI 兼容（复用 {@link OpenAiCompatClient}），仅鉴权特殊：
 * 不是把 apiKey 直接当 Bearer token，而是用 {@code id.secret} 中的 secret 现场签发一个
 * HS256 JWT 作为 Bearer token，并在有效期内缓存复用。</p>
 *
 * <p>默认 baseUrl：{@code https://open.bigmodel.cn/api/paas/v4}。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class ZhipuClient extends OpenAiCompatClient {

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

	/** JWT 提前刷新的安全余量（毫秒）。 */
	private static final long REFRESH_SKEW_MS = 60_000L;

	/** 已缓存的 Bearer token（含 "Bearer " 前缀）。 */
	private volatile String cachedToken;

	/** token 过期时间戳（毫秒）。 */
	private volatile long expireAt;

	/**
	 * 构造客户端；baseUrl 为空时使用智谱默认地址。
	 *
	 * @param config 配置（apiKey 必须是 {@code id.secret} 格式）
	 */
	public ZhipuClient(AiConfig config) {
		super(withDefaultBaseUrl(config));
	}

	@Override
	public String name() {
		return "zhipu";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", resolveToken(cfg.apiKey()));
	}

	/**
	 * 解析可用的 Bearer token：缓存未过期直接复用，否则双检锁重新签发。
	 *
	 * @param apiKey 智谱 apiKey（{@code id.secret}）
	 * @return 形如 {@code Bearer &lt;jwt&gt;} 的鉴权串
	 */
	private String resolveToken(String apiKey) {
		String token = this.cachedToken;
		if (token != null && System.currentTimeMillis() < this.expireAt - REFRESH_SKEW_MS) {
			return token;
		}
		synchronized (this) {
			if (this.cachedToken == null
				|| System.currentTimeMillis() >= this.expireAt - REFRESH_SKEW_MS) {
				this.cachedToken = ZhipuJwtGenerator.generate(apiKey);
				this.expireAt = System.currentTimeMillis() + 3_600_000L;
			}
			return this.cachedToken;
		}
	}

	/** baseUrl 为空时补默认地址，其余配置原样保留。 */
	private static AiConfig withDefaultBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		AiConfig.Builder b = AiConfig.builder()
			.apiKey(config.apiKey())
			.baseUrl(DEFAULT_BASE_URL)
			.timeout(config.timeout())
			.connectTimeout(config.connectTimeout())
			.maxRetries(config.maxRetries());
		if (config.proxy() != null && !config.proxy().isBlank()) {
			b.proxy(config.proxy());
		}
		if (config.organization() != null && !config.organization().isBlank()) {
			b.organization(config.organization());
		}
		for (Map.Entry<String, String> e : config.extraHeaders().entrySet()) {
			b.extraHeader(e.getKey(), e.getValue());
		}
		return b.build();
	}
}
