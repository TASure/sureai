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

package com.sure.ai.agent.delegation;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.async.AsyncExecutors;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;
import com.sure.tool.lang.Assert;

/**
 * Agent-as-Tool：把一个子 Agent 包装成父 Agent 可调用的工具。
 *
 * <p><b>语义（与 Handoff 区分）：</b>父 Agent 通过 function-calling 调用本工具时，
 * 委派层把任务描述交给子 {@link AgentRunner} 执行，子 Agent 的最终答案作为一条
 * 工具观察（observation）回灌父模型，<b>父 Agent 拿到结果后继续自己的推理循环</b>，
 * 并不会把控制权交出。</p>
 *
 * <p><b>防护：</b></p>
 * <ul>
 *   <li>最大委派深度（默认 {@value #DEFAULT_MAX_DEPTH}），超限抛
 *       {@link DelegationDepthExceededException}；</li>
 *   <li>同链环检测：同一 agentId 已在调用链上（直接环 A→A / 间接环 A→B→A）
 *       抛 {@link DelegationCycleException}；</li>
 *   <li>子 Agent 异常包装为 {@link DelegationException}（保留 cause，不吞异常）；</li>
 *   <li>可选单次委派超时，到期取消并抛 {@link DelegationTimeoutException}。</li>
 * </ul>
 *
 * <p><b>事件冒泡：</b>工厂用冒泡监听器构造子 Agent，子 Agent 的思考 / 工具调用 /
 * 完成等回调经 {@link ForwardingDelegationListener} 转发给父监听者；父监听者缺省
 * 为 null 时子 Agent 仍可独立运行。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class AgentAsTool {

	/** 默认最大委派深度。 */
	public static final int DEFAULT_MAX_DEPTH = 5;

	/** 主任务参数名。 */
	public static final String PARAM_TASK = "task";

	/** 备选任务参数名（模型可能用 description/query/input 传任务）。 */
	private static final String[] ALT_TASK_KEYS = {"description", "query", "input"};

	/** 工具参数 JSON Schema：仅一个可选任务字符串。 */
	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "task": {
			      "type": "string",
			      "description": "要委派给该专职 Agent 处理的具体任务描述"
			    }
			  }
			}
			""";

	private final String id;
	private final String description;
	private final ChildAgentFactory factory;
	private final AgentListener parentSink;
	private final int maxDepth;
	private final Duration timeout;
	private final MemoryPolicy memoryPolicy;

	private AgentAsTool(Builder b) {
		Assert.notBlank(b.id, "id must not be blank");
		Assert.notNull(b.factory, "factory must not be null");
		Assert.isTrue(b.maxDepth >= 1, "maxDepth must be >= 1");
		this.id = b.id;
		this.description = b.description == null ? "" : b.description;
		this.factory = b.factory;
		this.parentSink = b.parentSink;
		this.maxDepth = b.maxDepth;
		this.timeout = b.timeout;
		this.memoryPolicy = b.memoryPolicy == null ? MemoryPolicy.ISOLATED : b.memoryPolicy;
	}

	/**
	 * 以默认配置构造（maxDepth=5、无超时、隔离记忆、无父监听者）。
	 *
	 * @param id          子 Agent 稳定标识（环检测用）
	 * @param description 职责说明（写入工具描述）
	 * @param factory     子 Agent 工厂
	 * @return 实例
	 */
	public static AgentAsTool of(String id, String description, ChildAgentFactory factory) {
		return builder(id, description, factory).build();
	}

	/**
	 * 创建 Builder。
	 *
	 * @param id          子 Agent 稳定标识（环检测用，非空白）
	 * @param description 职责说明（写入工具描述）
	 * @param factory     子 Agent 工厂
	 * @return Builder
	 */
	public static Builder builder(String id, String description, ChildAgentFactory factory) {
		return new Builder(id, description, factory);
	}

	/**
	 * 工具函数声明：名=子 Agent id，描述=职责说明。
	 *
	 * @return 工具函数
	 */
	public ToolFunction toToolFunction() {
		return ToolFunction.of(this.id, this.description, SCHEMA);
	}

	/**
	 * 执行处理器：读取任务参数 → 环/深度检查 → 委派子 Agent → 回传最终答案。
	 *
	 * @return 工具处理器
	 */
	public ToolHandler toHandler() {
		return (JsonObject arguments) -> delegate(extractTask(arguments));
	}

	/**
	 * 直接注册进工具注册中心。
	 *
	 * @param registry 工具注册中心
	 */
	public void register(ToolRegistry registry) {
		Assert.notNull(registry, "registry must not be null");
		registry.register(toToolFunction(), toHandler());
	}

	/** 当前配置的最大深度（测试/调试用）。 */
	int maxDepth() {
		return this.maxDepth;
	}

	/** 当前记忆策略（测试/调试用）。 */
	MemoryPolicy memoryPolicy() {
		return this.memoryPolicy;
	}

	/** 当前单次委派超时（可能为 null）。 */
	Duration timeout() {
		return this.timeout;
	}

	/**
	 * 委派主流程：环检测 → 深度检查 → 压栈 → 运行子 Agent → 出栈。
	 */
	private String delegate(String task) throws Exception {
		if (DelegationScope.contains(this.id)) {
			throw new DelegationCycleException("检测到委派环：Agent [" + this.id
				+ "] 已在当前调用链上重复委派", DelegationScope.ancestorSnapshot());
		}
		int currentDepth = DelegationScope.depth();
		int ceiling = Math.min(this.maxDepth, DelegationScope.ceiling());
		if (currentDepth >= ceiling) {
			throw new DelegationDepthExceededException("委派深度 " + currentDepth
				+ " 已达上限 maxDepth=" + ceiling + "，拒绝继续委派 [" + this.id + "]");
		}
		DelegationContext context = new DelegationContext(DelegationScope.chainId(),
			currentDepth, this.id, task, DelegationScope.ancestorSnapshot(),
			this.memoryPolicy, this.timeout);
		ForwardingDelegationListener sink =
			new ForwardingDelegationListener(this.parentSink, context);
		AgentRunner child = this.factory.create(sink);
		DelegationScope.push(this.id, this.maxDepth);
		try {
			String answer = this.timeout == null
				? child.run(task)
				: runWithTimeout(child, task);
			return answer == null ? "" : answer;
		} catch (DelegationException e) {
			throw e;
		} catch (Exception e) {
			throw new DelegationException("委派子 Agent [" + this.id + "] 执行失败: "
				+ e.getMessage(), e);
		} finally {
			DelegationScope.pop();
		}
	}

	/**
	 * 带超时执行：虚拟线程承载，到期取消。
	 *
	 * <p>注意：超时分支会另起虚拟线程执行子任务，该线程不继承当前线程局部调用链，
	 * 因此超时仅用于叶子级单次委派；嵌套环检测在无超时的同步路径上生效。</p>
	 */
	private String runWithTimeout(AgentRunner child, String task) {
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return child.run(task);
			} catch (RuntimeException re) {
				throw re;
			} catch (Exception e) {
				throw new DelegationException("委派子 Agent [" + this.id + "] 执行失败: "
					+ e.getMessage(), e);
			}
		}, AsyncExecutors.virtualThreadExecutor());
		try {
			return future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			future.cancel(true);
			throw new DelegationTimeoutException("委派子 Agent [" + this.id
				+ "] 超过执行上限 " + this.timeout);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new DelegationException("委派子 Agent [" + this.id + "] 被中断", ie);
		} catch (ExecutionException ee) {
			Throwable cause = ee.getCause();
			throw new DelegationException("委派子 Agent [" + this.id + "] 执行失败: "
				+ (cause == null ? ee.getMessage() : cause.getMessage()), cause);
		}
	}

	/** 从参数对象提取任务描述：task 优先，回退 description/query/input。 */
	private static String extractTask(JsonObject arguments) {
		if (arguments == null) {
			return "";
		}
		String task = arguments.optString(PARAM_TASK, null);
		if (task != null && !task.isBlank()) {
			return task;
		}
		for (String key : ALT_TASK_KEYS) {
			String alt = arguments.optString(key, null);
			if (alt != null && !alt.isBlank()) {
				return alt;
			}
		}
		return "";
	}

	/**
	 * {@link AgentAsTool} 构造器。
	 */
	public static final class Builder {

		private final String id;
		private final String description;
		private final ChildAgentFactory factory;
		private AgentListener parentSink;
		private int maxDepth = DEFAULT_MAX_DEPTH;
		private Duration timeout;
		private MemoryPolicy memoryPolicy = MemoryPolicy.ISOLATED;

		private Builder(String id, String description, ChildAgentFactory factory) {
			Assert.notBlank(id, "id must not be blank");
			Assert.notNull(factory, "factory must not be null");
			this.id = id;
			this.description = description;
			this.factory = factory;
		}

		/**
		 * 父监听者：子 Agent 事件冒泡的目标（null 表示不冒泡）。
		 *
		 * @param listener 父监听者
		 * @return this
		 */
		public Builder parentListener(AgentListener listener) {
			this.parentSink = listener;
			return this;
		}

		/**
		 * 最大委派深度（≥1，默认 5）。
		 *
		 * @param maxDepth 深度上限
		 * @return this
		 */
		public Builder maxDepth(int maxDepth) {
			Assert.isTrue(maxDepth >= 1, "maxDepth must be >= 1");
			this.maxDepth = maxDepth;
			return this;
		}

		/**
		 * 单次委派执行上限（null 表示不额外限制，沿用子 Agent 自身超时）。
		 *
		 * @param timeout 超时预算
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 记忆共享策略（默认隔离）。
		 *
		 * @param policy 策略
		 * @return this
		 */
		public Builder memoryPolicy(MemoryPolicy policy) {
			this.memoryPolicy = policy;
			return this;
		}

		/**
		 * 构建。
		 *
		 * @return AgentAsTool
		 */
		public AgentAsTool build() {
			return new AgentAsTool(this);
		}
	}
}
