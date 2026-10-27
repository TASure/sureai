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

package com.sure.ai.internal.test.tag;

/**
 * 测试分类标记：端到端（E2E）测试。
 *
 * <p>拉起真实子进程（如 MCP stdio echo server）走完整协议回环的测试，
 * 启动成本高、依赖 JDK 单文件源码模式。在 {@code -Pfast} 构建中通过
 * surefire {@code excludedGroups} 排除。</p>
 *
 * <p>位于 {@code internal} 包：仅供测试分类使用，不属于 sureai 公共 API。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public interface E2e {
}
