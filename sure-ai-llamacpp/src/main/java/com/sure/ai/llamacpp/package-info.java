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
 * llama.cpp 本地推理服务器平台模块。
 *
 * <p>完全 OpenAI 兼容协议，默认无鉴权，SSE 流式。端点：
 * {@code POST /v1/chat/completions}、{@code POST /v1/embeddings}、
 * {@code GET /v1/models}。向量端点需 server 以 {@code --embedding} 启动。
 * 官方文档：<a href="https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md">llama.cpp server</a>。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
package com.sure.ai.llamacpp;
