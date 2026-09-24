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

package com.sure.ai.agent.plan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.memory.ConversationMemory;
import com.sure.ai.agent.tool.ToolArgumentValidator;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;
import com.sure.ai.model.ToolCall;
import com.sure.tool.lang.Assert;

/**
 * Plan-and-Execute 编排器：先全局规划，再分步执行，最后汇总。
 *
 * <p>相比 ReAct 的「边想边做」，本编排器把任务拆为三个阶段：</p>
 * <ol>
 *   <li><b>Planning</b>：要求模型返回 JSON 数组
 *       {@code [{"step":"...","description":"..."}]}（附带
 *       {@code responseFormat("json_object")}）；解析顺序为：严格 JSON 数组 →
 *       非严格文本按行兜底 → 全部失败回退为单步「直接回答」。</li>
 *   <li><b>Execution</b>：逐步骤执行，把已完成步骤结果作为上下文注入；registry
 *       非空时复用 ReAct 风格的单步工具循环（每步最多
 *       {@value #MAX_TOOL_CALLS_PER_STEP} 轮工具调用），为空时直接 chat。单步异常
 *       会回灌错误后重试一次，仍失败则记录错误并继续下一步，不中断整体。</li>
 *   <li><b>汇总</b>：把各步骤结果拼接为上下文，要求模型产出最终综合答案。</li>
 * </ol>
 *
 * <p>防护：步骤数超过 {@code maxIterations}（复用为 maxSteps）抛
 * {@link AiException}；总耗时超过 {@code timeout} 抛 {@link AiTimeoutException}。</p>
 *
 * <p><b>会话记忆（可选）：</b>注入 {@link ConversationMemory} 后，规划 / 执行 /
 * 汇总各阶段都会把 {@code memory.history()} 注入 baseRequest 模板消息之后；
 * 一轮结束后把当轮用户消息与最终答案追加进记忆。不注入时行为与无记忆一致。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class PlanExecuteAgent {

	/** 默认最大步骤数（复用 maxIterations）。 */
	public static final int DEFAULT_MAX_STEPS = 10;

	/** 默认总超时。 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

	/** 单步内最大工具调用轮数。 */
	public static final int MAX_TOOL_CALLS_PER_STEP = 3;

	private final AiClient client;
	private final ChatRequest baseRequest;
	private final ToolRegistry registry;
	private final AgentListener listener;
	private final int maxIterations;
	private final Duration timeout;
	private final ConversationMemory memory;
	private final ToolArgumentValidator validator = new ToolArgumentValidator();

	/**
	 * 用默认参数构造。
	 *
	 * @param client      对话客户端
	 * @param baseRequest 基础请求模板
	 * @param registry    工具注册中心
	 */
	public PlanExecuteAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry) {
		this(client, baseRequest, registry, null, DEFAULT_MAX_STEPS, DEFAULT_TIMEOUT);
	}

	/**
	 * 全参构造（不带会话记忆）。
	 *
	 * @param client        对话客户端
	 * @param baseRequest   基础请求模板
	 * @param registry      工具注册中心
	 * @param listener      事件回调（null 表示空监听）
	 * @param maxIterations 最大步骤数（≥1，复用为 maxSteps）
	 * @param timeout       总超时（正时长）
	 */
	public PlanExecuteAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
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
	 * @param maxIterations 最大步骤数（≥1，复用为 maxSteps）
	 * @param timeout       总超时（正时长）
	 * @param memory        会话记忆（null 表示不启用多轮记忆）
	 */
	public PlanExecuteAgent(AiClient client, ChatRequest baseRequest, ToolRegistry registry,
			AgentListener listener, int maxIterations, Duration timeout,
			ConversationMemory memory) {
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
	}

	/**
	 * 以 baseRequest 已有消息为起点运行编排。
	 *
	 * @return 最终答案
	 */
	public String run() {
		return run(null);
	}

	/**
	 * 运行完整的 Plan-and-Execute 流程。
	 *
	 * @param userMessage 用户任务（null 表示不追加，依赖 baseRequest 已有消息）
	 * @return 最终综合答案
	 */
	public String run(String userMessage) {
		Assert.notNull(this.client, "client must not be null");
		boolean hasUserMessage = userMessage != null && !userMessage.isBlank();
		long deadline = System.nanoTime() + this.timeout.toNanos();

		// 1. Planning
		List<ChatMessage> planMsgs = baseMsgs();
		planMsgs.add(ChatMessage.user(planningPrompt(userMessage)));
		ChatResponse planResp = this.client.chat(buildRequest(planMsgs, false, "json_object"));
		String planText = firstText(planResp);
		this.listener.onThought(planText);

		List<String> steps = parsePlanSteps(planText, userMessage);
		if (steps.size() > this.maxIterations) {
			throw new AiException("Plan has " + steps.size()
					+ " steps, exceeding maxSteps=" + this.maxIterations);
		}
		this.listener.onPlanGenerated(steps);

		// 2. Execution
		List<String> stepResults = new ArrayList<>();
		for (int i = 0; i < steps.size(); i++) {
			checkTimeout(deadline);
			String result = executeStep(steps.get(i), i, steps, stepResults, deadline);
			stepResults.add(result);
		}

		// 3. Summary
		checkTimeout(deadline);
		List<ChatMessage> sumMsgs = baseMsgs();
		sumMsgs.add(ChatMessage.user(summaryPrompt(userMessage, steps, stepResults)));
		ChatResponse sumResp = this.client.chat(buildRequest(sumMsgs, false, null));
		String finalAnswer = firstText(sumResp);

		this.listener.onFinish(finalAnswer);

		if (this.memory != null) {
			if (hasUserMessage) {
				this.memory.add(ChatMessage.user(userMessage));
			}
			this.memory.add(ChatMessage.assistant(finalAnswer));
		}
		return finalAnswer;
	}

	/**
	 * 执行单个步骤（含失败重试一次，仍失败则记录错误继续）。
	 */
	private String executeStep(String step, int index, List<String> steps,
			List<String> prevResults, long deadline) {
		this.listener.onStepStart(index, step);
		try {
			String result = doExecuteStep(step, index, prevResults, deadline, null);
			this.listener.onStepComplete(index, result);
			return result;
		} catch (RuntimeException e) {
			this.listener.onError(e);
			try {
				String result = doExecuteStep(step, index, prevResults, deadline,
						"上一次执行出错: " + e.getMessage());
				this.listener.onStepComplete(index, result);
				return result;
			} catch (RuntimeException e2) {
				this.listener.onError(e2);
				String err = "[步骤失败] " + e2.getMessage();
				this.listener.onStepComplete(index, err);
				return err;
			}
		}
	}

	/**
	 * 真正发起一次步骤执行请求（registry 非空时跑单步工具循环）。
	 */
	private String doExecuteStep(String step, int index, List<String> prevResults,
			long deadline, String retryHint) {
		checkTimeout(deadline);
		List<ChatMessage> msgs = baseMsgs();
		StringBuilder ctx = new StringBuilder();
		if (!prevResults.isEmpty()) {
			ctx.append("已完成步骤及其结果：\n");
			for (int j = 0; j < prevResults.size(); j++) {
				ctx.append(j + 1).append(". ").append("步骤").append(j + 1)
					.append(" → ").append(prevResults.get(j)).append('\n');
			}
		}
		ctx.append("请执行第 ").append(index + 1).append(" 步：").append(step)
			.append("\n只输出本步骤的执行结果，不要复述任务。");
		if (retryHint != null) {
			ctx.append("\n注意：").append(retryHint).append("，请修正后重试。");
		}
		msgs.add(ChatMessage.user(ctx.toString()));

		if (this.registry.isEmpty()) {
			ChatResponse resp = this.client.chat(buildRequest(msgs, false, null));
			return firstText(resp);
		}
		return runStepLoop(msgs, deadline);
	}

	/**
	 * 单步工具调用循环（Thought → Action → Observation），直到模型给出文本答案。
	 */
	private String runStepLoop(List<ChatMessage> history, long deadline) {
		for (int round = 1; round <= MAX_TOOL_CALLS_PER_STEP; round++) {
			checkTimeout(deadline);
			ChatResponse resp = this.client.chat(buildRequest(history, true, null));
			ChatMessage msg = firstMessage(resp);
			List<ToolCall> calls = msg == null ? null : msg.toolCalls();
			if (calls == null || calls.isEmpty()) {
				return msg == null ? "" : msg.content();
			}
			history.add(ChatMessage.assistant(calls));
			for (ToolCall call : calls) {
				String r = executeTool(call);
				history.add(ChatMessage.tool(call.id(), r));
			}
		}
		throw new AiException("Step exceeded max tool calls: " + MAX_TOOL_CALLS_PER_STEP);
	}

	/**
	 * 执行单个工具调用，返回回灌模型的文本。
	 */
	private String executeTool(ToolCall call) {
		this.listener.onToolCall(call);

		JsonObject args = parseArguments(call);
		if (args == null) {
			String err = "arguments 不是合法 JSON 对象: " + call.argumentsJson();
			this.listener.onToolResult(call, err);
			return err;
		}

		var fn = this.registry.getFunction(call.name());
		if (fn.isPresent()) {
			ToolArgumentValidator.ValidationResult vr = this.validator.validate(fn.get(), args);
			if (!vr.valid()) {
				String err = "参数校验失败: " + String.join("; ", vr.errors());
				this.listener.onToolResult(call, err);
				return err;
			}
		}

		var handler = this.registry.getHandler(call.name());
		if (handler.isEmpty()) {
			String err = "tool not found: " + call.name();
			this.listener.onToolResult(call, err);
			return err;
		}

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
	 * 解析计划文本为步骤列表：JSON 数组优先，按行兜底，失败回退单步直接回答。
	 */
	private List<String> parsePlanSteps(String planText, String task) {
		List<String> jsonSteps = tryParseJsonArray(planText);
		if (!jsonSteps.isEmpty()) {
			return jsonSteps;
		}
		List<String> lineSteps = tryParseLines(planText);
		if (lineSteps.size() >= 2) {
			return lineSteps;
		}
		String direct = (task != null && !task.isBlank()) ? task : "直接回答";
		return new ArrayList<>(List.of(direct));
	}

	/**
	 * 尝试把响应文本中的 {@code [...]} 解析为 JSON 数组步骤。
	 */
	private List<String> tryParseJsonArray(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		int s = text.indexOf('[');
		int e = text.lastIndexOf(']');
		if (s < 0 || e <= s) {
			return List.of();
		}
		try {
			JsonElement el = Json.parse(text.substring(s, e + 1));
			if (el == null || !el.isArray()) {
				return List.of();
			}
			JsonArray arr = el.getAsJsonArray();
			List<String> steps = new ArrayList<>();
			for (JsonElement item : arr) {
				if (item == null || item.isNull()) {
					continue;
				}
				String t = extractStepText(item);
				if (!t.isBlank()) {
					steps.add(t);
				}
			}
			return steps;
		} catch (RuntimeException ex) {
			return List.of();
		}
	}

	/**
	 * 从 JSON 数组元素提取步骤文本（对象取 step/description，字符串直接取值）。
	 */
	private String extractStepText(JsonElement item) {
		if (item.isObject()) {
			JsonObject obj = item.getAsJsonObject();
			String step = obj.optString("step", "");
			String desc = obj.optString("description", "");
			if (step == null || step.isBlank()) {
				return desc == null ? "" : desc;
			}
			if (desc == null || desc.isBlank()) {
				return step;
			}
			return step + "：" + desc;
		}
		if (item.isString()) {
			return item.getAsString();
		}
		return item.toString();
	}

	/**
	 * 非严格 JSON 兜底：按行切分，过滤空行与编号前缀。
	 */
	private List<String> tryParseLines(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		List<String> steps = new ArrayList<>();
		for (String raw : text.split("\\R")) {
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			line = line.replaceFirst("^\\s*(\\d+\\s*[.、)]|[\\-*\\u2022])\\s*", "");
			if (!line.isBlank()) {
				steps.add(line);
			}
		}
		return steps;
	}

	/**
	 * 构造规划阶段的用户提示。
	 */
	private String planningPrompt(String task) {
		String instr = "请把任务拆解为有序执行步骤。只返回一个 JSON 数组，"
			+ "格式为 [{\"step\":\"步骤标题\",\"description\":\"具体做法\"}]，"
			+ "不要输出多余文字。";
		if (task != null && !task.isBlank()) {
			return instr + "\n\n任务：" + task;
		}
		return instr + "\n\n（任务见上文对话内容）";
	}

	/**
	 * 构造汇总阶段的用户提示。
	 */
	private String summaryPrompt(String task, List<String> steps, List<String> stepResults) {
		StringBuilder sb = new StringBuilder();
		sb.append("任务：").append(task != null && !task.isBlank() ? task : "(见上文对话)")
			.append("\n\n各步骤执行结果：\n");
		for (int j = 0; j < stepResults.size(); j++) {
			sb.append(j + 1).append(". ").append(steps.get(j)).append(" → ")
				.append(stepResults.get(j)).append('\n');
		}
		sb.append("\n请综合以上步骤结果，给出最终答案。");
		return sb.toString();
	}

	/**
	 * 基础消息：baseRequest 模板消息 + 会话记忆历史（若有）。
	 */
	private List<ChatMessage> baseMsgs() {
		List<ChatMessage> msgs = new ArrayList<>(this.baseRequest.messages());
		if (this.memory != null) {
			msgs.addAll(this.memory.history());
		}
		return msgs;
	}

	/**
	 * 基于 baseRequest 模板构建请求，复制常用采样参数。
	 *
	 * @param messages      消息列表
	 * @param withTools     是否附带注册中心工具声明
	 * @param responseFormat 响应格式（null 表示不设置）
	 */
	private ChatRequest buildRequest(List<ChatMessage> messages, boolean withTools,
			Object responseFormat) {
		ChatRequest.Builder b = ChatRequest.builder()
			.model(this.baseRequest.model())
			.messages(new ArrayList<>(messages));
		if (withTools && !this.registry.isEmpty()) {
			b.tools(this.registry.getToolSpecs());
		}
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
		if (responseFormat != null) {
			b.responseFormat(responseFormat);
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
			throw new AiTimeoutException("Plan-Execute agent exceeded total timeout: "
					+ this.timeout);
		}
	}
}
