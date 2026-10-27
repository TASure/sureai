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
 * xAI Grok 接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.x.ai/v1}
 * （endpoint 为 {@code /chat/completions}、{@code /models}），使用
 * {@code Authorization: Bearer <apiKey>} 鉴权。支持 {@code grok-4.6}/{@code grok-4.3}/
 * {@code grok-3} 等对话模型及 SSE 流式，推理强度通过 {@code reasoning_effort} 透传；
 * xAI 不提供 Embeddings API。</p>
 *
 * <p>官方文档：<a href="https://docs.x.ai/">https://docs.x.ai/</a></p>
 *
 * @author sureai
 * @since 1.1.0
 */
package com.sure.ai.grok;
