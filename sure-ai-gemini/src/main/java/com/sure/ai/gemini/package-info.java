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
 * Google Gemini API 平台模块。
 *
 * <p>非 OpenAI 兼容协议，API Key 查询参数鉴权，自定义 contents/parts 请求格式与
 * streamGenerateContent SSE 流式解析。官方文档：
 * <a href="https://ai.google.dev/api/rest/v1beta/models/generateContent">generateContent</a>、
 * <a href="https://ai.google.dev/api/rest/v1beta/models/streamGenerateContent">streamGenerateContent</a>、
 * <a href="https://ai.google.dev/api/rest/v1beta/models/embedContent">embedContent</a>。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.gemini;
