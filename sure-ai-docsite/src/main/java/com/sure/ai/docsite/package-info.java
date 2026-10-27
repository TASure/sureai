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
 * 文档站生成器（v3.0.0）：把 docs/ Markdown 生成可发布静态 HTML 站点。
 * 手写 Markdown 子集渲染器 {@link MarkdownRenderer} + 站点生成器 {@link DocSiteGenerator}，
 * 纯 JDK 零第三方依赖。
 *
 * @author sureai
 * @since 3.0.0
 */
package com.sure.ai.docsite;
