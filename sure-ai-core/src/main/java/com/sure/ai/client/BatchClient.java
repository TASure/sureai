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

import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;

/**
 * 批处理（Batch）客户端抽象。
 *
 * <p>Batches 为异步任务：</p>
 * <ol>
 *   <li>{@link #createBatch(BatchRequest)} 提交任务，返回带 id 的初始 {@link BatchResponse}；</li>
 *   <li>调用方（或平台客户端内部）按 id 轮询 {@link #getBatch(String)}，
 *       直至 {@link BatchResponse#isCompleted()} 或 {@link BatchResponse#isFailed()} 终态。</li>
 * </ol>
 *
 * <p>本接口仅定义「提交 / 查询」两个原子操作，轮询循环由各平台客户端按需提供便捷方法。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface BatchClient {

	/**
	 * 提交批处理任务。
	 *
	 * @param request 批处理请求
	 * @return 初始任务响应（含 id 与初始状态）
	 */
	BatchResponse createBatch(BatchRequest request);

	/**
	 * 查询批处理任务状态。
	 *
	 * @param batchId 任务 ID
	 * @return 最新任务响应
	 */
	BatchResponse getBatch(String batchId);
}
