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

package com.sure.ai.agent.react;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.approval.ApprovalDecision;
import com.sure.ai.agent.approval.ApprovalGate;
import com.sure.ai.agent.approval.ApprovalRequest;
import com.sure.ai.agent.approval.ApprovalStatus;
import com.sure.ai.agent.checkpoint.AgentCheckpoint;
import com.sure.ai.agent.checkpoint.CheckpointStore;
import com.sure.ai.agent.memory.ConversationMemory;
import com.sure.ai.agent.memory.longterm.LongTermMemory;
import com.sure.ai.agent.memory.longterm.MemoryEntry;
import com.sure.ai.agent.tool.ToolArgumentValidator;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.async.AsyncExecutors;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.ToolCall;
import com.sure.tool.lang.Assert;

/**
 * ReAct（Reason + Act）编排器：Thought → Action → Observation 循环。
 *
 * <p>核心循环：</p>
 * <ol>
 *   <li>把当前对话历史发给模型，附带注册中心全部工具声明；</li>
 *   <li>模型返回 {@code tool_calls} → 逐个执行（参数校验 → 查 handler → 执行 → 异常捕获），
 *       把 assistant(toolCalls) 与 tool(result) 消息追加进历史；单轮返回多个
 *       {@code tool_calls} 时默认<b>并发执行</b>（虚拟线程承载），结果仍按模型给出的
 *       调用顺序回填历史，保证喂回模型的语义稳定；单个工具失败/超时不影响其余工具；
 *       可通过 {@link #withParallelToolCalls(boolean)} 关闭回退串行；</li>
 *   <li>模型返回纯文本（无 tool_calls）→ 该文本即最终答案，结束；</li>
 *   <li>达到 {@code maxIterations} 仍有工具调用 → 抛 {@link AiException}；
 *       超过总 {@code timeout} → 抛 {@link AiTimeoutException}。</li>
 * </ol>
 *
 * <p>registry 为空时退化为普通单次 chat（直接返回模型文本）。
 * 工具执行异常 / 参数校验失败 / 工具未注册都不会中断编排，而是把错误文本回灌模型让其自我修正。</p>
 *
 * <p><b>会话记忆（可选）：</b>注入 {@link ConversationMemory} 后，每次 run 会把
 * {@code memory.history()} 注入到 baseRequest 模板消息之后、当前用户消息之前
 * （不覆盖 system prompt）；一轮结束后把当轮用户消息与助手最终答案追加进记忆。
 * 不注入（null）时行为与历史版本完全一致。</p>
 *
 * <p><b>检查点持久化（可选）：</b>通过新增构造器注入 {@link CheckpointStore} 与
 * {@code sessionId} 后，run 会在开始、每轮迭代后与结束时自动落盘检查点
 * （见 {@link com.sure.ai.agent.checkpoint.AgentCheckpoint}）；不注入时行为不变。
 * 异常中断时不强制落盘最终检查点。</p>
 *
 * <p><b>HITL 审批（可选）：</b>注入 {@link ApprovalGate} 后，每次工具执行前先经策略判断；
 * 需要审批时阻塞等待决定——批准则继续执行，拒绝/超时则不执行工具、把提示文本
 * 回灌模型自我修正。不注入（null）时行为与历史版本完全一致。</p>
 *
 * <p><b>长期记忆（可选）：</b>注入 {@link LongTermMemory} 后，run 前按当前用户问题
 * 召回 Top-K 相关记忆并以 system 消息注入 baseRequest 模板之后；run 结束后把本轮
 * 用户消息与最终答案提取沉淀。与会话内 {@link ConversationMemory} 互不干扰。
 * 不注入（null）时行为不变。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class ReActAgent {

	/** 默认最大迭代轮数。 */
	public static final int DEFAULT_MAX_ITERATIONS = 10;

	/** 默认总超时。 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

	/** 长期记忆默认召回条数。 */
	public static final int DEFAULT_RECALL_K = 3;

	/** 单轮并行工具调用的默认最大并发数（分波执行，超过该数的调用分多波并发）。 */
	public static final int DEFAULT_MAX_PARALLEL_TOOL_CALLS = 8;

	private final AiClient client;
	private final ChatRequest baseRequest;
	private final ToolRegistry registry;
	private final AgentListener listener;
	private final int maxIterations;
	private final Duration timeout;
	private final ConversationMemory memory;
	private final ToolArgumentValidator validator = new ToolArgumentValidator();
	private final CheckpointStore checkpointStore;
	private final String checkpointSessionId;
	private final ApprovalGate approvalGate;
	private final LongTermMemory longTermMemory;
	private final boolean parallelToolCalls;

	/**
	 * 用默认参数构造。
	 *
	 * @param client      对话客户端（任意平台 AiClient 实现）
	 * @param baseRequest 基础请求（model / system / temperature 等模板，消息会被复制后追加）
	 * @param registry    工具注册中心
	 */
	public ReActAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry) {
		this(client, baseRequest, registry, new AgentListener() {
		}, DEFAULT_MAX_ITERATIONS, DEFAULT_TIMEOUT);
	}

	/**
	 * 全参构造（不带会话记忆）。
	 *
	 * @param client        对话客户端
	 * @param baseRequest   基础请求模板
	 * @param registry      工具注册中心
	 * @param listener      事件回调（null 表示空监听）
	 * @param maxIterations 最大迭代轮数（≥1）
	 * @param timeout       总超时（正时长）
	 */
	public ReActAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout) {
		this(client, baseRequest, registry, listener, maxIterations, timeout, null);
	}

	/**
	 * 全参构造（带可选会话记忆）。
	 *
	 * @param client        对话客户端
	 * @param baseRequest   基础请求模板
	 * @param registry      工具注册中心
	 * @param listener      事件回调（null 表示空监听）
	 * @param maxIterations 最大迭代轮数（≥1）
	 * @param timeout       总超时（正时长）
	 * @param memory        会话记忆（null 表示不启用多轮记忆）
	 */
	public ReActAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout,
			ConversationMemory memory) {
		this(client, baseRequest, registry, listener, maxIterations, timeout, memory, null, null);
	}

	/**
	 * 全参构造（带可选会话记忆与可选检查点持久化）。
	 *
	 * <p>当 {@code checkpointStore} 与 {@code sessionId} 均非 null 时，run 会在开始、
	 * 每轮迭代后与结束时自动落盘检查点；任一为 null 则不启用，行为与历史版本一致。</p>
	 *
	 * @param client            对话客户端
	 * @param baseRequest       基础请求模板
	 * @param registry          工具注册中心
	 * @param listener          事件回调（null 表示空监听）
	 * @param maxIterations     最大迭代轮数（≥1）
	 * @param timeout           总超时（正时长）
	 * @param memory            会话记忆（null 表示不启用多轮记忆）
	 * @param checkpointStore   检查点存储（null 表示不持久化）
	 * @param sessionId         检查点会话标识（null 表示不持久化）
	 */
	public ReActAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout,
			ConversationMemory memory, CheckpointStore checkpointStore, String sessionId) {
		this(client, baseRequest, registry, listener, maxIterations, timeout, memory,
			checkpointStore, sessionId, null, null);
	}

	/**
	 * 全参构造（带可选会话记忆、检查点持久化、HITL 审批与长期记忆）。
	 *
	 * <p>本构造器为 1.7.0 新增；其余既有构造器均委托至此。所有“可选”能力传 null
	 * 即关闭，关闭后行为与历史版本逐字节一致。</p>
	 *
	 * @param client            对话客户端
	 * @param baseRequest       基础请求模板
	 * @param registry          工具注册中心
	 * @param listener          事件回调（null 表示空监听）
	 * @param maxIterations     最大迭代轮数（≥1）
	 * @param timeout           总超时（正时长）
	 * @param memory            会话记忆（null 表示不启用多轮记忆）
	 * @param checkpointStore   检查点存储（null 表示不持久化）
	 * @param sessionId         会话标识（检查点落盘 + 长期记忆提取的 sessionId）
	 * @param approvalGate      HITL 审批门（null 表示不审批）
	 * @param longTermMemory    长期记忆（null 表示不跨会话记忆）
	 */
	public ReActAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout,
			ConversationMemory memory, CheckpointStore checkpointStore, String sessionId,
			ApprovalGate approvalGate, LongTermMemory longTermMemory) {
		this(client, baseRequest, registry, listener, maxIterations, timeout, memory,
			checkpointStore, sessionId, approvalGate, longTermMemory, true);
	}

	/**
	 * 规范全参构造（2.3.0 新增并行开关）。
	 *
	 * <p>本构造器为 private，仅供既有公开构造器与 {@link #withParallelToolCalls(boolean)} /
	 * {@link #withMemory(ConversationMemory)} 派生方法内部使用；外部调用方一律走既有公开
	 * 构造器（默认开启并行），行为对旧调用方逐字节兼容。</p>
	 *
	 * @param client            对话客户端
	 * @param baseRequest       基础请求模板
	 * @param registry          工具注册中心
	 * @param listener          事件回调（null 表示空监听）
	 * @param maxIterations     最大迭代轮数（≥1）
	 * @param timeout           总超时（正时长）
	 * @param memory            会话记忆（null 表示不启用多轮记忆）
	 * @param checkpointStore   检查点存储（null 表示不持久化）
	 * @param sessionId         会话标识（检查点落盘 + 长期记忆提取的 sessionId）
	 * @param approvalGate      HITL 审批门（null 表示不审批）
	 * @param longTermMemory    长期记忆（null 表示不跨会话记忆）
	 * @param parallelToolCalls 单轮多 tool_calls 是否并发执行（false 回退串行，结果顺序不变）
	 */
	private ReActAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout,
			ConversationMemory memory, CheckpointStore checkpointStore, String sessionId,
			ApprovalGate approvalGate, LongTermMemory longTermMemory,
			boolean parallelToolCalls) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(baseRequest, "baseRequest must not be null");
		Assert.notNull(registry, "registry must not be null");
		Assert.isTrue(maxIterations >= 1, "maxIterations must be >= 1");
		Assert.notNull(timeout, "timeout must not be null");
		Assert.isTrue(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive");
		this.client = client;
		this.baseRequest = baseRequest;
		this.registry = registry;
		this.listener = listener == null ? new AgentListener() {
		} : listener;
		this.maxIterations = maxIterations;
		this.timeout = timeout;
		this.memory = memory;
		this.checkpointStore = checkpointStore;
		this.checkpointSessionId = sessionId;
		this.approvalGate = approvalGate;
		this.longTermMemory = longTermMemory;
		this.parallelToolCalls = parallelToolCalls;
	}

	/**
	 * baseRequest 模板消息快照（供检查点创建/恢复使用）。
	 *
	 * @return 不可修改的模板消息列表
	 */
	public List<ChatMessage> baseRequestMessages() {
		return List.copyOf(this.baseRequest.messages());
	}

	/**
	 * 当前注入的会话记忆（可能为 null）。
	 *
	 * @return 记忆，或 null
	 */
	public ConversationMemory conversationMemory() {
		return this.memory;
	}

	/**
	 * 以新的会话记忆派生一个配置相同的新编排器（供检查点恢复使用）。
	 *
	 * @param newMemory 新记忆
	 * @return 新的 ReActAgent（其余配置与本实例一致）
	 */
	public ReActAgent withMemory(ConversationMemory newMemory) {
		return new ReActAgent(this.client, this.baseRequest, this.registry, this.listener,
			this.maxIterations, this.timeout, newMemory, this.checkpointStore,
			this.checkpointSessionId, this.approvalGate, this.longTermMemory,
			this.parallelToolCalls);
	}

	/**
	 * 当前是否开启单轮多 tool_calls 并发执行。
	 *
	 * @return true 表示并行（默认），false 表示串行
	 * @since 2.3.0
	 */
	public boolean parallelToolCalls() {
		return this.parallelToolCalls;
	}

	/**
	 * 以新的并行工具调用开关派生一个配置相同的新编排器。
	 *
	 * <p>默认开启并行；传入 {@code false} 可回退为历史的串行执行行为（结果回填顺序不变），
	 * 便于在工具非线程安全或需要严格可重现时序时使用。</p>
	 *
	 * @param enabled 是否并发执行单轮内的多个工具调用
	 * @return 新的 ReActAgent（其余配置与本实例一致）
	 * @since 2.3.0
	 */
	public ReActAgent withParallelToolCalls(boolean enabled) {
		return new ReActAgent(this.client, this.baseRequest, this.registry, this.listener,
			this.maxIterations, this.timeout, this.memory, this.checkpointStore,
			this.checkpointSessionId, this.approvalGate, this.longTermMemory, enabled);
	}

	/**
	 * 当前注入的 HITL 审批门（可能为 null）。
	 *
	 * @return 审批门，或 null
	 */
	public ApprovalGate approvalGate() {
		return this.approvalGate;
	}

	/**
	 * 当前注入的长期记忆（可能为 null）。
	 *
	 * @return 长期记忆，或 null
	 */
	public LongTermMemory longTermMemory() {
		return this.longTermMemory;
	}

	/**
	 * 以 baseRequest 已有消息为起点运行编排（不追加新用户消息）。
	 *
	 * @return 最终答案
	 */
	public String run() {
		return run(null);
	}

	/**
	 * 追加一条用户消息后运行编排。
	 *
	 * @param userMessage 用户消息（null 表示不追加）
	 * @return 最终答案
	 */
	public String run(String userMessage) {
		List<ChatMessage> history = new ArrayList<>(this.baseRequest.messages());
		// 长期记忆召回：把相关历史事实作为 system 消息注入模板之后
		List<MemoryEntry> recalled = recallLongTerm(userMessage);
		if (!recalled.isEmpty()) {
			history.add(buildRecallMessage(recalled));
		}
		if (this.memory != null) {
			history.addAll(this.memory.history());
		}
		boolean hasUserMessage = userMessage != null && !userMessage.isBlank();
		if (hasUserMessage) {
			history.add(ChatMessage.user(userMessage));
		}
		long deadline = System.nanoTime() + this.timeout.toNanos();
		long checkpointCreated = System.currentTimeMillis();
		saveCheckpoint(history, 0, null, checkpointCreated);

		String finalAnswer;
		// 无工具：退化为普通单次 chat
		if (this.registry.isEmpty()) {
			ChatRequest req = buildRequest(history);
			ChatResponse resp = this.client.chat(req);
			finalAnswer = firstText(resp);
			saveCheckpoint(history, 0, finalAnswer, checkpointCreated);
		} else {
			finalAnswer = runLoop(history, deadline, checkpointCreated);
		}

		this.listener.onFinish(finalAnswer);

		if (this.memory != null) {
			if (hasUserMessage) {
				this.memory.add(ChatMessage.user(userMessage));
			}
			this.memory.add(ChatMessage.assistant(finalAnswer));
		}
		// 长期记忆沉淀：把本轮用户问题与最终答案交给提取器
		if (this.longTermMemory != null) {
			List<ChatMessage> turn = new ArrayList<>();
			if (hasUserMessage) {
				turn.add(ChatMessage.user(userMessage));
			}
			turn.add(ChatMessage.assistant(finalAnswer));
			this.longTermMemory.remember(turn, this.checkpointSessionId);
		}
		return finalAnswer;
	}

	/**
	 * 长期记忆召回（未启用时返回空列表）。
	 */
	private List<MemoryEntry> recallLongTerm(String userMessage) {
		if (this.longTermMemory == null || userMessage == null || userMessage.isBlank()) {
			return List.of();
		}
		return this.longTermMemory.recall(userMessage, DEFAULT_RECALL_K);
	}

	/**
	 * 把召回的记忆条目拼成一条 system 提示消息。
	 */
	private static ChatMessage buildRecallMessage(List<MemoryEntry> recalled) {
		StringBuilder sb = new StringBuilder("相关长期记忆：");
		for (MemoryEntry e : recalled) {
			sb.append("\n- ").append(e.content());
		}
		return ChatMessage.system(sb.toString());
	}

	/**
	 * ReAct 工具调用主循环：Thought → Action → Observation 直到模型给出文本答案。
	 *
	 * <p>启用检查点时，每轮工具执行后保存中间快照；模型给出文本答案时保存最终快照。
	 * 达到最大迭代抛异常前不保存最终快照（文档约定）。</p>
	 */
	private String runLoop(List<ChatMessage> history, long deadline, long checkpointCreated) {
		for (int iter = 1; iter <= this.maxIterations; iter++) {
			checkTimeout(deadline);
			ChatResponse response = this.client.chat(buildRequest(history));
			ChatMessage message = firstMessage(response);
			List<ToolCall> toolCalls = message == null ? null : message.toolCalls();

			if (toolCalls == null || toolCalls.isEmpty()) {
				String answer = message == null ? "" : message.content();
				saveCheckpoint(history, iter - 1, answer, checkpointCreated);
				return answer;
			}

			// 助手工具调用消息 + 每个 tool 结果消息追加进历史
			history.add(ChatMessage.assistant(toolCalls));
			List<String> results = executeTools(toolCalls, deadline);
			for (int i = 0; i < toolCalls.size(); i++) {
				history.add(ChatMessage.tool(toolCalls.get(i).id(), results.get(i)));
			}
			saveCheckpoint(history, iter, null, checkpointCreated);
		}

		throw new AiException("ReAct agent exceeded max iterations: " + this.maxIterations);
	}

	/**
	 * 执行一轮内的全部工具调用，结果按 tool_call 顺序返回。
	 *
	 * <p>开启并行（默认）且调用数 &gt; 1 时，以虚拟线程并发执行，分波控制在
	 * {@value #DEFAULT_MAX_PARALLEL_TOOL_CALLS} 路以内；结果先按下标收集到数组，
	 * 再由调用线程统一追加历史——工作线程全程不触碰共享历史，保证并发安全。
	 * 等待预算复用总超时 {@code deadline} 的剩余时长。</p>
	 *
	 * <p>失败隔离：单个工具抛异常 / 超时都被收敛为错误文本回填，不影响其余工具继续执行，
	 * 主循环照常推进。关闭并行或仅单个调用时走历史串行路径，行为与旧版逐字节一致。</p>
	 *
	 * @param calls    模型本轮给出的工具调用（顺序即回填顺序）
	 * @param deadline  总超时截止时刻（nanoTime）
	 * @return 与 calls 等长、同序的结果文本列表
	 */
	private List<String> executeTools(List<ToolCall> calls, long deadline) {
		if (!this.parallelToolCalls || calls.size() == 1) {
			List<String> serial = new ArrayList<>(calls.size());
			for (ToolCall call : calls) {
				serial.add(safeExecute(call));
			}
			return serial;
		}

		String[] results = new String[calls.size()];
		for (int start = 0; start < calls.size(); start += DEFAULT_MAX_PARALLEL_TOOL_CALLS) {
			int end = Math.min(start + DEFAULT_MAX_PARALLEL_TOOL_CALLS, calls.size());
			List<CompletableFuture<String>> futures = new ArrayList<>(end - start);
			for (int i = start; i < end; i++) {
				final ToolCall call = calls.get(i);
				futures.add(CompletableFuture.supplyAsync(() -> safeExecute(call),
					AsyncExecutors.virtualThreadExecutor()));
			}
			for (int j = start; j < end; j++) {
				CompletableFuture<String> future = futures.get(j - start);
				long remaining = deadline - System.nanoTime();
				try {
					results[j] = future.get(remaining > 0 ? remaining : 0L,
						TimeUnit.NANOSECONDS);
				} catch (TimeoutException te) {
					future.cancel(true);
					results[j] = "工具执行超时 " + calls.get(j).name();
				} catch (Exception e) {
					// safeExecute 理论上已吞掉全部异常；这里兜底，保证失败隔离
					this.listener.onError(e);
					results[j] = "工具执行异常 " + calls.get(j).name() + ": " + e.getMessage();
				}
			}
		}
		return List.of(results);
	}

	/**
	 * 执行单个工具调用的安全包装：把任何（含事件监听器回调抛出的）异常收敛为错误文本，
	 * 保证并行场景下单工具失败绝不扩散到其他工具或主循环。
	 *
	 * @param call 工具调用
	 * @return 回灌模型的文本（成功输出或错误信息）
	 */
	private String safeExecute(ToolCall call) {
		try {
			return executeTool(call);
		} catch (Throwable t) {
			try {
				this.listener.onError(t);
			} catch (RuntimeException ignored) {
				// 监听器自身异常不再向外扩散
			}
			return "工具执行异常 " + call.name() + ": " + t.getMessage();
		}
	}

	/**
	 * 启用检查点时把当前状态落盘；未启用时静默跳过。
	 */
	private void saveCheckpoint(List<ChatMessage> history, int iteration,
			String finalAnswer, long createdAt) {
		if (this.checkpointStore == null || this.checkpointSessionId == null) {
			return;
		}
		AgentCheckpoint checkpoint = new AgentCheckpoint(this.checkpointSessionId, history,
			iteration, finalAnswer, createdAt, Json.object());
		this.checkpointStore.save(checkpoint);
	}

	/**
	 * 执行单个工具调用，返回回灌模型的文本（成功输出或错误信息）。
	 */
	private String executeTool(ToolCall call) {
		this.listener.onToolCall(call);

		JsonObject args = parseArguments(call);
		if (args == null) {
			String err = "arguments 不是合法 JSON 对象: " + call.argumentsJson();
			this.listener.onToolResult(call, err);
			return err;
		}

		// 参数校验
		var fn = this.registry.getFunction(call.name());
		if (fn.isPresent()) {
			ToolArgumentValidator.ValidationResult vr = this.validator.validate(fn.get(), args);
			if (!vr.valid()) {
				String err = "参数校验失败: " + String.join("; ", vr.errors());
				this.listener.onToolResult(call, err);
				return err;
			}
		}

		// 查 handler
		var handler = this.registry.getHandler(call.name());
		if (handler.isEmpty()) {
			String err = "tool not found: " + call.name();
			this.listener.onToolResult(call, err);
			return err;
		}

		// HITL 审批（可选）：策略命中时阻塞等待人工决定；拒绝/超时不执行工具
		if (this.approvalGate != null
				&& this.approvalGate.requiresApproval(call.name(), args)) {
			ApprovalRequest request = ApprovalRequest.of(this.checkpointSessionId,
				call.name(), args, "调用工具 " + call.name(), false);
			ApprovalDecision decision = this.approvalGate.request(request);
			if (decision.status() == ApprovalStatus.REJECTED) {
				String text = "用户拒绝执行工具 " + call.name()
					+ (decision.reason() != null && !decision.reason().isBlank()
						? "：" + decision.reason() : "");
				this.listener.onToolResult(call, text);
				return text;
			}
			if (decision.status() == ApprovalStatus.TIMEOUT) {
				String text = "审批超时，跳过工具 " + call.name();
				this.listener.onToolResult(call, text);
				return text;
			}
			// APPROVED：继续执行
		}

		// 执行
		try {
			String output = handler.get().execute(args);
			this.listener.onToolResult(call, output);
			return output;
		} catch (Exception e) {
			this.listener.onError(e);
			String err = "工具执行异常 " + call.name() + ": " + e.getMessage();
			this.listener.onToolResult(call, err);
			return err;
		}
	}

	/**
	 * 解析 argumentsJson 为 JsonObject；非法时返回 null。
	 */
	private JsonObject parseArguments(ToolCall call) {
		String json = call.argumentsJson();
		if (json == null || json.isBlank()) {
			return Json.object();
		}
		try {
			JsonElement el = Json.parse(json);
			if (el == null || el.isNull()) {
				return Json.object();
			}
			if (!el.isObject()) {
				return null;
			}
			return el.getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 基于 baseRequest 模板构建单轮请求，注入最新历史与工具列表。
	 */
	private ChatRequest buildRequest(List<ChatMessage> history) {
		ChatRequest.Builder b = ChatRequest.builder()
			.model(this.baseRequest.model())
			.messages(new ArrayList<>(history))
			.tools(this.registry.getToolSpecs());
		if (this.baseRequest.temperature() != null) {
			b.temperature(this.baseRequest.temperature());
		}
		if (this.baseRequest.maxTokens() != null) {
			b.maxTokens(this.baseRequest.maxTokens());
		}
		if (this.baseRequest.topP() != null) {
			b.topP(this.baseRequest.topP());
		}
		if (this.baseRequest.toolChoice() != null) {
			b.toolChoice(this.baseRequest.toolChoice());
		}
		if (this.baseRequest.presencePenalty() != null) {
			b.presencePenalty(this.baseRequest.presencePenalty());
		}
		if (this.baseRequest.frequencyPenalty() != null) {
			b.frequencyPenalty(this.baseRequest.frequencyPenalty());
		}
		if (this.baseRequest.seed() != null) {
			b.seed(this.baseRequest.seed());
		}
		if (this.baseRequest.user() != null) {
			b.user(this.baseRequest.user());
		}
		return b.build();
	}

	/**
	 * 取第一条候选消息。
	 */
	private ChatMessage firstMessage(ChatResponse response) {
		if (response == null || response.choices().isEmpty()) {
			return null;
		}
		Choice c = response.choices().get(0);
		return c == null ? null : c.message();
	}

	/**
	 * 取第一条候选文本。
	 */
	private String firstText(ChatResponse response) {
		ChatMessage m = firstMessage(response);
		return m == null ? "" : m.content();
	}

	/**
	 * 超时检查。
	 */
	private void checkTimeout(long deadlineNanos) {
		if (System.nanoTime() > deadlineNanos) {
			throw new AiTimeoutException("ReAct agent exceeded total timeout: " + this.timeout);
		}
	}
}
