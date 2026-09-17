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
 * DeepSeek 接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.deepseek.com}（不带 {@code /v1}，
 * endpoint 为 {@code /chat/completions}），使用 {@code Authorization: Bearer <apiKey>} 鉴权。
 * 支持 {@code deepseek-chat} 与 {@code deepseek-reasoner} 对话及 SSE 流式；暂无官方 embeddings。</p>
 *
 * <p>官方文档：<a href="https://api-docs.deepseek.com/">https://api-docs.deepseek.com/</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.deepseek;
