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

import com.sure.ai.model.RerankRequest;
import com.sure.ai.model.RerankResponse;

/**
 * 重排（Rerank）客户端抽象。
 *
 * <p>对 RAG 二阶段检索命中的候选文档按与查询的相关性重新排序。屏蔽平台差异，
 * 平台客户端负责将 {@link RerankRequest} 序列化为自身协议并解析为
 * {@link RerankResponse}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface RerankClient {

	/**
	 * 对候选文档重排。
	 *
	 * @param request 重排请求
	 * @return 重排响应（结果按相关性降序）
	 */
	RerankResponse rerank(RerankRequest request);
}
