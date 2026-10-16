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

package com.sure.ai.framework.advisor;

import java.util.List;
import java.util.logging.Logger;

import com.sure.ai.framework.cache.SemanticCache;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Role;
import com.sure.tool.lang.Assert;

/**
 * 语义缓存 Advisor：命中即短路、未命中回填。
 *
 * <p>以上下文里最后一条 user 文本为 {@code query}，先查 {@link SemanticCache}：</p>
 * <ul>
 *   <li><b>命中</b>：直接返回缓存的 {@link ChatResponse}，<b>不调用内层 advisor 与终端</b>，
 *       但 {@link #after} 钩子仍会逆序执行（链语义不被短路破坏）；</li>
 *   <li><b>未命中</b>：{@code chain.proceed(ctx)} 正常调用，成功后把 (query, response) 回填缓存。</li>
 * </ul>
 *
 * <p>embedder / threshold / store 都由使用方在构建 {@link SemanticCache} 时配好；本 advisor
 * 只负责接线。推荐把它放在链的<b>最外层</b>，让短路尽可能早发生。query 为空（多模态等）时
 * 本 advisor 退化为透传，不读不写缓存。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class SemanticCacheAdvisor implements Advisor {

	private static final Logger LOG = Logger.getLogger(SemanticCacheAdvisor.class.getName());

	private final SemanticCache cache;

	private final long ttlMillis;

	/**
	 * 用缓存默认 TTL 构造。
	 *
	 * @param cache 语义缓存
	 */
	public SemanticCacheAdvisor(SemanticCache cache) {
		this(cache, 0L);
	}

	/**
	 * 全参构造。
	 *
	 * @param cache      语义缓存
	 * @param ttlMillis   写入 TTL（&lt;=0 使用缓存构建器默认 TTL）
	 */
	public SemanticCacheAdvisor(SemanticCache cache, long ttlMillis) {
		Assert.notNull(cache, "cache 不能为 null");
		this.cache = cache;
		this.ttlMillis = ttlMillis;
	}

	@Override
	public ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
		String query = lastUserText(ctx.messages());
		if (query == null) {
			return chain.proceed(ctx);
		}
		ChatResponse hit = this.cache.get(query);
		if (hit != null) {
			LOG.fine(() -> "semantic cache hit for query: " + preview(query));
			return hit;
		}
		ChatResponse resp = chain.proceed(ctx);
		if (resp != null) {
			try {
				this.cache.put(query, resp, this.ttlMillis);
			} catch (RuntimeException e) {
				// 回填失败不影响主结果
				LOG.fine(() -> "semantic cache put skipped: " + e.getMessage());
			}
		}
		return resp;
	}

	/** 取最后一条 user 文本内容；无则返回 {@code null}。 */
	private static String lastUserText(List<ChatMessage> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage m = messages.get(i);
			if (m.role() == Role.USER && m.content() != null && !m.content().isBlank()) {
				return m.content();
			}
		}
		return null;
	}

	/** 日志预览截断。 */
	private static String preview(String q) {
		return q.length() <= 40 ? q : q.substring(0, 40) + "…";
	}
}
