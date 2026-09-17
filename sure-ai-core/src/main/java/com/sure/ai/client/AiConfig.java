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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
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
	private final List<RetryListener> retryListeners;
	private final MetricsCollector metricsCollector;
	private final double rateLimitQps;

	private AiConfig(Builder b) {
		this.apiKey = b.apiKey;
		this.baseUrl = b.baseUrl;
		this.timeout = b.timeout;
		this.connectTimeout = b.connectTimeout;
		this.proxy = b.proxy;
		this.organization = b.organization;
		this.extraHeaders = b.extraHeaders == null ? Map.of() : Map.copyOf(b.extraHeaders);
		this.maxRetries = b.maxRetries;
		this.retryListeners = b.retryListeners == null ? List.of() : List.copyOf(b.retryListeners);
		this.metricsCollector = b.metricsCollector;
		this.rateLimitQps = b.rateLimitQps;
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
		private List<RetryListener> retryListeners;
		private MetricsCollector metricsCollector;
		private double rateLimitQps;

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
		 * 追加一个重试事件监听器（可多次调用累加）。
		 *
		 * @param listener 重试监听器
		 * @return this
		 */
		public Builder retryListener(RetryListener listener) {
			if (this.retryListeners == null) {
				this.retryListeners = new ArrayList<>();
			}
			this.retryListeners.add(listener);
			return this;
		}

		/**
		 * 批量追加重试事件监听器。
		 *
		 * @param listeners 重试监听器列表
		 * @return this
		 */
		public Builder retryListeners(List<RetryListener> listeners) {
			if (listeners == null) {
				return this;
			}
			if (this.retryListeners == null) {
				this.retryListeners = new ArrayList<>();
			}
			this.retryListeners.addAll(listeners);
			return this;
		}

		/**
		 * 挂载指标采集器（可选，不挂载时零开销）。
		 *
		 * @param metricsCollector 指标采集器
		 * @return this
		 */
		public Builder metricsCollector(MetricsCollector metricsCollector) {
			this.metricsCollector = metricsCollector;
			return this;
		}

		/**
		 * 客户端限流 QPS（&gt;0 启用令牌桶限流，0=关闭，默认 0）。
		 *
		 * @param qps 每秒放行请求数
		 * @return this
		 */
		public Builder rateLimitQps(double qps) {
			this.rateLimitQps = qps;
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

	/** 已注册的重试监听器（不可变，默认空列表）。 */
	public List<RetryListener> retryListeners() {
		return this.retryListeners;
	}

	/** 指标采集器，未挂载时返回 null。 */
	public MetricsCollector metricsCollector() {
		return this.metricsCollector;
	}

	/** 限流 QPS，0 表示关闭。 */
	public double rateLimitQps() {
		return this.rateLimitQps;
	}
}
