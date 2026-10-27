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

package com.sure.ai.hunyuan;

/**
 * 腾讯混元 OpenAI 兼容端点当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://cloud.tencent.com/document/product/1729/111007">混元 OpenAI 兼容接口</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class HunyuanModels {

	private HunyuanModels() {
		throw new AssertionError("No instances");
	}

	/** hunyuan-turbos-latest：混元 Turbo S 主力对话模型（低时延、高并发）。 */
	public static final String HUNYUAN_TURBOS_LATEST = "hunyuan-turbos-latest";

	/** hunyuan-t1-latest：混元 T1 推理模型（思维链）。 */
	public static final String HUNYUAN_T1_LATEST = "hunyuan-t1-latest";

	/** hunyuan-embedding：混元向量模型（/v1/embeddings 当前固定模型，维度 1024）。 */
	public static final String HUNYUAN_EMBEDDING = "hunyuan-embedding";
}
