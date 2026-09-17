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

package com.sure.ai.baidu;

/**
 * 百度千帆（文心 ERNIE）模型 ID 常量。
 *
 * <p>对话模型 ID 作为文心接口 URL 的路径段使用（不是请求体字段）。枚举 2026 年主力对话模型与
 * 通用向量模型；部分模型可能使用不同的接口路径前缀，以官方文档为准。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class BaiduModels {

	/** ernie-4.0-turbo-8k：文心 4.0 Turbo，8K 上下文。 */
	public static final String ERNIE_4_0_TURBO_8K = "ernie-4.0-turbo-8k";

	/** ernie-3.5-8k：文心 3.5，8K 上下文。 */
	public static final String ERNIE_3_5_8K = "ernie-3.5-8k";

	/** ernie-speed-128k：文心 Speed，128K 长上下文。 */
	public static final String ERNIE_SPEED_128K = "ernie-speed-128k";

	/** ernie-lite-8k：文心 Lite，8K 上下文。 */
	public static final String ERNIE_LITE_8K = "ernie-lite-8k";

	/** embedding-v1：文心通用文本向量模型。 */
	public static final String EMBEDDING_V1 = "embedding-v1";

	/** ernie-vilg-v2：文心一格文生图模型（异步任务）。 */
	public static final String ERNIE_VILG_V2 = "ernie-vilg-v2";

	/** 工具类禁止实例化。 */
	private BaiduModels() {
		throw new AssertionError("No instances");
	}
}
