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

package com.sure.ai.agent.tool.catalog;

import java.util.List;

/**
 * 工具提供者 SPI（ServiceLoader）：模块 / 插件通过实现本接口并在
 * {@code META-INF/services/com.sure.ai.agent.tool.catalog.ToolProvider} 登记，
 * 即可向 {@link ToolCatalog} 贡献本地工具清单。
 *
 * <p>本 SPI 只负责<b>清单注册</b>：实现方在 {@link #provide()} 内返回进程内
 * 已构造好的声明与处理器；远程清单来源由实现方自行获取后转换为本记录，
 * 框架层不发起任何真实网络调用。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public interface ToolProvider {

	/**
	 * 提供本模块贡献的工具清单。
	 *
	 * @return 工具贡献列表（实现可返回空列表；返回 null 由加载方按空处理）
	 */
	List<ToolContribution> provide();
}
