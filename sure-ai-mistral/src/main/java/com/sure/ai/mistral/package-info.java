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
 * Mistral AI 接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.mistral.ai/v1}
 * （endpoint 为 {@code /chat/completions}、{@code /embeddings}、{@code /models}），使用
 * {@code Authorization: Bearer <apiKey>} 鉴权。支持 {@code mistral-large-latest}/
 * {@code codestral-latest} 等对话模型与 {@code mistral-embed} 向量模型，及 SSE 流式。</p>
 *
 * <p>官方文档：<a href="https://docs.mistral.ai/">https://docs.mistral.ai/</a></p>
 *
 * @author sureai
 * @since 1.1.0
 */
package com.sure.ai.mistral;
