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

package com.sure.ai.boot;

import java.time.Duration;

/**
 * 单个 AI 平台的通用配置项。
 *
 * <p>所有平台共享同一组连接参数：apiKey / baseUrl / model / timeout / maxRetries。
 * 各平台特有的参数（如 Azure 的 api-version、百度的 access_token 换取、智谱的 JWT）
 * 仍通过 {@code AiConfig.extraHeader} 或平台默认约定处理，本通用类只承载 90% 场景。</p>
 *
 * <p>说明：{@code model} 是建议的默认模型名，仅供应用侧读取后用于
 * {@code ChatRequest.builder().model(...)}；sureai 的 {@code AiConfig} 本身不持有模型字段，
 * 模型是「每次请求」级别的参数。</p>
 */
public class PlatformProperties {

	/** 是否启用该平台（默认 true；实际是否装配 Bean 还取决于是否配置了 api-key）。 */
	private boolean enabled = true;

	/** 平台凭证（API Key / Token）。未配置时该平台不装配 Bean。 */
	private String apiKey;

	/** 基础地址，可空——为空时由平台客户端使用各自默认地址。 */
	private String baseUrl;

	/** 建议默认模型名，可空。 */
	private String model;

	/** 读超时，可空（空则用 AiConfig 默认 60s）。 */
	private Duration timeout;

	/** 最大重试次数，可空（空则用 AiConfig 默认 2）。 */
	private Integer maxRetries;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Integer getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(Integer maxRetries) {
		this.maxRetries = maxRetries;
	}
}
