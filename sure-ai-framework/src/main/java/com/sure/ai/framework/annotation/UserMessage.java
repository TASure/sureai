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
 * 标注一个 AiService 接口方法对应的用户消息内容模板。
 *
 * <p>支持 {@code {paramName}} 模板占位符。若方法未标注本注解：</p>
 * <ul>
 *   <li>仅有一个参数时，该参数的字符串值直接作为用户消息；</li>
 *   <li>多于一个参数时，必须标注本注解以明确拼接方式，否则创建时代理校验失败。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UserMessage {

	/**
	 * 用户消息模板，可含 {@code {paramName}} 占位符；空串表示无模板（按上述规则回退）。
	 *
	 * @return 模板文本
	 */
	String value() default "";
}
