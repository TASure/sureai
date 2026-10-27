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

package com.sure.ai.otel.langfuse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Langfuse Ingestion 批次发送抽象（便于测试时替换为录制桩）。
 *
 * <p>每个元素是一个已序列化好的 Langfuse 事件信封 JSON 对象字符串（不含外层数组与 {@code batch}
 * 包裹）；实现负责把它们组装为 {@code {"batch":[...]}} 并 POST 到 {@code /api/public/ingestion}。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
@FunctionalInterface
interface LangfuseBatchSender {

	/**
	 * 发送一批事件（实现内部应异步、吞异常、不向上抛出）。
	 *
	 * @param events 事件信封 JSON 字符串列表
	 * @return 完成句柄（异常已被实现内部记录并吞咽）
	 */
	CompletableFuture<Void> send(List<String> events);

	/** @return 空操作发送器（未配置密钥时占位）。 */
	static LangfuseBatchSender noop() {
		return events -> CompletableFuture.completedFuture(null);
	}
}
