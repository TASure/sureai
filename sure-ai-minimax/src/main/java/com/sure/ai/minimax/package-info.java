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
 * MiniMax（稀宇科技）接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.minimax.cn/v1}（endpoint
 * {@code /chat/completions}），使用 {@code Authorization: Bearer <apiKey>} 鉴权。
 * 支持 MiniMax-M3 等 M 系列模型对话及 SSE 流式；OpenAI 兼容端点未提供 embeddings。
 * 国际站可将 baseUrl 覆盖为 {@code https://api.minimax.io/v1}。</p>
 *
 * <p>官方文档：<a href="https://platform.minimaxi.com/docs/api-reference/text-openai-api">
 * https://platform.minimaxi.com/docs/api-reference/text-openai-api</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.minimax;
