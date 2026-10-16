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

package com.sure.ai.framework.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个 AiService 接口方法对应的系统消息内容。
 *
 * <p>支持 {@code {paramName}} 模板占位符：调用时按方法实参名（或 {@link Param} 显式绑定）
 * 替换为实参值。渲染后的文本作为 {@code system} 角色消息置于请求消息列表首部。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SystemMessage {

	/**
	 * 系统消息模板，可含 {@code {paramName}} 占位符。
	 *
	 * @return 模板文本
	 */
	String value();
}
