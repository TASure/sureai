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

package com.sure.ai.mcp.server.tool;

import java.util.List;

/**
 * {@code sureai.embed} 工具的入参模型，用于 JSON Schema 推导。
 *
 * @param platform 客户端路由键（可空）
 * @param model    向量模型名
 * @param input    批量待向量化文本（与 text 二选一）
 * @param text     便捷单条文本（可空）
 * @author sureai
 * @since 1.5.0
 */
public record EmbedToolRequest(String platform, String model, List<String> input, String text) {
}
