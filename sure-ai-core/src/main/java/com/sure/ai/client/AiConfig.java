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

import com.sure.ai.client.cache.CacheStore;
import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.client.observability.RetryListener;
import com.sure.ai.client.resilience.CircuitBreaker;
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
	private final CacheStore cacheStore;
	private final Duration cacheTtl;
	private final CircuitBreaker circuitBreaker;

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
		this.cacheStore = b.cacheStore;
		this.cacheTtl = b.cacheTtl;
		this.circuitBreaker = b.circuitBreaker;
	}

	/**
	 * 全字段拷贝构造器（供 {@link #withBaseUrl} 使用，绕过 Builder 以保留全部字段）。
	 *
	 * <p>extraHeaders / retryListeners 已在 {@link #AiConfig(Builder)} 中做过不可变拷贝，
	 * 此处直接引用同一不可变实例即可。</p>
	 */
	private AiConfig(String apiKey, String baseUrl, Duration timeout, Duration connectTimeout,
			String proxy, String organization, Map<String, String> extraHeaders,
			int maxRetries, List<RetryListener> retryListeners,
			MetricsCollector metricsCollector, double rateLimitQps,
			CacheStore cacheStore, Duration cacheTtl, CircuitBreaker circuitBreaker) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.timeout = timeout;
		this.connectTimeout = connectTimeout;
		this.proxy = proxy;
		this.organization = organization;
		this.extraHeaders = extraHeaders;
		this.maxRetries = maxRetries;
		this.retryListeners = retryListeners;
		this.metricsCollector = metricsCollector;
		this.rateLimitQps = rateLimitQps;
		this.cacheStore = cacheStore;
		this.cacheTtl = cacheTtl;
		this.circuitBreaker = circuitBreaker;
	}

	/**
	 * 返回 baseUrl 替换为指定值的新配置，其余所有字段原样保留。
	 *
	 * <p>用于平台子类在 baseUrl 为空时补默认地址，避免 Builder 重建丢失跨切面字段
	 * （metricsCollector/retryListeners/rateLimitQps/cacheStore/cacheTtl/circuitBreaker）。</p>
	 *
	 * @param baseUrl 新的 baseUrl
	 * @return 新配置实例
	 */
	public AiConfig withBaseUrl(String baseUrl) {
		return new AiConfig(this.apiKey, baseUrl, this.timeout, this.connectTimeout,
			this.proxy, this.organization, this.extraHeaders,
			this.maxRetries, this.retryListeners,
			this.metricsCollector, this.rateLimitQps,
			this.cacheStore, this.cacheTtl, this.circuitBreaker);
	}

	/**
	 * baseUrl 为空（null 或全空白）时补默认地址，否则原样返回。
	 *
	 * <p>平台子类构造器的统一入口：等价于
	 * {@code (this.baseUrl 为空 ? withBaseUrl(defaultBase) : this)}，其余字段全部保留。
	 * 用于消除各平台子类中重复的 {@code applyDefaultBaseUrl} 样板。</p>
	 *
	 * @param defaultBase baseUrl 为空时使用的默认地址
	 * @return 补齐 baseUrl 后的配置
	 */
	public AiConfig withBaseUrlIfAbsent(String defaultBase) {
		if (this.baseUrl != null && !this.baseUrl.isBlank()) {
			return this;
		}
		return withBaseUrl(defaultBase);
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
		private CacheStore cacheStore;
		private Duration cacheTtl;
		private CircuitBreaker circuitBreaker;

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
		 * 挂载对话响应缓存存储（可选，默认 null=关闭；关闭时零开销）。
		 *
		 * <p>仅非流式 chat 走缓存；缓存命中不触发网络、指标与重试。</p>
		 *
		 * @param cacheStore 缓存存储 SPI 实现
		 * @return this
		 */
		public Builder cacheStore(CacheStore cacheStore) {
			this.cacheStore = cacheStore;
			return this;
		}

		/**
		 * 缓存 TTL（可选；不设置时使用 store 自带默认 TTL）。
		 *
		 * @param ttl 缓存存活时长
		 * @return this
		 */
		public Builder cacheTtl(Duration ttl) {
			this.cacheTtl = ttl;
			return this;
		}

		/**
		 * 挂载熔断器（可选，默认 null=关闭；关闭时零开销）。
		 *
		 * <p>熔断在重试循环之外再包一层：OPEN 时不发起网络、不触发重试/指标/限流，
		 * 直接快速失败。不配置时行为与之前完全一致。</p>
		 *
		 * @param circuitBreaker 熔断器
		 * @return this
		 */
		public Builder circuitBreaker(CircuitBreaker circuitBreaker) {
			this.circuitBreaker = circuitBreaker;
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

	/** 对话响应缓存存储，未挂载时返回 null（缓存关闭）。 */
	public CacheStore cacheStore() {
		return this.cacheStore;
	}

	/** 缓存 TTL，未设置时返回 null（使用 store 默认 TTL）。 */
	public Duration cacheTtl() {
		return this.cacheTtl;
	}

	/** 熔断器，未挂载时返回 null（熔断关闭）。 */
	public CircuitBreaker circuitBreaker() {
		return this.circuitBreaker;
	}
}
