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
 * 按全局选项装配对话客户端的工厂抽象：测试可注入 Fake 实现，避免真实网络。
 *
 * @author sureai
 * @since 2.0.0
 */
@FunctionalInterface
public interface ClientFactory {

	/**
	 * 装配客户端。
	 *
	 * @param global 全局选项
	 * @return 已就绪的客户端与解析后模型
	 * @throws CliException 未知平台 / 缺少凭证等用法错误
	 */
	Prepared prepare(GlobalOptions global);
}
