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
 * 01.AI 零一万物（Lingyiwanwu / Yi）接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.lingyiwanwu.com/v1}（endpoint
 * {@code /chat/completions}），使用 {@code Authorization: Bearer <apiKey>} 鉴权。
 * 支持 yi-large / yi-medium / yi-lightning 对话及 SSE 流式；未提供 embeddings。</p>
 *
 * <p>官方文档：<a href="https://platform.lingyiwanwu.com/docs/api-reference">
 * https://platform.lingyiwanwu.com/docs/api-reference</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.lingyi;
