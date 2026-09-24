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
 * Cohere v2 接入。
 *
 * <p>自研实现，独立协议（非 OpenAI 兼容）：鉴权为 {@code Authorization: Bearer <apiKey>}，
 * 对话接口 {@code POST /chat} 无 {@code choices}，正文在 {@code message.content[].text}，
 * 用量在 {@code usage.tokens.input_tokens/output_tokens}；向量接口 {@code POST /embed}
 * 必填 {@code input_type}（默认 {@code search_document}），向量在 {@code embeddings.float}；
 * 流式 SSE 以 {@code type=content-delta} 投递增量、以 {@code type=message-end} 结束，
 * 无 {@code [DONE]} 标志。Cohere v2 不提供模型列表 API。
 * 默认 baseUrl：{@code https://api.cohere.com/v2}。</p>
 *
 * <p>官方文档：<a href="https://docs.cohere.com/reference">https://docs.cohere.com/reference</a></p>
 *
 * @author sureai
 * @since 0.2.0
 */
package com.sure.ai.cohere;
