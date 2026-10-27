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
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.ToolCall;
import com.sure.tool.lang.Assert;

/**
 * 自动工具循环 Advisor：把模型返回的 {@code tool_calls} 闭环成「调用工具 → 回填 → 再问模型」。
 *
 * <p>工作循环：</p>
 * <ol>
 *   <li>{@code chain.proceed(ctx)} 调模型（可能已携带 {@code tools} 声明）；</li>
 *   <li>响应首条候选消息带 {@code tool_calls} 且上下文存在 {@link ToolExecutor} 时：
 *       追加 {@code assistant(toolCalls)} 消息，逐个安全执行工具并追加 {@code tool(id,result)}
 *       消息，然后回到第 1 步；</li>
 *   <li>响应无 {@code tool_calls}、无执行器，或达到 {@code maxIterations} → 返回该响应结束循环。</li>
 * </ol>
 *
 * <p><b>失败隔离</b>：单个工具抛异常 / 未注册 / 参数解析失败都被收敛为错误文本回灌模型，
 * 不影响其余工具与主循环。<b>循环上限</b>默认 {@value #DEFAULT_MAX_ITERATIONS}，
 * 防止模型持续工具调用不收敛；达上限时把最后一次响应（可能仍含 tool_calls）原样返回。</p>
 *
 * <p>线程安全：Advisor 无状态，可在多个代理 / 并发调用间共享；每次调用状态存于
 * {@link AdvisorContext}，天然线程隔离。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class ToolCallingAdvisor implements Advisor {

	/** 默认最大迭代轮数。 */
	public static final int DEFAULT_MAX_ITERATIONS = 5;

	private static final Logger LOG = Logger.getLogger(ToolCallingAdvisor.class.getName());

	private final int maxIterations;

	/**
	 * 默认构造：最大迭代 {@value #DEFAULT_MAX_ITERATIONS}。
	 */
	public ToolCallingAdvisor() {
		this(DEFAULT_MAX_ITERATIONS);
	}

	/**
	 * 全参构造。
	 *
	 * @param maxIterations 最大迭代轮数（≥1）
	 */
	public ToolCallingAdvisor(int maxIterations) {
		Assert.isTrue(maxIterations >= 1, "maxIterations 必须 >= 1: " + maxIterations);
		this.maxIterations = maxIterations;
	}

	@Override
	public ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
		int iterations = 0;
		ChatResponse resp = chain.proceed(ctx);
		while (hasToolCalls(resp) && ctx.toolExecutor() != null && iterations < this.maxIterations) {
			iterations++;
			List<ToolCall> calls = firstMessage(resp).toolCalls();
			ctx.messages().add(ChatMessage.assistant(calls));
			for (ToolCall call : calls) {
				String result = safeExecute(ctx.toolExecutor(), call);
				ctx.messages().add(ChatMessage.tool(call.id(), result));
			}
			final int round = iterations;
			LOG.fine(() -> "tool round " + round + " executed " + calls.size() + " tool(s)");
			resp = chain.proceed(ctx);
		}
		if (hasToolCalls(resp) && iterations >= this.maxIterations) {
			LOG.warning("tool loop reached maxIterations=" + this.maxIterations
				+ " before model produced a final answer");
		}
		return resp;
	}

	/** 单工具安全执行：任何异常收敛为错误文本，绝不扩散。 */
	private static String safeExecute(ToolExecutor executor, ToolCall call) {
		try {
			return executor.execute(call.name(), call.argumentsJson());
		} catch (Throwable t) {
			LOG.log(Level.FINE, "tool " + call.name() + " failed", t);
			return "工具执行异常 " + call.name() + ": " + t.getMessage();
		}
	}

	/** 响应首条候选是否携带 tool_calls。 */
	private static boolean hasToolCalls(ChatResponse resp) {
		ChatMessage m = firstMessage(resp);
		return m != null && m.toolCalls() != null && !m.toolCalls().isEmpty();
	}

	/** 取首条候选消息；无候选返回 {@code null}。 */
	private static ChatMessage firstMessage(ChatResponse resp) {
		if (resp == null || resp.choices().isEmpty()) {
			return null;
		}
		Choice c = resp.choices().get(0);
		return c == null ? null : c.message();
	}
}
