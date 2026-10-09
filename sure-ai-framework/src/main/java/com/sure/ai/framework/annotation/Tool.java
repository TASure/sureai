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
 * 把接口方法声明为一个可供模型调用的工具（Function）。
 *
 * <p>代理在每次发起对话请求时，会把接口中所有 {@code @Tool} 方法的签名转换为
 * {@link com.sure.ai.model.ToolSpec}（方法名/参数名 → JSON Schema，返回类型 → 工具结果
 * 形状）并挂到 {@code ChatRequest.tools}。本批次仅完成「工具 schema 注册进请求」；
 * 模型侧的多轮工具调用循环由后续 ToolCallingAdvisor 批次接入。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

	/**
	 * 工具名；留空时使用方法名。
	 *
	 * @return 工具名
	 */
	String name() default "";

	/**
	 * 工具描述（供模型理解何时调用）。
	 *
	 * @return 描述
	 */
	String description() default "";
}
