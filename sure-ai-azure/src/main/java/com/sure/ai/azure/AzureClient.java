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

import java.net.http.HttpRequest;
import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.compat.OpenAiCompatClient;
import com.sure.ai.model.FineTuneResponse;

/**
 * Azure OpenAI（Azure AI Foundry）客户端。
 *
 * <p>复用 OpenAI 兼容协议的请求序列化与响应解析，但覆盖两处差异：</p>
 * <ul>
 *   <li><b>鉴权</b>：使用 {@code api-key: <apiKey>} 头（而非 {@code Authorization: Bearer}）。</li>
 *   <li><b>URL</b>：对话为 {@code {baseUrl}/openai/deployments/{deployment}/chat/completions?api-version={apiVersion}}，
 *   向量为 {@code .../openai/deployments/{deployment}/embeddings?api-version={apiVersion}}，
 *   图像为 {@code .../openai/deployments/{deployment}/images/generations?api-version={apiVersion}}。</li>
 * </ul>
 *
 * <p>配置约定：</p>
 * <ul>
 *   <li>{@code deployment}：从 {@code config.extraHeaders("deployment")} 读取，缺省 {@code gpt-4o}；</li>
 *   <li>{@code api-version}：从 {@code config.extraHeaders("api-version")} 读取，缺省 {@link #DEFAULT_API_VERSION}；</li>
 *   <li>{@code baseUrl}：为 null 时从 {@code config.extraHeaders("resource")} 或环境变量
 *   {@code SURE_AI_AZURE_RESOURCE} 推导为 {@code https://{resource}.openai.azure.com}。</li>
 * </ul>
 *
 * <p>官方文档：<a href="https://learn.microsoft.com/en-us/azure/ai-foundry/openai/reference">REST API reference</a>；
 * <a href="https://learn.microsoft.com/en-us/azure/ai-foundry/openai/api-version-lifecycle">api-version 生命周期</a>
 * （2024-10-21 为当前最新 GA 版本）。</p>
 *
 * <p>P2 平台特定能力：</p>
 * <ul>
 *   <li>{@code GET /openai/models?api-version=}：模型列表（继承 core 的 data[] 解析）；</li>
 *   <li>{@code POST /openai/moderations?api-version=}：内容审核；</li>
 *   <li>{@code POST /openai/fine_tuning/jobs?api-version=}、
 *   {@code GET /openai/fine_tuning/jobs/{id}?api-version=}、
 *   {@code POST /openai/files?api-version=}：微调与训练文件上传；</li>
 *   <li>reasoning_effort 与 grounding web_search 工具与 OpenAI 协议一致，由 core 序列化。</li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
public class AzureClient extends OpenAiCompatClient {

	/** 当前最新 GA api-version。 */
	public static final String DEFAULT_API_VERSION = "2024-10-21";

	/** 缺省部署名（参考值，实际应通过 extraHeaders("deployment", ...) 指定）。 */
	public static final String DEFAULT_DEPLOYMENT = "gpt-4o";

	/** 环境变量名：resource 名。 */
	public static final String ENV_RESOURCE = "SURE_AI_AZURE_RESOURCE";

	/** 部署名。 */
	private final String deployment;

	/** api-version。 */
	private final String apiVersion;

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（deployment/api-version/resource 经 extraHeaders 传入）
	 */
	public AzureClient(AiConfig config) {
		super(normalizeBaseUrl(config));
		Map<String, String> hdrs = this.config.extraHeaders();
		this.deployment = hdrs.getOrDefault("deployment", DEFAULT_DEPLOYMENT);
		this.apiVersion = hdrs.getOrDefault("api-version", DEFAULT_API_VERSION);
		this.chatPath = "/openai/deployments/" + this.deployment
			+ "/chat/completions?api-version=" + this.apiVersion;
		this.embeddingsPath = "/openai/deployments/" + this.deployment
			+ "/embeddings?api-version=" + this.apiVersion;
		this.imagesPath = "/openai/deployments/" + this.deployment
			+ "/images/generations?api-version=" + this.apiVersion;
		// 平台特定能力：模型列表 / 内容审核 / 微调 / 文件上传均走 /openai/* 根路径，
		// 鉴权由 applyAuth 的 api-key 头完成，端点统一拼接 api-version 查询参数。
		this.modelsPath = "/openai/models?api-version=" + this.apiVersion;
		this.moderationsPath = "/openai/moderations?api-version=" + this.apiVersion;
		this.fineTunePath = "/openai/fine_tuning/jobs?api-version=" + this.apiVersion;
		this.filesPath = "/openai/files?api-version=" + this.apiVersion;
	}

	@Override
	public String name() {
		return "azure";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("api-key", cfg.apiKey());
	}

	/**
	 * 查询微调任务详情：Azure 的任务详情端点为
	 * {@code /openai/fine_tuning/jobs/{id}?api-version=...}，不能直接复用带查询串的
	 * {@link #fineTunePath} 后再追加 {@code /{id}}（否则查询串会落到 path 中间）。
	 *
	 * @param jobId 微调任务 ID
	 * @return 微调任务响应
	 */
	@Override
	public FineTuneResponse getFineTune(String jobId) {
		PostResult result = doGetRaw("/openai/fine_tuning/jobs/" + jobId
			+ "?api-version=" + this.apiVersion);
		return parseFineTuneResponse(result.json(), result.rawBody());
	}

	/** 部署名。 */
	public String deployment() {
		return this.deployment;
	}

	/** api-version。 */
	public String apiVersion() {
		return this.apiVersion;
	}

	/**
	 * baseUrl 为 null 时，用 resource 推导默认地址重建配置。
	 *
	 * @param config 原始配置
	 * @return 补齐 baseUrl 后的配置
	 */
	static AiConfig normalizeBaseUrl(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		String resource = config.extraHeaders().get("resource");
		if (resource == null || resource.isBlank()) {
			resource = System.getenv(ENV_RESOURCE);
		}
		if (resource == null || resource.isBlank()) {
			return config;
		}
		return rebuild(config, "https://" + resource + ".openai.azure.com");
	}

	/** 用新 baseUrl 替换配置（其余全部字段通过 {@link AiConfig#withBaseUrl} 原样保留）。 */
	private static AiConfig rebuild(AiConfig config, String baseUrl) {
		return config.withBaseUrl(baseUrl);
	}
}
