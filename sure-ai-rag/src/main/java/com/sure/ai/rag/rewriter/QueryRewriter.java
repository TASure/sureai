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

package com.sure.ai.rag.rewriter;

import java.util.List;

/**
 * 查询改写器：把原始查询改写为多个语义等价、表述不同的查询。
 *
 * <p>用于多查询检索扩展（Multi-Query Retrieval）：在检索前对每个改写后的查询
 * 分别检索，再合并结果并去重，从而召回单一路径遗漏的相关文档。</p>
 *
 * <p>本接口不注入默认 {@code RagPipeline} 链路，保持向后兼容；调用方在检索前
 * 手动调用 {@link #rewrite(String, int)} 并自行融合多路检索结果。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public interface QueryRewriter {

	/**
	 * 将原始查询改写为若干语义等价的查询。
	 *
	 * @param query 原始用户查询
	 * @param count 期望改写数量，必须大于 0
	 * @return 改写后的查询列表（数量可能少于 count），不会返回 null
	 */
	List<String> rewrite(String query, int count);
}
