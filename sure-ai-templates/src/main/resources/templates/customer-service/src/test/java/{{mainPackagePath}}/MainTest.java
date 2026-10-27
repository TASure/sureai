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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 客服机器人模板最小单元测试（零真实网络：工具纯逻辑断言）。
 */
public class MainTest {

	@Test
	public void queryOrderReturnsKnownStatus() {
		String result = Main.queryOrder("10001");
		assertTrue(result.contains("已发货"));
		assertTrue(result.contains("10001"));
	}

	@Test
	public void queryOrderRejectsBlankOrderId() {
		assertTrue(Main.queryOrder(null).contains("不能为空"));
		assertTrue(Main.queryOrder("  ").contains("不能为空"));
	}

	@Test
	public void queryOrderUnknownOrderIsReported() {
		assertTrue(Main.queryOrder("99999").contains("未找到订单"));
	}

	@Test
	public void refundAcceptsValidRequest() {
		String result = Main.requestRefund("10002", "商品破损");
		assertTrue(result.contains("已受理订单 10002"));
		assertTrue(result.contains("24 小时内"));
	}

	@Test
	public void refundRejectsShortReason() {
		assertTrue(Main.requestRefund("10002", "破损").contains("至少 4 个字"));
		assertEquals("受理文本不含订单号：", Main.requestRefund(null, "商品破损"), "订单号不能为空。");
	}
}
