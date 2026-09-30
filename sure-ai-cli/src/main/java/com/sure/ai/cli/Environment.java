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

package com.sure.ai.cli;

/**
 * 环境变量读取抽象：便于测试注入，避免直接依赖 {@link System#getenv}。
 *
 * @author sureai
 * @since 2.0.0
 */
@FunctionalInterface
public interface Environment {

	/**
	 * 读取环境变量。
	 *
	 * @param name 变量名
	 * @return 变量值，未设置返回 null
	 */
	String getenv(String name);

	/** 默认实现：委托 {@link System#getenv}。 */
	Environment SYSTEM = System::getenv;
}
