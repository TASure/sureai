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

package com.sure.ai.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.ApiKeyProvider;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiRateLimitException;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * 密钥轮转装饰器：包装一个“按 key 构造平台客户端”的工厂，在调用失败（鉴权失败 / 限流）时
 * 自动切换到下一个 API Key 重建客户端并重试。
 *
 * <h2>方案选择（对应任务书方案 B 的简化落地）</h2>
 * <p>由于 {@code AiClient.chat(ChatRequest)} 不暴露请求头注入点，且不能修改
 * {@code AbstractAiClient}/{@code AiConfig} 的公共 API，本装饰器采用<b>工厂 + 缓存</b>方案：
 * 使用方传入 {@code Function<String, AiClient> clientFactory}——给定一个 apiKey 构造一个平台客户端。
 * 装饰器按 key 缓存已构造的客户端（{@link ConcurrentHashMap}），调用前从
 * {@link ApiKeyProvider#currentKey()} 取 key 选客户端；当调用抛出
 * {@link AiAuthException}（401/403）或 {@link AiRateLimitException}（429）时，
 * 调用 {@link ApiKeyProvider#markBad(String)} 拉黑该 key，再用 {@link ApiKeyProvider#nextKey()}
 * 切下一个 key 重试。最多尝试 {@link ApiKeyProvider#size()} 次（每个 key 一次）。</p>
 *
 * <p><b>不轮转的异常</b>：非 401/429 的异常（含 5xx、超时、其它 4xx）按默认谓词直接抛出，
 * 不消耗 key。调用方可通过带 {@link Predicate} 的构造器自定义“哪些异常触发换 key”。</p>
 *
 * <p>全部 key 都失败时抛出 {@link AiException}，把每次尝试的原始异常作为 suppressed 附加。</p>
 *
 * <p>本装饰器实现 {@link AiClient}，可直接注册进 {@link ClientRegistry}，对上层透明。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class KeyRotatingClientDecorator implements AiClient {

	/** 密钥提供者。 */
	private final ApiKeyProvider provider;

	/** 按 apiKey 构造平台客户端的工厂。 */
	private final Function<String, AiClient> clientFactory;

	/** key → 已构造客户端缓存。 */
	private final Map<String, AiClient> clientCache = new ConcurrentHashMap<>();

	/** 判定某异常是否触发换 key 重试（沿 cause 链）。 */
	private final Predicate<Throwable> rotateOn;

	/**
	 * 默认构造器：401/403/429 触发换 key。
	 *
	 * @param provider     密钥池
	 * @param clientFactory 给定 apiKey 构造平台客户端
	 */
	public KeyRotatingClientDecorator(ApiKeyProvider provider,
			Function<String, AiClient> clientFactory) {
		this(provider, clientFactory, KeyRotatingClientDecorator::defaultRotate);
	}

	/**
	 * 全参构造器。
	 *
	 * @param provider     密钥池
	 * @param clientFactory 给定 apiKey 构造平台客户端
	 * @param rotateOn     判定异常是否触发换 key（沿 cause 链调用）
	 */
	public KeyRotatingClientDecorator(ApiKeyProvider provider,
			Function<String, AiClient> clientFactory, Predicate<Throwable> rotateOn) {
		Objects.requireNonNull(provider, "provider must not be null");
		Objects.requireNonNull(clientFactory, "clientFactory must not be null");
		Objects.requireNonNull(rotateOn, "rotateOn must not be null");
		this.provider = provider;
		this.clientFactory = clientFactory;
		this.rotateOn = rotateOn;
	}

	@Override
	public String name() {
		return "key-rotating";
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		List<Throwable> suppressed = new ArrayList<>();
		int maxAttempts = Math.max(1, this.provider.size());
		String key = this.provider.currentKey();
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			AiClient client = clientFor(key);
			try {
				return client.chat(request);
			} catch (Exception ex) {
				suppressed.add(ex);
				if (!this.rotateOn.test(ex)) {
					throw propagate(ex);
				}
				this.provider.markBad(key);
				if (attempt == maxAttempts - 1) {
					break;
				}
				key = this.provider.nextKey();
			}
		}
		AiException exhausted = new AiException(
				"all " + maxAttempts + " api key(s) exhausted while calling chat");
		suppressed.forEach(exhausted::addSuppressed);
		throw exhausted;
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		List<Throwable> suppressed = new ArrayList<>();
		int maxAttempts = Math.max(1, this.provider.size());
		String key = this.provider.currentKey();
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			AiClient client = clientFor(key);
			try {
				client.chatStream(request, consumer);
				return;
			} catch (Exception ex) {
				suppressed.add(ex);
				if (!this.rotateOn.test(ex)) {
					throw propagate(ex);
				}
				this.provider.markBad(key);
				if (attempt == maxAttempts - 1) {
					break;
				}
				key = this.provider.nextKey();
			}
		}
		AiException exhausted = new AiException(
				"all " + maxAttempts + " api key(s) exhausted while calling chatStream");
		suppressed.forEach(exhausted::addSuppressed);
		throw exhausted;
	}

	@Override
	public void close() {
		this.clientCache.values().forEach(AiClient::close);
		this.clientCache.clear();
	}

	/**
	 * 取（必要时构造）某 key 对应的缓存客户端。
	 *
	 * @param key apiKey
	 * @return 客户端
	 */
	private AiClient clientFor(String key) {
		return this.clientCache.computeIfAbsent(key, this.clientFactory);
	}

	/**
	 * 把异常原样抛出（若是 RuntimeException），否则包成 AiException。
	 *
	 * @param ex 原始异常
	 * @return 待抛出的运行时异常
	 */
	private static RuntimeException propagate(Exception ex) {
		if (ex instanceof RuntimeException re) {
			return re;
		}
		return new AiException("non-rotatable failure", ex);
	}

	/**
	 * 默认换 key 判定：沿 cause 链遇到 {@link AiAuthException} 或 {@link AiRateLimitException}。
	 *
	 * @param t 异常
	 * @return 是否换 key
	 */
	private static boolean defaultRotate(Throwable t) {
		Throwable cur = t;
		while (cur != null) {
			if (cur instanceof AiAuthException || cur instanceof AiRateLimitException) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}
}
