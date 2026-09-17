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

import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;

/**
 * 微调（Fine-tuning）客户端抽象。
 *
 * <p>负责创建微调任务、查询任务状态与上传训练数据文件；异步轮询等待完成
 * 由各平台客户端以 {@code waitForCompletion(jobId, timeoutMs)} 形式提供。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface FineTuneClient {

	/**
	 * 创建微调任务。
	 *
	 * @param request 微调请求
	 * @return 任务响应
	 */
	FineTuneResponse createFineTune(FineTuneRequest request);

	/**
	 * 查询微调任务状态。
	 *
	 * @param jobId 任务 ID
	 * @return 任务响应
	 */
	FineTuneResponse getFineTune(String jobId);

	/**
	 * 上传训练数据文件，返回 file_id。
	 *
	 * @param fileName 文件名
	 * @param content  文件二进制内容（JSONL）
	 * @return file_id
	 */
	String uploadTrainingFile(String fileName, byte[] content);
}
