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

package com.sure.ai.client.realtime;

import java.util.concurrent.ScheduledFuture;

/**
 * 实时客户端的延迟/周期任务调度抽象（包内可见）。
 *
 * <p>生产实现基于 JDK 守护线程池；测试可注入「立即同步执行」实现，使重连逻辑零真实网络、
 * 确定性可测。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
interface RealtimeTaskScheduler {

	/**
	 * 延迟执行一次性任务。
	 *
	 * @param task       任务
	 * @param delayMillis 延迟毫秒
	 * @return 可取消句柄
	 */
	ScheduledFuture<?> schedule(Runnable task, long delayMillis);

	/**
	 * 固定速率周期执行（心跳保活）。
	 *
	 * @param task        任务
	 * @param intervalMillis 间隔毫秒
	 * @return 可取消句柄
	 */
	ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long intervalMillis);

	/** 释放调度线程。 */
	void shutdown();
}
