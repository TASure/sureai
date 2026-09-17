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
 * Azure OpenAI（Azure AI Foundry）接入模块。
 *
 * <p>baseUrl 形如 {@code https://{resource}.openai.azure.com}，使用 {@code api-key} 请求头鉴权；
 * 请求 URL 含部署名 deployment 与 api-version 查询参数（当前默认 GA 版本 {@code 2024-10-21}）。
 * 支持对话、SSE 流式对话与向量。</p>
 *
 * <p>官方文档：
 * <a href="https://learn.microsoft.com/en-us/azure/ai-foundry/openai/reference">REST API reference</a>；
 * <a href="https://learn.microsoft.com/en-us/azure/ai-foundry/openai/api-version-lifecycle">api-version 生命周期</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.azure;
