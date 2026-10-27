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
 * 可选 Apache POI 文档解析扩展：DOCX/XLSX/PPTX。
 *
 * <p>Apache POI 以 {@code provided} 引入，不随本模块传递给下游；
 * 使用方需自行在工程中显式声明 {@code org.apache.poi:poi-ooxml} 依赖。
 * 将本模块的解析器通过 {@code FileSystemLoader.Builder#register} 注册即可启用对应格式。</p>
 */
package com.sure.ai.ingest.poi;
