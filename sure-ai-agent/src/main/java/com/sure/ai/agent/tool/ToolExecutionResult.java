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

package com.sure.ai.agent.tool;

/**
 * 工具执行结果封装：统一承载成功输出与失败原因，供编排器回灌模型。
 *
 * <p>无论是 handler 正常返回、参数校验失败还是 handler 抛异常，
 * 编排器都把对应文本作为 {@code tool} 角色消息回灌模型，让模型有机会自我修正。</p>
 *
 * @param success 是否成功
 * @param output  成功时的输出文本（失败时为 null）
 * @param error   失败时的错误信息（成功时为 null）
 * @author sureai
 * @since 0.3.0
 */
public record ToolExecutionResult(boolean success, String output, String error) {

	/**
	 * 构造器（紧凑形式，校验不变量）。
	 */
	public ToolExecutionResult {
		if (success && error != null) {
			throw new IllegalArgumentException("success result must not carry error");
		}
		if (!success && output != null) {
			throw new IllegalArgumentException("failure result must not carry output");
		}
	}

	/**
	 * 成功结果。
	 *
	 * @param output 工具输出文本
	 * @return 成功结果
	 */
	public static ToolExecutionResult success(String output) {
		return new ToolExecutionResult(true, output, null);
	}

	/**
	 * 失败结果。
	 *
	 * @param error 错误信息（回灌模型）
	 * @return 失败结果
	 */
	public static ToolExecutionResult failure(String error) {
		return new ToolExecutionResult(false, null, error);
	}

	/**
	 * 回灌模型的文本：成功取 output，失败取 error。
	 *
	 * @return 回灌文本
	 */
	public String payload() {
		return success ? output : error;
	}
}
