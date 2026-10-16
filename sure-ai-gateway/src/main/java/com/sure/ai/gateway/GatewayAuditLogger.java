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

package com.sure.ai.gateway;

import java.util.List;
import java.util.logging.Logger;

/**
 * 网关审计日志器：基于 JUL（java.util.logging）落一行请求概要。
 *
 * <p>本批选型为 JUL（任务允许 JUL 或 SPI）：默认 logger 名为
 * {@value #LOGGER_NAME}，级别 INFO。日志只记录请求概要 / 路由结果 / 成本 / 租户，
 * <b>不落全文</b>（不含消息体 / 完整响应），{@code detail} 截断到
 * {@value #DETAIL_LIMIT} 字符。</p>
 *
 * <p>审计与指标解耦：本类只负责把 {@link AuditEvent} 写成结构化一行文本，
 * 由 GatewayClient（集成批）在请求终态时调用，不做计数 / 聚合。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class GatewayAuditLogger {

	/** 默认 JUL logger 名。 */
	public static final String LOGGER_NAME = "com.sure.ai.gateway.Audit";

	/** detail 截断上限（字符数）。 */
	public static final int DETAIL_LIMIT = 200;

	/** 委托的 JUL logger。 */
	private final Logger logger;

	/** 默认构造器：使用名为 {@value #LOGGER_NAME} 的 JUL logger。 */
	public GatewayAuditLogger() {
		this(Logger.getLogger(LOGGER_NAME));
	}

	/**
	 * 测试 / 同包注入用构造器：注入自定义 JUL logger（可挂 Handler 捕获输出）。
	 *
	 * <p>包内可见而非 public：避免把外部可变 {@link Logger} 直接存入字段而暴露
	 * 内部表示（SpotBugs EI_EXPOSE_REP2），与 core RetryExecutor 的注入模式一致。</p>
	 *
	 * @param logger 待注入的 logger
	 */
	GatewayAuditLogger(Logger logger) {
		this.logger = logger;
	}

	/**
	 * 记录单条审计事件；{@code event} 为 null 时直接忽略。
	 *
	 * @param event 审计事件
	 */
	public void log(AuditEvent event) {
		if (event == null) {
			return;
		}
		this.logger.info(format(event));
	}

	/**
	 * 批量记录审计事件；{@code events} 为 null 时忽略，其中的 null 元素跳过。
	 *
	 * @param events 审计事件列表
	 */
	public void log(List<AuditEvent> events) {
		if (events == null) {
			return;
		}
		for (AuditEvent e : events) {
			log(e);
		}
	}

	private String format(AuditEvent e) {
		String detail = e.detail();
		if (detail != null && detail.length() > DETAIL_LIMIT) {
			detail = detail.substring(0, DETAIL_LIMIT);
		}
		return String.join("|",
			nullSafe(e.requestId()),
			nullSafe(e.method()),
			nullSafe(e.tenantId()),
			nullSafe(e.platform()),
			nullSafe(e.instanceId()),
			String.valueOf(e.status()),
			String.valueOf(e.durationMs()),
			String.valueOf(e.estimatedCostUsd()),
			nullSafe(detail));
	}

	private static String nullSafe(String s) {
		return s == null ? "" : s;
	}
}
