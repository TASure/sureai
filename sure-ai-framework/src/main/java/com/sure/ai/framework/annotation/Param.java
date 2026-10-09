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
 * 显式声明方法参数在模板占位符中的绑定名。
 *
 * <p>当编译期未保留参数名（未开启 {@code -parameters}）时，模板 {@code {name}} 无法靠
 * 反射拿到形参名，此时可用本注解显式绑定：{@code String greet(@Param("who") String who)}
 * 可被模板 {@code "你好，{who}"} 正确替换。若未标注本注解，则回退使用反射形参名。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Param {

	/**
	 * 占位符绑定名。
	 *
	 * @return 名称
	 */
	String value();
}
