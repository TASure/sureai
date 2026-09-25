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
 * 测试分类标记：纯单元测试。
 *
 * <p>无 IO、无子进程、无真实时序等待的纯逻辑测试。未标注任何 {@code @Category}
 * 的测试默认即视为 unit，无需显式标注本接口。仅在需要显式声明时使用。</p>
 *
 * <p>位于 {@code internal} 包：仅供测试分类使用，不属于 sureai 公共 API。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public interface Unit {
}
