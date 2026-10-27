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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import com.sure.tool.lang.Assert;

/**
 * {@link ToolProvider} SPI 加载器：基于 {@link ServiceLoader} 发现所有登记的提供者，
 * 并可把贡献清单批量装入 {@link ToolCatalog}。
 *
 * <p>框架层不发起任何网络调用；远程清单由提供者实现方自行获取后转换为
 * {@link ToolContribution}。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class ToolProviders {

	private ToolProviders() {
		throw new AssertionError("No instances");
	}

	/**
	 * 用当前线程上下文类加载器加载全部提供者。
	 *
	 * @return 提供者列表（无登记时为空列表）
	 */
	public static List<ToolProvider> load() {
		// ServiceLoader 在类加载器为 null 时内部回退系统类加载器，无需额外分支。
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		ServiceLoader<ToolProvider> loader = ServiceLoader.load(ToolProvider.class, cl);
		List<ToolProvider> out = new ArrayList<>();
		for (ToolProvider provider : loader) {
			out.add(provider);
		}
		return out;
	}

	/**
	 * 加载全部提供者并把贡献批量装入市场。
	 *
	 * @param catalog 目标市场
	 * @return 实际注册的贡献条数（提供者返回 null 清单按空处理）
	 */
	public static int loadInto(ToolCatalog catalog) {
		Assert.notNull(catalog, "catalog must not be null");
		return loadInto(catalog, load());
	}

	/**
	 * 把指定提供者清单批量装入市场（便于测试与自定义装配）。
	 *
	 * @param catalog   目标市场
	 * @param providers 提供者集合（可空视为空）
	 * @return 实际注册的贡献条数（提供者返回 null 清单按空处理）
	 */
	public static int loadInto(ToolCatalog catalog, Iterable<ToolProvider> providers) {
		Assert.notNull(catalog, "catalog must not be null");
		int count = 0;
		if (providers == null) {
			return 0;
		}
		for (ToolProvider provider : providers) {
			List<ToolContribution> contributions = provider.provide();
			if (contributions == null) {
				continue;
			}
			for (ToolContribution c : contributions) {
				catalog.register(c.function(), c.handler(), c.metadata());
				count++;
			}
		}
		return count;
	}
}
