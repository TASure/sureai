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

import com.sure.ai.model.ChatResponse;

/**
 * 声明式编排中间件：在一次 AiService 代理调用路径上横切请求 / 响应。
 *
 * <p>三钩子语义（以注册顺序 {@code A, B, C} 为例）：</p>
 * <ul>
 *   <li>{@link #before(AdvisorContext)}：<b>正序</b>执行 A → B → C，可改写
 *       {@link AdvisorContext#messages()} 或挂载属性；</li>
 *   <li>{@link #around(AdvisorChain, AdvisorContext)}：<b>嵌套</b>执行——
 *       A 包裹 B，B 包裹 C，C 包裹终端（{@code client.chat}）。默认实现直接
 *       {@code chain.proceed(ctx)} 透传；覆写后可零次调用 proceed（短路返回缓存）、
 *       一次（改写响应）或<b>多次</b>（工具循环 / 校验自纠重试）；</li>
 *   <li>{@link #after(AdvisorContext)}：<b>逆序</b>执行 C → B → A，无论终端成功、
 *       短路还是抛异常都会运行（finally 语义），适合收尾日志。</li>
 * </ul>
 *
 * <p>所有钩子均为 {@code default} 空实现，实现者只需覆写关心的钩子。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public interface Advisor {

	/**
	 * 正序前置钩子：在任何 around / 终端之前运行，可改写请求消息。
	 *
	 * @param ctx 链上下文
	 */
	default void before(AdvisorContext ctx) {
	}

	/**
	 * 环绕钩子：包裹「链的剩余部分 + 终端」。
	 *
	 * <p>默认直接 {@code return chain.proceed(ctx);}。覆写时通过调用
	 * {@code chain.proceed(ctx)} 推进——每次调用都会重新执行内层 advisor 与终端，
	 * 因此工具循环、校验重试这类「多次调用模型」的能力都在此实现。</p>
	 *
	 * @param chain 代表链剩余部分的视图（仅能推进，不可重排）
	 * @param ctx   链上下文
	 * @return 最终响应（短路时自行构造，否则为 proceed 的返回）
	 */
	default ChatResponse around(AdvisorChain chain, AdvisorContext ctx) {
		return chain.proceed(ctx);
	}

	/**
	 * 逆序后置钩子：在终端 / 短路完成后运行（finally 语义）。
	 *
	 * @param ctx 链上下文（此时 {@link AdvisorContext#response()} 已写入）
	 */
	default void after(AdvisorContext ctx) {
	}
}
