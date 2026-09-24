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

package com.sure.ai.agent.orchestrator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.sure.ai.agent.react.ReActAgent;

/**
 * 多 Agent 编排器：拆分 → 并行执行 → 聚合。
 *
 * <p>执行模型：</p>
 * <ol>
 *   <li>用 {@link TaskSplitter} 把大任务拆成子任务；</li>
 *   <li>提交到 {@link ExecutorService} 并行执行，每个子任务由
 *       {@code agentFactory.apply(subtask)} 构造的独立 {@link ReActAgent} 处理；</li>
 *   <li>单个子任务抛异常或超时，结果记为 {@code "[ERROR: ...]"}，不拖垮整体；</li>
 *   <li>用 {@link ResultAggregator} 合并所有子结果。</li>
 * </ol>
 *
 * <p><b>线程池生命周期：</b>若未通过 {@link Builder#executor(ExecutorService)} 注入，
 * 编排器会自建一个 {@code fixedThreadPool(4)}。<b>该默认线程池不会自动关闭，
 * 调用方负责在不再使用时调用 {@code shutdown()} 释放；生产环境强烈建议注入独立的、
 * 受调用方管理的 {@link ExecutorService}。</b></p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class AgentOrchestrator {

	/** 默认整体超时。 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	private final TaskSplitter splitter;
	private final ResultAggregator aggregator;
	private final ExecutorService executor;
	private final long timeoutMillis;
	private final Function<String, ReActAgent> agentFactory;

	private AgentOrchestrator(Builder b) {
		this.splitter = b.splitter;
		this.aggregator = b.aggregator;
		this.executor = b.executor;
		this.timeoutMillis = b.timeoutMillis;
		this.agentFactory = b.agentFactory;
	}

	/**
	 * 创建 Builder。
	 *
	 * @param agentFactory 子任务 → ReActAgent 工厂（必需，由调用方提供 client/baseRequest/registry）
	 * @return Builder
	 */
	public static Builder builder(Function<String, ReActAgent> agentFactory) {
		return new Builder(agentFactory);
	}

	/**
	 * 拆分并并行执行。
	 *
	 * @param task 原始任务
	 * @return 聚合后的最终答案
	 */
	public String execute(String task) {
		List<String> subtasks = this.splitter.split(task);
		return runSubtasks(subtasks);
	}

	/**
	 * 直接执行给定子任务列表（跳过拆分）。
	 *
	 * @param subtasks 子任务列表
	 * @return 聚合后的最终答案
	 */
	public String execute(List<String> subtasks) {
		return runSubtasks(subtasks);
	}

	private String runSubtasks(List<String> subtasks) {
		if (subtasks == null || subtasks.isEmpty()) {
			return this.aggregator.aggregate(List.of());
		}
		List<Callable<String>> calls = new ArrayList<>();
		for (String sub : subtasks) {
			calls.add(() -> safeRun(sub));
		}
		List<Future<String>> futures;
		try {
			futures = this.executor.invokeAll(calls, this.timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "[ERROR: orchestrator interrupted]";
		}
		List<String> results = new ArrayList<>();
		for (Future<String> f : futures) {
			results.add(unwrap(f));
		}
		return this.aggregator.aggregate(results);
	}

	private String safeRun(String subtask) {
		try {
			return this.agentFactory.apply(subtask).run(subtask);
		} catch (Exception e) {
			return "[ERROR: " + e.getMessage() + "]";
		}
	}

	private String unwrap(Future<String> f) {
		try {
			return f.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "[ERROR: interrupted]";
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			return "[ERROR: " + (cause == null ? e.getMessage() : cause.getMessage()) + "]";
		} catch (CancellationException e) {
			return "[ERROR: task timeout]";
		}
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private final Function<String, ReActAgent> agentFactory;
		private TaskSplitter splitter = new SimpleTaskSplitter();
		private ResultAggregator aggregator = new ConcatenatingAggregator();
		private ExecutorService executor;
		private long timeoutMillis = DEFAULT_TIMEOUT.toMillis();

		private Builder(Function<String, ReActAgent> agentFactory) {
			if (agentFactory == null) {
				throw new IllegalArgumentException("agentFactory must not be null");
			}
			this.agentFactory = agentFactory;
		}

		/**
		 * 设置任务拆分器（默认 {@link SimpleTaskSplitter}）。
		 *
		 * @param splitter 拆分器
		 * @return this
		 */
		public Builder splitter(TaskSplitter splitter) {
			if (splitter != null) {
				this.splitter = splitter;
			}
			return this;
		}

		/**
		 * 设置结果聚合器（默认 {@link ConcatenatingAggregator}）。
		 *
		 * @param aggregator 聚合器
		 * @return this
		 */
		public Builder aggregator(ResultAggregator aggregator) {
			if (aggregator != null) {
				this.aggregator = aggregator;
			}
			return this;
		}

		/**
		 * 注入执行线程池。未注入时编排器自建 fixedThreadPool(4)，调用方负责 shutdown。
		 *
		 * @param executor 执行器
		 * @return this
		 */
		public Builder executor(ExecutorService executor) {
			if (executor != null) {
				this.executor = executor;
			}
			return this;
		}

		/**
		 * 设置整体超时（默认 60s）。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
				this.timeoutMillis = timeout.toMillis();
			}
			return this;
		}

		/**
		 * 构建编排器。
		 *
		 * @return 编排器
		 */
		public AgentOrchestrator build() {
			if (this.executor == null) {
				this.executor = Executors.newFixedThreadPool(4);
			}
			return new AgentOrchestrator(this);
		}
	}
}
