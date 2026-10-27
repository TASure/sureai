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

package com.sure.ai.quarkus;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * sureai Quarkus 扩展 recorder：把构建期「拍平后的配置」延迟到运行期实例化为平台客户端。
 *
 * <p>deployment 模块的 {@code @BuildStep} 在构建期调用本类方法时并不真正执行，
 * 而是被 Quarkus 录制成字节码；到运行期（STATIC_INIT）再真实执行——届时调用
 * {@link SureAiClientFactory} 完成「构建 AiConfig → new 客户端」。</p>
 *
 * <p>参数仅限可录制类型：String + {@link ClientSpec}/{@link BedrockSpec}（POJO）。
 * 返回 {@link RuntimeValue} 包裹不可录制的客户端实例。</p>
 */
@Recorder
public class SureAiRecorder {

	/**
	 * 运行期实例化一个 {@code AiConfig} 族平台客户端。
	 *
	 * @param clientClassName 客户端类全限定名
	 * @param spec            平台拍平配置
	 * @return 包裹好的客户端实例
	 */
	public RuntimeValue<Object> createClient(String clientClassName, ClientSpec spec) {
		return new RuntimeValue<>(SureAiClientFactory.newClient(clientClassName, spec));
	}

	/**
	 * 运行期实例化 AWS Bedrock 客户端。
	 *
	 * @param spec Bedrock 拍平配置
	 * @return 包裹好的 BedrockClient 实例
	 */
	public RuntimeValue<Object> createBedrock(BedrockSpec spec) {
		return new RuntimeValue<>(SureAiClientFactory.newBedrock(spec));
	}
}
