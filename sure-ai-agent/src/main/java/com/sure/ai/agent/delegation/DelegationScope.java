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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 委派调用链的线程上下文（包内内部实现）。
 *
 * <p>ReActAgent 调用 {@code ToolHandler.execute(args)} 时不附带父委派上下文，
 * 因此当前调用链（祖先 Agent id 栈、chainId）由本类以<b>线程局部</b>方式维护：
 * 委派进入时 {@link #push(String)}、退出时 {@link #pop()}（try-finally 保证回滚）。</p>
 *
 * <p><b>线程安全边界：</b>每个线程（含每条虚拟线程）持有独立链；同一委派分支在
 * 执行线程上同步深度优先，栈在该线程内正确累积。多线程并发发起的顶层委派互不干扰。
 * 不使用 ScopedValue（避免为其开启预览编译参数），也不使用全局可变静态状态。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
final class DelegationScope {

	/** 当前线程上的祖先 Agent id 栈（栈顶 = 最近父）。 */
	private static final ThreadLocal<Deque<String>> CHAIN =
		ThreadLocal.withInitial(ArrayDeque::new);

	/** 与 CHAIN 平行的委派深度天花板栈（逐层取 min 收紧，栈顶 = 当前生效上限）。 */
	private static final ThreadLocal<Deque<Integer>> CEILING =
		ThreadLocal.withInitial(ArrayDeque::new);

	/** 当前线程的调用链标识；链清空时一并清除。 */
	private static final ThreadLocal<String> CHAIN_ID = new ThreadLocal<>();

	private DelegationScope() {
	}

	/**
	 * 当前链上是否已包含指定 Agent id（环检测）。
	 *
	 * @param agentId 待查标识
	 * @return true 表示已在链上
	 */
	static boolean contains(String agentId) {
		return CHAIN.get().contains(agentId);
	}

	/**
	 * 当前链长度（即当前深度：根委派进入前为 0）。
	 *
	 * @return 栈深
	 */
	static int depth() {
		return CHAIN.get().size();
	}

	/**
	 * 当前祖先 id 不可变快照（自根到栈底顺序，不含本次待压入节点）。
	 *
	 * @return id 列表
	 */
	static List<String> ancestorSnapshot() {
		return new ArrayList<>(CHAIN.get());
	}

	/**
	 * 当前调用链标识；链为空（顶层）时惰性生成新 UUID。
	 *
	 * @return chainId
	 */
	static String chainId() {
		String id = CHAIN_ID.get();
		if (id == null) {
			id = UUID.randomUUID().toString();
			CHAIN_ID.set(id);
		}
		return id;
	}

	/**
	 * 当前生效的委派深度天花板（栈空时为无穷大）。
	 *
	 * @return 天花板深度
	 */
	static int ceiling() {
		Deque<Integer> stack = CEILING.get();
		return stack.isEmpty() ? Integer.MAX_VALUE : stack.peek();
	}

	/**
	 * 压入一个被委派 Agent id，并把深度天花板与本层上限取 min 收紧。
	 *
	 * @param agentId  子 Agent 标识
	 * @param maxDepth 本层声明的深度上限
	 */
	static void push(String agentId, int maxDepth) {
		CHAIN.get().push(agentId);
		int parent = CEILING.get().isEmpty() ? Integer.MAX_VALUE : CEILING.get().peek();
		CEILING.get().push(Math.min(parent, maxDepth));
	}

	/**
	 * 弹出栈顶；链清空时清除 chainId，避免线程复用时串链。
	 */
	static void pop() {
		CHAIN.get().poll();
		CEILING.get().poll();
		if (CHAIN.get().isEmpty()) {
			CHAIN_ID.remove();
		}
	}
}
