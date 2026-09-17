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
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.Model;

/**
 * 智谱 AI 开放平台客户端。
 *
 * <p>请求体/响应体与 OpenAI 兼容（复用 {@link OpenAiCompatClient}），仅鉴权特殊：
 * 不是把 apiKey 直接当 Bearer token，而是用 {@code id.secret} 中的 secret 现场签发一个
 * HS256 JWT 作为 Bearer token，并在有效期内缓存复用。</p>
 *
 * <p>默认 baseUrl：{@code https://open.bigmodel.cn/api/paas/v4}。</p>
 *
 * <p>P2 平台特定能力：</p>
 * <ul>
 *   <li><b>模型列表</b>：智谱无公开 REST 模型列表 API，{@link #listModels()} 直接抛
 *   {@link AiException}，模型 ID 见 {@link ZhipuModels}。</li>
 *   <li><b>思考模式 / Grounding</b>：智谱 OpenAI 兼容模式与 OpenAI 协议高度相似，
 *   {@code reasoning_effort} 与 {@code web_search} 工具由 core 序列化，无需额外改写。</li>
 *   <li><b>微调</b>：智谱微调为独立控制台/工单流程，无稳定公开 REST 端点，本期未单独适配。</li>
 * </ul>
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
		// baseUrl 已含 /api/paas/v4，这里显式给出相对语音接口路径，便于阅读与覆盖。
		this.ttsPath = "/audio/speech";
		this.sttPath = "/audio/transcriptions";
	}

	@Override
	public String name() {
		return "zhipu";
	}

	/**
	 * 智谱不提供公开的 OpenAI 风格 {@code GET /models} 列表 API（模型仅在静态文档页概览）。
	 *
	 * <p>若沿用 core 的默认实现会对 {@code /models} 发请求并收到 404，故此处直接抛出
	 * 业务异常，引导调用方使用 {@link ZhipuModels} 中的静态模型 ID 常量。</p>
	 *
	 * @return 从不正常返回
	 * @throws AiException 始终抛出，说明智谱无公开模型列表 API
	 */
	@Override
	public List<Model> listModels() {
		throw new AiException("Zhipu does not provide a public models list API; "
			+ "use static model IDs in ZhipuModels");
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
