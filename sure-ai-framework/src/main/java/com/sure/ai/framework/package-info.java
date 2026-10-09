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

/**
 * 声明式编排层（AiService）：接口即服务。
 *
 * <p>通过 {@link com.sure.ai.framework.FrameworkUtil} 把一个带注解的 Java 接口，经 JDK
 * 动态代理映射为可调用的 AI 服务。核心注解族位于
 * {@link com.sure.ai.framework.annotation}，会话记忆抽象位于
 * {@link com.sure.ai.framework.memory}。本模块仅依赖 sure-ai-core，零第三方运行期依赖。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
package com.sure.ai.framework;
