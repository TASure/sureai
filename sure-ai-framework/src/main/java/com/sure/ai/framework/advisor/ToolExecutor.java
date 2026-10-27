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

/**
 * 工具执行器 SPI：把模型给出的一次工具调用解析为回灌文本。
 *
 * <p>框架内置 {@code ReflectionToolExecutor}：按工具名找到接口上的 {@code @Tool} 方法，
 * 把 JSON 参数绑定到形参并反射调用（{@code default} 方法经
 * {@link java.lang.reflect.InvocationHandler#invokeDefault} 在代理上执行）。
 * 高级用户也可实现本接口接入任意执行后端（如 agent 模块的 ToolRegistry），
 * 从而避免 framework → agent 的反向依赖。</p>
 *
 * <p>实现应把任何异常收敛为错误文本返回，<b>不应向外抛出</b>——工具失败是可观测的
 * 业务结果，应回灌模型让其自我修正，而非打断整条链。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
@FunctionalInterface
public interface ToolExecutor {

	/**
	 * 执行一次工具调用。
	 *
	 * @param toolName       工具名（{@code @Tool.name()} 或方法名）
	 * @param argumentsJson  模型给出的参数 JSON 对象字符串（可能为空串）
	 * @return 回灌给模型的结果文本（成功输出或错误描述）
	 */
	String execute(String toolName, String argumentsJson);
}
