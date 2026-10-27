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
 * 腾讯混元 Hunyuan 接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://api.hunyuan.cloud.tencent.com/v1}
 * （endpoint {@code /chat/completions} 与 {@code /embeddings}），使用
 * {@code Authorization: Bearer <apiKey>} 鉴权。支持 hunyuan-turbos-latest / hunyuan-t1-latest
 * 对话及 SSE 流式，以及 hunyuan-embedding 向量（维度 1024）。混元正迁移至 TokenHub，
 * 可将 baseUrl 覆盖为 {@code https://tokenhub.tencentmaas.com/v1}。</p>
 *
 * <p>官方文档：<a href="https://cloud.tencent.com/document/product/1729/111007">
 * 混元 OpenAI 兼容接口相关调用示例</a>。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.hunyuan;
