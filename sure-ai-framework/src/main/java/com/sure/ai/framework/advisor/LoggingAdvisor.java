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

import java.util.logging.Level;
import java.util.logging.Logger;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Role;

/**
 * 结构化日志 Advisor：用 {@code java.util.logging} 记录一次编排的请求概要、耗时、
 * 响应摘要与工具调用数。
 *
 * <p>推荐置于工具循环 advisor <b>外层</b>，这样一次编排（含多轮工具调用）只记一条总耗时；
 * 若置于内层，则每次模型调用各记一条。日志在 {@code Level.INFO} 输出，异常路径在
 * {@code Level.WARNING} 记录并原样向上抛。</p>
 *
 * <p>日志只摘要关键信息（消息条数 / 工具条数 / 响应文本长度），不落完整提示词，避免
 * 敏感内容刷屏。无状态、可并发共享。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class LoggingAdvisor implements Advisor {

	private static final Logger LOG = Logger.getLogger(LoggingAdvisor.class.getName());

	/** 响应摘要截断长度。 */
	private static final int SUMMARY_MAX = 80;

	@Override
	public ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
		long start = System.nanoTime();
		try {
			ChatResponse resp = chain.proceed(ctx);
			long ms = (System.nanoTime() - start) / 1_000_000L;
			LOG.info(() -> "advisor chat done"
				+ " model=" + ctx.baseRequest().model()
				+ " messages=" + ctx.messages().size()
				+ " toolResults=" + countToolResults(ctx)
				+ " finishReason=" + finishReason(resp)
				+ " respLen=" + respLen(resp)
				+ " durationMs=" + ms);
			return resp;
		} catch (RuntimeException e) {
			long ms = (System.nanoTime() - start) / 1_000_000L;
			LOG.log(Level.WARNING, "advisor chat failed after " + ms + "ms: " + e.getMessage(), e);
			throw e;
		}
	}

	/** 统计本轮累计回填的 tool 结果消息数。 */
	private static int countToolResults(AdvisorContext ctx) {
		int n = 0;
		for (ChatMessage m : ctx.messages()) {
			if (m.role() == Role.TOOL) {
				n++;
			}
		}
		return n;
	}

	/** 响应首条候选 finish_reason。 */
	private static String finishReason(ChatResponse resp) {
		if (resp == null || resp.choices().isEmpty()) {
			return "none";
		}
		return resp.choices().get(0).finishReason();
	}

	/** 响应文本长度（摘要截断）。 */
	private static int respLen(ChatResponse resp) {
		String t = resp == null ? null : resp.firstText();
		return t == null ? 0 : Math.min(t.length(), SUMMARY_MAX);
	}
}
