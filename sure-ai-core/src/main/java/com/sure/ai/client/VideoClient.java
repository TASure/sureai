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

import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * 视频生成客户端抽象。
 *
 * <p>视频生成全平台均为异步任务模式（提交任务→轮询状态→获取结果），本接口屏蔽轮询细节，
 * 对外统一以同步方法返回。轮询间隔与超时由各平台客户端内部配置。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface VideoClient {

	/**
	 * 同步视频生成（内部完成异步任务轮询）。
	 *
	 * @param request 请求
	 * @return 响应（含视频 URL / 封面图 URL）
	 */
	VideoResponse generate(VideoRequest request);
}
