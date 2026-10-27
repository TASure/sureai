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
 * 百川智能 Baichuan 接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.baichuan-ai.com/v1}（endpoint
 * {@code /chat/completions}），使用 {@code Authorization: Bearer <apiKey>} 鉴权。
 * 支持 Baichuan4-Turbo / Baichuan4 / Baichuan3-Turbo 对话及 SSE 流式；
 * 向量接口为原生 {@code /v1/embedding}（与 OpenAI {@code /v1/embeddings} 路径不一致），
 * 未在本兼容端点声明 embeddings。</p>
 *
 * <p>官方文档：<a href="https://platform.baichuan-ai.com/docs/api">
 * https://platform.baichuan-ai.com/docs/api</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.baichuan;
