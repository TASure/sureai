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
 * sureai Spring Boot 自动装配模块。
 *
 * <p>本包是整个 sureai 工程中<b>唯一</b>引入 Spring 依赖的模块：
 * 通过 {@link com.sure.ai.boot.SureAiAutoConfiguration} 把各平台客户端
 * （{@code OpenAiClient}、{@code QwenClient} 等）按
 * {@link com.sure.ai.boot.SureAiProperties} 配置自动注册为 Spring Bean。</p>
 *
 * <p>设计红线：</p>
 * <ul>
 *   <li>sure-ai-core 与各平台模块运行期零第三方依赖，不感知 Spring；</li>
 *   <li>Spring 相关注解与依赖仅出现在本包内；</li>
 *   <li>本模块不进入 sure-ai-all 聚合，也不在运行期依赖链上——
 *       仅 Spring Boot 工程显式引入后才生效。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.1.0
 */
package com.sure.ai.boot;
