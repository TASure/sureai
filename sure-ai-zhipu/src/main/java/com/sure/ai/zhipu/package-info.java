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
 * 智谱 AI 开放平台（BigModel / GLM）接入。
 *
 * <p>本模块基于 OpenAI 兼容协议，差异仅在鉴权：apiKey 为 {@code id.secret} 格式，
 * 客户端会自动用 secret 签发 HS256 JWT 作为 Bearer token 并缓存复用。默认 baseUrl：
 * {@code https://open.bigmodel.cn/api/paas/v4}，支持 {@code /chat/completions} 与
 * {@code /embeddings}（embedding-3）。</p>
 *
 * <p>官方文档：</p>
 * <ul>
 *   <li>模型概览：<a href="https://docs.bigmodel.cn/cn/guide/start/model-overview">https://docs.bigmodel.cn/cn/guide/start/model-overview</a></li>
 *   <li>GLM-4 系列：<a href="https://docs.bigmodel.cn/cn/guide/models/text/glm-4">https://docs.bigmodel.cn/cn/guide/models/text/glm-4</a></li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.zhipu;
