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

package com.sure.ai.otel.langfuse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Langfuse 原生导出器不可变配置。
 *
 * <p>认证与端点按 Langfuse 官方 Ingestion API（{@code POST /api/public/ingestion}）约定：
 * HTTP Basic Auth，用户名=publicKey、密码=secretKey，即
 * {@code Authorization: Basic base64(publicKey:secretKey)}。
 * 见 <a href="https://langfuse.com/docs/sdk/python/sdk-v3">Langfuse SDK 文档</a> 与
 * <a href="https://langfuse.com/integrations/native/opentelemetry">OTel 集成认证说明</a>。</p>
 *
 * <p><b>环境变量</b>（官方名优先，sureai 前缀兜底）：</p>
 * <ul>
 *   <li>{@code LANGFUSE_PUBLIC_KEY}（兜底 {@code SURE_AI_LANGFUSE_PUBLIC_KEY}）——项目公钥
 *       （{@code pk-lf-...}），必填；</li>
 *   <li>{@code LANGFUSE_SECRET_KEY}（兜底 {@code SURE_AI_LANGFUSE_SECRET_KEY}）——项目私钥
 *       （{@code sk-lf-...}），必填；</li>
 *   <li>{@code LANGFUSE_HOST}（兜底 {@code SURE_AI_LANGFUSE_ENDPOINT}）——Langfuse 实例基址，
 *       默认 {@code https://cloud.langfuse.com}；</li>
 *   <li>{@code LANGFUSE_ENVIRONMENT}——可选环境标签（如 {@code production}）。</li>
 * </ul>
 *
 * <p><b>无感降级</b>：publicKey 或 secretKey 任一为空时 {@link #isEnabled()} 返回 {@code false}，
 * 导出器为空操作，主流程零感知。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class LangfuseConfig {

	/** 默认 Langfuse Cloud 基址（EU）。 */
	public static final String DEFAULT_HOST = "https://cloud.langfuse.com";

	/** Ingestion 端点相对路径。 */
	static final String INGESTION_PATH = "/api/public/ingestion";

	private final String endpoint;
	private final String publicKey;
	private final String secretKey;
	private final String environment;
	private final boolean enabled;

	private LangfuseConfig(String endpoint, String publicKey, String secretKey, String environment,
			boolean enabled) {
		this.endpoint = endpoint;
		this.publicKey = publicKey;
		this.secretKey = secretKey;
		this.environment = environment;
		this.enabled = enabled;
	}

	/**
	 * 显式构造。
	 *
	 * @param endpoint    Langfuse 实例基址（如 {@code https://cloud.langfuse.com}）；为空回退默认
	 * @param publicKey   项目公钥；为空则配置禁用
	 * @param secretKey   项目私钥；为空则配置禁用
	 * @param environment 环境标签；可为 {@code null}
	 * @return 配置实例
	 */
	public static LangfuseConfig of(String endpoint, String publicKey, String secretKey,
			String environment) {
		String host = (endpoint == null || endpoint.isBlank()) ? DEFAULT_HOST : endpoint.trim();
		String pk = publicKey == null ? "" : publicKey.trim();
		String sk = secretKey == null ? "" : secretKey.trim();
		String env = (environment == null || environment.isBlank()) ? null : environment.trim();
		boolean enabled = !pk.isEmpty() && !sk.isEmpty();
		return new LangfuseConfig(normalizeBase(host), pk, sk, env, enabled);
	}

	/**
	 * 从环境变量读取配置（官方名优先、sureai 前缀兜底）。
	 *
	 * @return 配置实例；未配齐密钥时为禁用态
	 */
	public static LangfuseConfig fromEnv() {
		String pk = firstNonBlank(System.getenv("LANGFUSE_PUBLIC_KEY"),
			System.getenv("SURE_AI_LANGFUSE_PUBLIC_KEY"));
		String sk = firstNonBlank(System.getenv("LANGFUSE_SECRET_KEY"),
			System.getenv("SURE_AI_LANGFUSE_SECRET_KEY"));
		String host = firstNonBlank(System.getenv("LANGFUSE_HOST"),
			System.getenv("SURE_AI_LANGFUSE_ENDPOINT"));
		String env = System.getenv("LANGFUSE_ENVIRONMENT");
		return of(host, pk, sk, env);
	}

	/** @return 禁用（空操作）配置，供未接入 Langfuse 时占位。 */
	public static LangfuseConfig disabled() {
		return new LangfuseConfig(DEFAULT_HOST, "", "", null, false);
	}

	/** @return 是否已配齐密钥、可启用导出。 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/** @return 去掉结尾斜杠的基址。 */
	public String endpoint() {
		return this.endpoint;
	}

	/** @return 项目公钥。 */
	public String publicKey() {
		return this.publicKey;
	}

	/** @return 项目私钥。 */
	public String secretKey() {
		return this.secretKey;
	}

	/** @return 环境标签；可能为 {@code null}。 */
	public String environment() {
		return this.environment;
	}

	/** @return 完整 Ingestion URL（基址 + {@code /api/public/ingestion}）。 */
	public String ingestionUrl() {
		return this.endpoint + INGESTION_PATH;
	}

	/**
	 * 构造 HTTP Basic 认证头值（{@code Basic base64(pk:sk)}）。
	 *
	 * @return 认证头值
	 */
	public String basicAuthorization() {
		String raw = this.publicKey + ":" + this.secretKey;
		return "Basic " + Base64.getEncoder()
			.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static String normalizeBase(String host) {
		String h = host.trim();
		while (h.endsWith("/")) {
			h = h.substring(0, h.length() - 1);
		}
		return h;
	}

	private static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) {
			return a.trim();
		}
		if (b != null && !b.isBlank()) {
			return b.trim();
		}
		return null;
	}
}
