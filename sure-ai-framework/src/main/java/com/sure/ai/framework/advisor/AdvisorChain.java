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
import java.util.function.Function;

import com.sure.ai.model.ChatResponse;

/**
 * 有序 Advisor 链：把 {@link Advisor} 列表按注册顺序串成「正序 before → 嵌套 around
 * → 逆序 after」的执行管线。
 *
 * <p>链对 advisor 暴露的 {@code proceed} 视图<b>不可重排、不可复用整链</b>——它只代表
 * 「从当前 advisor 之后到终端」的剩余管线。因此 ToolCallingAdvisor / 校验 advisor 可以
 * 反复调用 {@link #proceed(AdvisorContext)} 触发多轮模型调用，而不会错误地把外层
 * before/after 再跑一遍。</p>
 *
 * <p>本类由框架在每次调用时构建，不对外暴露构建器；用户只需向
 * {@code FrameworkUtil.builder().advisors(...)} 传有序列表。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class AdvisorChain {

	/** 全量有序 advisor。 */
	private final List<Advisor> advisors;

	/** 本视图从第几个 advisor 开始。 */
	private final int start;

	/** 终端：链尾的真实对话调用（client.chat(rebuildRequest())）。 */
	private final Function<AdvisorContext, ChatResponse> terminal;

	/**
	 * 由框架构造完整链。
	 *
	 * @param advisors  有序 advisor（注册顺序即执行顺序）
	 * @param terminal   终端对话调用
	 */
	public AdvisorChain(List<Advisor> advisors, Function<AdvisorContext, ChatResponse> terminal) {
		this(advisors, 0, terminal);
	}

	/** 内部构造：派生一个从 {@code start} 开始的剩余视图。 */
	private AdvisorChain(List<Advisor> advisors, int start, Function<AdvisorContext, ChatResponse> terminal) {
		this.advisors = List.copyOf(advisors);
		this.start = start;
		this.terminal = terminal;
	}

	/**
	 * 执行整条链：正序 before → 嵌套 around（终端为 {@code client.chat}）→ 逆序 after。
	 *
	 * <p>无论终端正常返回、around 短路还是抛异常，after 都会逆序执行。
	 * 抛出的异常在 after 执行完毕后继续向上传播。</p>
	 *
	 * @param ctx 链上下文
	 * @return 最终响应
	 */
	public ChatResponse execute(AdvisorContext ctx) {
		for (Advisor a : this.advisors) {
			a.before(ctx);
		}
		try {
			ChatResponse resp = proceed(ctx);
			ctx.response(resp);
			return resp;
		} finally {
			for (int i = this.advisors.size() - 1; i >= 0; i--) {
				this.advisors.get(i).after(ctx);
			}
		}
	}

	/**
	 * 推进到链的下一个 advisor；到达末尾则调用终端。
	 *
	 * <p>可被 around 反复调用（工具循环 / 校验重试），每次都重新执行剩余 advisor 与终端。</p>
	 *
	 * @param ctx 链上下文
	 * @return 剩余管线产生的响应
	 */
	public ChatResponse proceed(AdvisorContext ctx) {
		if (this.start >= this.advisors.size()) {
			return this.terminal.apply(ctx);
		}
		Advisor next = this.advisors.get(this.start);
		AdvisorChain rest = new AdvisorChain(this.advisors, this.start + 1, this.terminal);
		return next.around(rest, ctx);
	}
}
