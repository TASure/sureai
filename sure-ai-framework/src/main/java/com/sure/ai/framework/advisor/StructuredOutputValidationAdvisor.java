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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.util.JsonMapper;
import com.sure.tool.lang.Assert;

/**
 * 结构化输出校验 + 自纠重试 Advisor。
 *
 * <p>仅在 {@link AdvisorContext#expectedType()} 非空（即声明式接口返回 record、
 * 请求已挂 {@code responseFormat=json_schema}）时介入。对模型首条文本做校验：</p>
 * <ul>
 *   <li>文本必须是合法 JSON 对象；</li>
 *   <li>且能被 {@link JsonMapper#fromJson(JsonObject, Class)} 反序列化为目标 record。</li>
 * </ul>
 *
 * <p>校验失败时，把「上一次输出不合规 + 错误原因」作为一条修正指令消息追加回对话，
 * 再次 {@code chain.proceed(ctx)} 请求模型重写，最多重试 {@code maxRetries} 次；
 * 达上限仍不合规则把最后一次响应原样返回（交由上层解析时抛错）。</p>
 *
 * <p>响应携带 {@code tool_calls}（工具循环的中间轮）时跳过校验——那不是最终结构化输出。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class StructuredOutputValidationAdvisor implements Advisor {

	/** 默认自纠重试次数。 */
	public static final int DEFAULT_MAX_RETRIES = 2;

	private static final Logger LOG = Logger.getLogger(StructuredOutputValidationAdvisor.class.getName());

	private final int maxRetries;

	/**
	 * 默认构造：最多重试 {@value #DEFAULT_MAX_RETRIES} 次。
	 */
	public StructuredOutputValidationAdvisor() {
		this(DEFAULT_MAX_RETRIES);
	}

	/**
	 * 全参构造。
	 *
	 * @param maxRetries 校验失败后最多重试次数（≥0）
	 */
	public StructuredOutputValidationAdvisor(int maxRetries) {
		Assert.isTrue(maxRetries >= 0, "maxRetries 必须 >= 0: " + maxRetries);
		this.maxRetries = maxRetries;
	}

	@Override
	public ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
		Class<?> type = ctx.expectedType();
		if (type == null) {
			return chain.proceed(ctx);
		}
		ChatResponse resp = chain.proceed(ctx);
		String error = validate(resp, type);
		int retries = 0;
		while (error != null && retries < this.maxRetries) {
			retries++;
			LOG.fine("structured output invalid (" + error + "); self-correct retry " + retries);
			ctx.messages().add(ChatMessage.user(buildCorrection(error)));
			resp = chain.proceed(ctx);
			error = validate(resp, type);
		}
		if (error != null) {
			LOG.warning("structured output still invalid after " + retries + " retries: " + error);
		}
		return resp;
	}

	/**
	 * 校验响应；合法返回 {@code null}，否则返回错误描述。
	 * 中间工具轮（带 tool_calls）视为合法，交由工具循环处理。
	 */
	private static String validate(ChatResponse resp, Class<?> type) {
		ChatMessage m = firstMessage(resp);
		if (m != null && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
			return null;
		}
		String text = m == null ? null : m.content();
		if (text == null || text.isBlank()) {
			return "输出为空";
		}
		try {
			JsonElement el = Json.parse(text);
			if (el == null || !el.isObject()) {
				return "输出不是 JSON 对象";
			}
			JsonMapper.fromJson(el.getAsJsonObject(), type);
			return null;
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "structured parse failed", e);
			return e.getMessage();
		}
	}

	/** 构造自纠指令消息。 */
	private static String buildCorrection(String error) {
		return "你上一次的输出不符合要求：" + error
			+ "。请仅输出一个符合 JSON Schema 的 JSON 对象，不要包含任何额外解释或 markdown 代码块标记。";
	}

	/** 取首条候选消息。 */
	private static ChatMessage firstMessage(ChatResponse resp) {
		if (resp == null || resp.choices().isEmpty()) {
			return null;
		}
		return resp.choices().get(0) == null ? null : resp.choices().get(0).message();
	}
}
