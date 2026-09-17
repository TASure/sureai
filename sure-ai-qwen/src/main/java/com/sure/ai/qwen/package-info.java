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
 * 阿里云百炼通义千问（DashScope）OpenAI 兼容模式接入模块。
 *
 * <p>默认 baseUrl {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，
 * 使用 {@code Authorization: Bearer <sk-...>} 鉴权；支持 qwen 系列对话、SSE 流式对话与
 * text-embedding 向量。</p>
 *
 * <p>官方文档：
 * <a href="https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope">通过 OpenAI 接口调用千问模型</a></p>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.qwen;
