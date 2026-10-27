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
 * 文档导入器：把「来源」（本地文件、URL）加载为带元数据的 RAG 文档。
 *
 * <p>本模块复用 {@code sure-ai-rag} 的 {@code Document} 模型与 {@code TextSplitter}
 * 分块链路，保持 {@code RagPipeline} 兼容：加载得到的 {@code Document} 可直接交给
 * {@code RagPipeline#ingest(Document)} 入库。</p>
 *
 * <p>能力边界：加载器只负责「来源 → 纯文本 + 元数据」，<b>不负责分块与向量化</b>。
 * 分块由 rag 的 {@code TextSplitter} 完成，向量化与入库由 {@code RagPipeline} 负责。</p>
 *
 * <ul>
 *   <li>{@link com.sure.ai.ingest.DocumentLoader}：加载器 SPI（{@code load(Path)} /
 *       {@code load(URI)}）；</li>
 *   <li>{@link com.sure.ai.ingest.FileSystemLoader}：本地文件，按扩展名路由到对应解析器；</li>
 *   <li>{@link com.sure.ai.ingest.URLLoader}：JDK {@link java.net.http.HttpClient} 下载后路由；</li>
 *   <li>{@link com.sure.ai.ingest.spi.DocumentParser}：单格式解析器 SPI，可扩展自定义格式；</li>
 *   <li>{@link com.sure.ai.ingest.parser}：纯文本族（TXT/MD/HTML）与 PDF 文本层有限提取，纯 JDK 零依赖。</li>
 * </ul>
 *
 * <p>DOCX/XLSX/PPTX 由可选模块 {@code sure-ai-ingest-poi}（Apache POI provided）提供。</p>
 */
package com.sure.ai.ingest;
