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
 * 标注一个 AiService 接口，并声明默认模型与采样参数。
 *
 * <p>模型名解析优先级：{@code FrameworkUtil.builder(...).model(x)} 显式设置 &gt;
 * 本注解 {@link #model()}；二者皆空时创建代理失败。{@link #temperature()} 为 -1 表示不设置。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AiService {

	/**
	 * 默认模型名。
	 *
	 * @return 模型名，空串表示未设置
	 */
	String model() default "";

	/**
	 * 默认采样温度；-1 表示不设置。
	 *
	 * @return temperature
	 */
	double temperature() default -1.0;
}
