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

package com.sure.ai.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 客户端配置。
 *
 * <p>baseUrl 为 null 时由具体平台子类提供默认地址。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class AiConfig {

	private final String apiKey;
	private final String baseUrl;
	private final Duration timeout;
	private final Duration connectTimeout;
	private final String proxy;
	private final String organization;
	private final Map<String, String> extraHeaders;
	private final int maxRetries;

	private AiConfig(Builder b) {
		this.apiKey = b.apiKey;
		this.baseUrl = b.baseUrl;
		this.timeout = b.timeout;
		this.connectTimeout = b.connectTimeout;
		this.proxy = b.proxy;
		this.organization = b.organization;
		this.extraHeaders = b.extraHeaders == null ? Map.of() : Map.copyOf(b.extraHeaders);
		this.maxRetries = b.maxRetries;
	}

	/**
	 * 仅指定 API Key 的快捷构造。
	 *
	 * @param apiKey API Key
	 * @return 配置
	 */
	public static AiConfig of(String apiKey) {
		return builder().apiKey(apiKey).build();
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String apiKey;
		private String baseUrl;
		private Duration timeout = Duration.ofSeconds(60);
		private Duration connectTimeout = Duration.ofSeconds(10);
		private String proxy;
		private String organization;
		private Map<String, String> extraHeaders;
		private int maxRetries = 2;

		private Builder() {
		}

		/**
		 * API Key。
		 *
		 * @param apiKey API Key
		 * @return this
		 */
		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		/**
		 * baseUrl。
		 *
		 * @param baseUrl 基础地址
		 * @return this
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * 读超时。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 连接超时。
		 *
		 * @param connectTimeout 连接超时
		 * @return this
		 */
		public Builder connectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
			return this;
		}

		/**
		 * 代理 host:port。
		 *
		 * @param proxy 代理
		 * @return this
		 */
		public Builder proxy(String proxy) {
			this.proxy = proxy;
			return this;
		}

		/**
		 * 组织 ID。
		 *
		 * @param organization 组织
		 * @return this
		 */
		public Builder organization(String organization) {
			this.organization = organization;
			return this;
		}

		/**
		 * 追加请求头。
		 *
		 * @param name  头名
		 * @param value 头值
		 * @return this
		 */
		public Builder extraHeader(String name, String value) {
			if (this.extraHeaders == null) {
				this.extraHeaders = new LinkedHashMap<>();
			}
			this.extraHeaders.put(name, value);
			return this;
		}

		/**
		 * 最大重试次数。
		 *
		 * @param maxRetries 重试次数
		 * @return this
		 */
		public Builder maxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
			return this;
		}

		/**
		 * 构建。
		 *
		 * @return 配置
		 */
		public AiConfig build() {
			Assert.notBlank(this.apiKey, "apiKey must not be blank");
			return new AiConfig(this);
		}
	}

	/** API Key。 */
	public String apiKey() {
		return this.apiKey;
	}

	/** baseUrl，可能为 null。 */
	public String baseUrl() {
		return this.baseUrl;
	}

	/** 读超时。 */
	public Duration timeout() {
		return this.timeout;
	}

	/** 连接超时。 */
	public Duration connectTimeout() {
		return this.connectTimeout;
	}

	/** 代理 host:port，可能为 null。 */
	public String proxy() {
		return this.proxy;
	}

	/** 组织，可能为 null。 */
	public String organization() {
		return this.organization;
	}

	/** 额外请求头。 */
	public Map<String, String> extraHeaders() {
		return this.extraHeaders;
	}

	/** 最大重试次数。 */
	public int maxRetries() {
		return this.maxRetries;
	}
}
