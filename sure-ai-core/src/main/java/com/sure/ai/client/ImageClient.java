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

package com.sure.ai.client;

import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * 图像生成客户端抽象。
 *
 * <p>屏蔽平台间同步/异步差异：同步平台（如 OpenAI DALL·E）直接返回结果；
 * 异步平台（如通义万相、文心一格）在内部完成「提交任务 → 轮询状态 → 提取结果」全链路，
 * 对外同样以同步方法返回。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface ImageClient {

	/**
	 * 同步图像生成。
	 *
	 * @param request 请求
	 * @return 响应（含生成的图像 URL 或 Base64 数据）
	 */
	ImageResponse generate(ImageRequest request);

	/**
	 * 便捷图像生成：构造只含模型与提示词的请求。
	 *
	 * @param model  模型名
	 * @param prompt 提示词
	 * @return 响应
	 */
	default ImageResponse generate(String model, String prompt) {
		return generate(ImageRequest.of(model, prompt));
	}
}
