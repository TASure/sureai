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

package com.sure.ai.agent;

import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ToolFunction;

/**
 * Agent 静态便捷入口工具类（参照 RagUtil 风格）。
 *
 * <p>持有一个进程级全局 {@link ToolRegistry}，通过双检锁懒加载；
 * 适合简单脚本 / Demo 场景。复杂场景（多 Agent、隔离工具集、并发注册）建议直接
 * {@code new ToolRegistry()} 显式管理生命周期。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 *   AgentUtil.registerTool(ToolFunction.of("get_weather", "查天气", schema),
 *           args -&gt; "晴 26℃");
 *   ReActAgent agent = AgentUtil.react(client, baseRequest);
 *   String answer = agent.run("西安天气如何？");
 * </pre>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class AgentUtil {

	/** 全局注册中心容器（封装 DCL 懒加载）。 */
	private static final SingletonHolder<ToolRegistry> HOLDER =
		new SingletonHolder<>(ToolRegistry::new);

	private AgentUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 获取全局工具注册中心（懒加载）。
	 *
	 * @return 全局注册中心
	 */
	public static ToolRegistry registry() {
		return HOLDER.get();
	}

	/**
	 * 向全局注册中心注册工具。
	 *
	 * @param function 工具函数定义
	 * @param handler  执行处理器
	 */
	public static void registerTool(ToolFunction function, ToolHandler handler) {
		registry().register(function, handler);
	}

	/**
	 * 用全局注册中心创建 ReActAgent（默认参数）。
	 *
	 * @param client      对话客户端
	 * @param baseRequest 基础请求模板
	 * @return ReActAgent
	 */
	public static ReActAgent react(AiClient client, ChatRequest baseRequest) {
		return new ReActAgent(client, baseRequest, registry());
	}

	/**
	 * 用全局注册中心创建 ReActAgent（带事件监听）。
	 *
	 * @param client   对话客户端
	 * @param baseRequest 基础请求模板
	 * @param listener 事件回调
	 * @return ReActAgent
	 */
	public static ReActAgent react(AiClient client, ChatRequest baseRequest, AgentListener listener) {
		return new ReActAgent(client, baseRequest, registry(), listener,
				ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT);
	}

	/**
	 * 重置全局注册中心（清空所有工具，主要用于测试隔离）。
	 */
	public static void resetRegistry() {
		HOLDER.set(new ToolRegistry());
	}
}
