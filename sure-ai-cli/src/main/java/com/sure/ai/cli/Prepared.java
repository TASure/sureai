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

package com.sure.ai.cli;

import com.sure.ai.client.AiClient;

/**
 * 已按全局选项装配好的客户端及其解析后的模型。
 *
 * @param descriptor 平台描述（含默认嵌入模型），测试 Fake 可为 null
 * @param client     对话客户端
 * @param model      最终生效的对话模型（命令行 --model 优先，否则平台默认）
 * @author sureai
 * @since 2.0.0
 */
public record Prepared(ProviderDescriptor descriptor, AiClient client, String model) {
}
