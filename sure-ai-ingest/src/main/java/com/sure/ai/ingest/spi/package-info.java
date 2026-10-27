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
 * 单格式文档解析器 SPI：把「已下载 / 已读取的字节」解析为带元数据的 {@code Document}。
 *
 * <p>加载器（{@link com.sure.ai.ingest.FileSystemLoader} /
 * {@link com.sure.ai.ingest.URLLoader}）负责读字节与路由，解析器负责把字节按格式
 * 抽取为纯文本。新增文件格式只需实现本接口并注册到加载器。</p>
 */
package com.sure.ai.ingest.spi;
