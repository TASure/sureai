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

/**
 * 百度智能云千帆（文心 ERNIE）接入。
 *
 * <p>自研实现：先用 API Key + Secret Key 调 OAuth 换 access_token 并按有效期缓存，
 * 再把 token 拼在文心接口 URL 查询串上。对话接口为
 * {@code /rpc/2.0/ai_custom/v1/wenxinworkshop/chat/{model}}（model 是路径参数），
 * 非流式正文在 {@code result} 字段，流式走 SSE（{@code result} 增量 + {@code is_end} 结束标志），
 * 错误体为 {@code {error_code, error_msg}}。默认 baseUrl：{@code https://aip.baidubce.com}。</p>
 *
 * <p>官方文档：</p>
 * <ul>
 *   <li>鉴权介绍：<a href="https://wenxinyiyan.apifox.cn/doc-3253895">https://wenxinyiyan.apifox.cn/doc-3253895</a></li>
 *   <li>获取 access_token：<a href="https://wenxinyiyan.apifox.cn/api-125213017">https://wenxinyiyan.apifox.cn/api-125213017</a></li>
 * </ul>
 *
 * @author sureai
 * @since 0.1.0
 */
package com.sure.ai.baidu;
