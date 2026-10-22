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

package {{mainPackage}};

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sure.ai.agent.memory.InMemoryConversationMemory;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 客服机器人模板（ReAct + 工具 + 会话记忆）。
 *
 * <p>内置两个工具：{@code query_order}（订单状态查询，mock 数据）与
 * {@code request_refund}（退换货申请，规则校验）。设置
 * {@code SURE_AI_OPENAI_API_KEY} 后由模型驱动完整 ReAct 循环；
 * 无 key 时直接演示两个工具的可执行行为。</p>
 *
 * @author sureai
 */
public final class Main {

	/** 查询订单工具名。 */
	static final String TOOL_QUERY_ORDER = "query_order";

	/** 退换货工具名。 */
	static final String TOOL_REQUEST_REFUND = "request_refund";

	/** mock 订单数据（订单号 → 状态）。 */
	private static final Map<String, String> ORDERS = Map.of(
		"10001", "已发货（预计明日送达）",
		"10002", "待支付",
		"10003", "已签收");

	private Main() {
		throw new AssertionError("No instances");
	}

	/**
	 * 查询订单状态。
	 *
	 * @param orderId 订单号
	 * @return 状态文本
	 */
	static String queryOrder(String orderId) {
		if (orderId == null || orderId.isBlank()) {
			return "订单号不能为空，请提供 5 位数字订单号。";
		}
		String status = ORDERS.get(orderId);
		return status == null
			? "未找到订单 " + orderId + "，请核对订单号。"
			: "订单 " + orderId + " 当前状态：" + status + "。";
	}

	/**
	 * 退换货申请（规则校验）。
	 *
	 * @param orderId 订单号
	 * @param reason  原因（须 ≥4 字）
	 * @return 受理结果
	 */
	static String requestRefund(String orderId, String reason) {
		if (orderId == null || orderId.isBlank()) {
			return "订单号不能为空。";
		}
		if (reason == null || reason.length() < 4) {
			return "退换货原因至少 4 个字，请补充说明。";
		}
		return "已受理订单 " + orderId + " 的退换货申请（原因：" + reason
			+ "）。客服将在 24 小时内处理。";
	}

	/**
	 * 入口。
	 *
	 * @param args 未使用
	 */
	public static void main(String[] args) {
		ToolRegistry registry = new ToolRegistry();
		registry.register(
			ToolFunction.of(TOOL_QUERY_ORDER, "按订单号查询订单状态",
				"{\"type\":\"object\",\"required\":[\"orderId\"],"
				+ "\"properties\":{\"orderId\":{\"type\":\"string\"}}}"),
			(ToolHandler) json -> queryOrder(json.getString("orderId")));
		registry.register(
			ToolFunction.of(TOOL_REQUEST_REFUND, "提交退换货申请",
				"{\"type\":\"object\",\"required\":[\"orderId\",\"reason\"],"
				+ "\"properties\":{\"orderId\":{\"type\":\"string\"},"
				+ "\"reason\":{\"type\":\"string\"}}}"),
			(ToolHandler) json -> requestRefund(
				json.getString("orderId"), json.getString("reason")));

		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("未设置 SURE_AI_OPENAI_API_KEY，直接演示工具：");
			System.out.println("  " + queryOrder("10001"));
			System.out.println("  " + requestRefund("10002", "商品破损"));
			System.out.println("设置 key 后运行 mvn exec:java 体验模型驱动的 ReAct 循环。");
			return;
		}

		ChatRequest base = ChatRequest.builder()
			.model(OpenAiModels.GPT_4O_MINI)
			.messages(List.of(ChatMessage.system("你是电商客服机器人，只能使用提供的工具。"
				+ "涉及订单状态时调用 query_order，涉及退换货时调用 request_refund。")))
			.build();
		ReActAgent agent = new ReActAgent(OpenAiUtil.client(), base, registry,
			null, 10, Duration.ofSeconds(120),
			new InMemoryConversationMemory(20));
		String answer = agent.run("我的订单 10001 现在什么状态？我想退掉它，理由是商品破损。");
		System.out.println("客服回答：" + answer);
	}
}
