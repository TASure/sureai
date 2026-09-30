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
 * sureai Quarkus 扩展 deployment 模块（构建期）。
 *
 * <p>本包仅在 Quarkus 应用构建期（augmentation）被加载，运行期不进入应用依赖。
 * {@link com.sure.ai.quarkus.deployment.SureAiProcessor} 通过 {@code @BuildStep}
 * 读取 {@code sure.ai.*} 配置并注册各平台客户端为 Arc 合成 Bean。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
package com.sure.ai.quarkus.deployment;
