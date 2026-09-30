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
 * 硅基流动 SiliconFlow（SiliconCloud）接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.siliconflow.cn/v1}（endpoint
 * {@code /chat/completions} 与 {@code /embeddings}），使用 {@code Authorization: Bearer <apiKey>} 鉴权。
 * 平台为开源模型聚合托管，支持 DeepSeek-V3、Qwen2.5/Qwen3 等对话模型与 SSE 流式，
 * 以及 BAAI/bge-m3 等向量模型。国际站可将 baseUrl 覆盖为 {@code https://api.siliconflow.com/v1}。</p>
 *
 * <p>官方文档：<a href="https://docs.siliconflow.cn/cn/userguide/capabilities/text-generation">
 * 语言模型</a>；<a href="https://docs.siliconflow.cn/docs/api/embeddings-post">创建嵌入请求</a>。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.siliconflow;
