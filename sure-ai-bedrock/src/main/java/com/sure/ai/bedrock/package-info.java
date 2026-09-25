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
 * AWS Bedrock 接入模块。
 *
 * <p>统一使用 Bedrock <b>Converse API</b>（非流式 {@code /model/{modelId}/converse}、
 * 流式 {@code /model/{modelId}/converse-stream}），通过自研 {@link com.sure.ai.bedrock.AwsSigV4Signer}
 * 完成 AWS Signature V4 签名（HMAC-SHA256，纯 JDK 实现），HTTP 基于 JDK
 * {@link java.net.http.HttpClient}，SSE 流式复用 core 的
 * {@link com.sure.ai.internal.http.SseLineReader}。运行期零第三方依赖。</p>
 *
 * <p>支持 Anthropic Claude、Meta Llama、Amazon Titan/Nova 等 Converse 兼容模型，
 * 见 {@link com.sure.ai.bedrock.BedrockModels}。凭证通过环境变量注入，见
 * {@link com.sure.ai.bedrock.BedrockUtil}。</p>
 *
 * <p>官方文档：<a href="https://docs.aws.amazon.com/bedrock/">https://docs.aws.amazon.com/bedrock/</a></p>
 *
 * @author sureai
 * @since 1.2.0
 */
package com.sure.ai.bedrock;
