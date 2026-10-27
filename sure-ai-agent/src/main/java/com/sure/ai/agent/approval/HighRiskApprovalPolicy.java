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

package com.sure.ai.agent.approval;

import java.util.Set;

import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * 高危动作审批策略：按工具名前缀或参数中的金额字段判断是否需要审批。
 *
 * <p>默认命中规则：</p>
 * <ul>
 *   <li>工具名以 {@code payment / transfer / withdraw / delete / remove /
 *       write / update / exec / shell} 等前缀开头（大小写不敏感）；</li>
 *   <li>参数中出现 {@code amount / money / balance / price / total} 等键，
 *       且其数值大于 0（视为涉及资金）。</li>
 * </ul>
 *
 * <p>前缀集合与金额键集合均可通过构造器自定义。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class HighRiskApprovalPolicy implements ApprovalPolicy {

	/** 默认高危工具名前缀。 */
	private static final Set<String> DEFAULT_NAME_PREFIXES = Set.of(
		"payment", "transfer", "withdraw", "delete", "remove",
		"write", "update", "exec", "shell");

	/** 默认金额类参数键。 */
	private static final Set<String> DEFAULT_AMOUNT_KEYS = Set.of(
		"amount", "money", "balance", "price", "total");

	/** 高危工具名前缀（小写）。 */
	private final Set<String> namePrefixes;

	/** 金额类参数键（小写）。 */
	private final Set<String> amountKeys;

	/**
	 * 使用默认规则构造。
	 */
	public HighRiskApprovalPolicy() {
		this(DEFAULT_NAME_PREFIXES, DEFAULT_AMOUNT_KEYS);
	}

	/**
	 * 全参构造。
	 *
	 * @param namePrefixes 高危工具名前缀（大小写不敏感）
	 * @param amountKeys   金额类参数键（小写匹配）
	 */
	public HighRiskApprovalPolicy(Set<String> namePrefixes, Set<String> amountKeys) {
		this.namePrefixes = namePrefixes == null ? DEFAULT_NAME_PREFIXES : Set.copyOf(namePrefixes);
		this.amountKeys = amountKeys == null ? DEFAULT_AMOUNT_KEYS : Set.copyOf(amountKeys);
	}

	@Override
	public boolean requiresApproval(String toolName, JsonObject args) {
		if (toolName != null && matchesNamePrefix(toolName)) {
			return true;
		}
		return args != null && hasAmount(args);
	}

	/**
	 * 判断工具名是否命中任一高危前缀。
	 */
	private boolean matchesNamePrefix(String toolName) {
		String lower = toolName.toLowerCase();
		for (String prefix : this.namePrefixes) {
			if (lower.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断参数中是否存在正值金额字段。
	 */
	private boolean hasAmount(JsonObject args) {
		for (String key : args.keySet()) {
			if (this.amountKeys.contains(key.toLowerCase())) {
				JsonElement el = args.get(key);
				if (el != null && el.isNumber() && el.getAsDouble() > 0) {
					return true;
				}
			}
		}
		return false;
	}
}
