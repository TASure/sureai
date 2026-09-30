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
 * 讯飞星火 Spark 接入模块。
 *
 * <p>OpenAI 兼容协议，默认 baseUrl {@code https://spark-api-open.xf-yun.com/v1}（endpoint
 * {@code /chat/completions}），使用 {@code Authorization: Bearer <apiKey>} 鉴权；
 * 讯飞 API Key 形如 {@code APIPath:APIKey}，需整体传入。支持 lite/pro/max/general 模型对话及
 * SSE 流式；该兼容面未提供 embeddings。星火原生 WebSocket 接口不在本模块范围。</p>
 *
 * <p>官方文档：<a href="https://www.xfyun.cn/doc/spark/Web.html">星火认知大模型 Web API</a>。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.spark;
