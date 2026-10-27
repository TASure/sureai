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

/**
 * 工具注册中心市场（ToolCatalog）子包：在 {@code ToolRegistry} 执行注册中心之上，
 * 聚合工具声明、处理器与市场元数据，提供标签 / 关键词 / 来源 / 风险级别发现、
 * 本地与 MCP 工具聚合、命名空间与冲突策略、SPI 清单接入，并可一键导出供 Agent 使用。
 *
 * <p>核心类型：</p>
 * <ul>
 *   <li>{@code ToolMetadata}：不可变元数据（标签 / 版本 / 风险 / 来源 / 服务端 / 文档）；</li>
 *   <li>{@code ToolEntry}：声明 + 处理器 + 元数据三合一市场条目；</li>
 *   <li>{@code ToolCatalog}：市场本体（发现 / 聚合 / 冲突 / 导出）；</li>
 *   <li>{@code ToolProvider} / {@code ToolProviders}：ServiceLoader 清单 SPI；</li>
 *   <li>{@code RiskLevel} / {@code ToolSource} / {@code ConflictStrategy}：枚举。</li>
 * </ul>
 */
package com.sure.ai.agent.tool.catalog;
