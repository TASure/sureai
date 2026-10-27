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
package com.sure.ai.examples;

import java.util.List;

import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;
import com.sure.ai.qwen.QwenUtil;

/**
 * 重排序（Rerank）使用示例。
 *
 * <p>演示通义千问 qwen3-rerank 对候选文档按查询相关性重新打分排序。
 * 检查环境变量 {@code SURE_AI_QWEN_API_KEY}，缺失时打印提示并优雅跳过，
 * 不抛异常中断整个 demo。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class RerankDemo {

	/** 示例查询。 */
	private static final String QUERY = "如何重置路由器密码？";

	/** 候选文档（模拟 RAG 一阶段召回结果，顺序无关）。 */
	private static final List<String> DOCUMENTS = List.of(
		"今天天气晴朗，适合外出散步。",
		"登录路由器管理后台，在无线设置中即可修改 WiFi 名称与密码。",
		"路由器重置：长按复位键 10 秒，恢复出厂后使用默认管理员密码登录。",
		"番茄炒蛋需要两个鸡蛋和三个番茄。",
		"修改路由器后台登录密码：进入系统工具 -> 管理帐户，输入旧密码与新密码后保存。"
	);

	private RerankDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== 通义千问 qwen3-rerank ===");
		String apiKey = System.getenv("SURE_AI_QWEN_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_QWEN_API_KEY");
			return;
		}
		try {
			RerankResponse resp = QwenUtil.rerank(QUERY, DOCUMENTS);
			System.out.println("  查询：" + QUERY);
			System.out.println("  重排后结果（按相关性降序）：");
			int rank = 1;
			for (RerankResult r : resp.results()) {
				String preview = r.document().length() > 40
					? r.document().substring(0, 40) + "…"
					: r.document();
				System.out.printf("    #%d  score=%.4f  [原下标=%d]  %s%n",
					rank++, r.relevanceScore(), r.index(), preview);
			}
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
