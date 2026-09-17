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
 * OpenAI 官方平台接入模块。
 *
 * <p>基于 OpenAI 兼容协议，默认 baseUrl {@code https://api.openai.com/v1}，
 * 使用 {@code Authorization: Bearer <apiKey>} 鉴权（organization 非空时附加
 * {@code OpenAI-Organization} 头），支持对话、SSE 流式对话与向量。</p>
 *
 * <p>官方文档：<a href="https://platform.openai.com/docs/api-reference">https://platform.openai.com/docs/api-reference</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.openai;
