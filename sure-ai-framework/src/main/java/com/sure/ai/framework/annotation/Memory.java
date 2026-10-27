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
 * 标记某个方法（或整个接口）参与会话记忆注入。
 *
 * <p>创建代理时若注入了 {@link com.sure.ai.framework.memory.ChatMemory}，则：</p>
 * <ul>
 *   <li>请求构造时，把记忆中的历史消息（system 之后、当前 user 之前）注入上下文；</li>
 *   <li>阻塞式对话完成后，把本轮 user 消息与助手回复追加进记忆。</li>
 * </ul>
 *
 * <p>标注在接口类型上表示该接口所有方法默认开启记忆；方法上的标注可覆盖（开启或关闭）。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Memory {
}
