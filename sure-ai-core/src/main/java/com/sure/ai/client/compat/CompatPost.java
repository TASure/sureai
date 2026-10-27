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

package com.sure.ai.client.compat;

import com.sure.ai.internal.json.JsonObject;

/**
 * 同包策略可见的"POST/GET 结果"值类型。
 *
 * <p>{@code AbstractAiClient.PostResult} 声明在 {@code com.sure.ai.client} 包且为 protected，
 * 跨包（compat 包）策略无法访问其访问器；本记录作为桥接方法返回的可访问投影，承载
 * 解析后的 JSON 与原始报文。</p>
 *
 * @param json    解析后的 JSON
 * @param rawBody 原始响应体
 * @author sureai
 * @since 1.4.0
 */
record CompatPost(JsonObject json, String rawBody) {
}
