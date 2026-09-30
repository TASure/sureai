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
 * 全链路异步 / Java 21 虚拟线程支持（1.9.0 批次 3）。
 *
 * <p>本包为 chat / embed / image / video / audio 请求路径提供 {@link java.util.concurrent.CompletableFuture}
 * 异步 API，统一以虚拟线程承载阻塞 IO。两种使用形态可组合：</p>
 * <ul>
 *   <li>{@link com.sure.ai.client.AiClient} 的 {@code default chatAsync/chatStreamAsync}——
 *       既有平台客户端零改动即获得异步能力；</li>
 *   <li>{@link com.sure.ai.client.async.AsyncAiClient} / {@link AsyncEmbeddingClient} /
 *       {@link AsyncImageClient} / {@link AsyncVideoClient} / {@link AsyncAudioClient} 装饰器——
 *       可注入自定义 {@link java.util.concurrent.Executor}，并覆盖其余四个客户端接口。</li>
 * </ul>
 *
 * @see com.sure.ai.client.async.AsyncExecutors
 * @see com.sure.ai.client.async.AsyncClients
 * @since 1.9.0
 */
package com.sure.ai.client.async;
