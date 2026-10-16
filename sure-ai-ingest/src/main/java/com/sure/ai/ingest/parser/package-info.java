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
 * 内置单格式解析器：纯文本族（TXT/MD/HTML）与 PDF 文本层有限提取。
 *
 * <p>全部纯 JDK 实现、零第三方运行期依赖。PDF 为「有限文本层提取」，如实标注支持子集与限制。</p>
 */
package com.sure.ai.ingest.parser;
