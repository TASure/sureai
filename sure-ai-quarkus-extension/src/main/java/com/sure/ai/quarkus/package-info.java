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
 * sureai Quarkus 扩展 runtime 模块。
 *
 * <p>本包是 sureai 工程中引入 Quarkus 运行期依赖的两处之一（另一处为
 * {@code sure-ai-quarkus-extension-deployment} 构建期模块）：通过
 * {@link com.sure.ai.quarkus.SureAiRecorder} 把 {@code sure.ai.<平台>.*} 配置
 * 延迟实例化为各平台客户端（{@code OpenAiClient}、{@code QwenClient} 等），
 * 再由 deployment 模块注册为 Arc 合成 Bean。</p>
 *
 * <p>设计红线：</p>
 * <ul>
 *   <li>sure-ai-core 与各平台模块运行期零 Quarkus 依赖，不感知 Quarkus；</li>
 *   <li>Quarkus 相关注解与依赖仅出现在本扩展两个模块内；</li>
 *   <li>本模块不进入 sure-ai-all 聚合，也不在运行期依赖链上——
 *       仅 Quarkus 工程显式引入后才生效。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.quarkus;
