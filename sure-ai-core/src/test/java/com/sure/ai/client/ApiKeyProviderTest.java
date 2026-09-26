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

package com.sure.ai.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link RoundRobinApiKeyProvider} 单元测试（零网络、可注入伪时钟）。
 *
 * @author sureai
 * @since 1.6.0
 */
public class ApiKeyProviderTest {

    /** 冷却期：1000ms。 */
    private static final long COOLDOWN = 1000L;

    /**
     * 3 个 key 连续 nextKey：key1→key2→key3→key1。
     */
    @Test
    public void testRoundRobinRotation() {
        RoundRobinApiKeyProvider p = new RoundRobinApiKeyProvider(
                List.of("k1", "k2", "k3"), COOLDOWN, () -> 0L);
        assertEquals("k1", p.currentKey());
        assertEquals("k2", p.nextKey());
        assertEquals("k3", p.nextKey());
        assertEquals("k1", p.nextKey());
    }

    /**
     * markBad(k1) 后，currentKey/nextKey 跳过 k1，冷却期后恢复。
     */
    @Test
    public void testMarkBadSkipsBadKey() {
        AtomicLong now = new AtomicLong(0L);
        RoundRobinApiKeyProvider p = new RoundRobinApiKeyProvider(
                List.of("k1", "k2", "k3"), COOLDOWN, now::get);
        assertEquals("k1", p.currentKey());

        p.markBad("k1");
        // 冷却期内：currentKey 跳过 k1 到 k2
        assertEquals("k2", p.currentKey());
        // nextKey 继续从 k2 往后：k3
        assertEquals("k3", p.nextKey());
        // 再 nextKey：k1 仍在冷却，跳过 → k2
        assertEquals("k2", p.nextKey());

        // 时间快进到冷却刚结束（now=1000，badUntil=1000，now < until 为 false → 恢复）
        now.set(1000L);
        // 从 k2 出发 nextKey：k3 → k1（已恢复）
        assertEquals("k3", p.nextKey());
        assertEquals("k1", p.nextKey());
    }

    /**
     * allKeys() 返回全部，size() 正确。
     */
    @Test
    public void testAllKeysAndSize() {
        RoundRobinApiKeyProvider p = new RoundRobinApiKeyProvider(
                List.of("a", "b", "c", "d"), COOLDOWN, () -> 0L);
        assertEquals(4, p.size());
        assertEquals(List.of("a", "b", "c", "d"), p.allKeys());
        // allKeys 含坏 key
        p.markBad("a");
        assertEquals(4, p.size());
        assertTrue(p.allKeys().contains("a"));
    }

    /**
     * 只有 1 个 key 时 nextKey 始终返回同一个。
     */
    @Test
    public void testSingleKey() {
        RoundRobinApiKeyProvider p = new RoundRobinApiKeyProvider(
                List.of("only"), COOLDOWN, () -> 0L);
        assertEquals("only", p.currentKey());
        assertEquals("only", p.nextKey());
        assertEquals("only", p.nextKey());
        assertEquals(1, p.size());
    }

    /**
     * 冷却期过后 bad key 重新可用（可注入时钟）。
     */
    @Test
    public void testBadKeyCooldownExpiry() {
        AtomicLong now = new AtomicLong(0L);
        RoundRobinApiKeyProvider p = new RoundRobinApiKeyProvider(
                List.of("k1", "k2"), COOLDOWN, now::get);
        p.markBad("k1");

        // 冷却中（now=500 < until=1000）：跳过 k1
        now.set(500L);
        assertEquals("k2", p.currentKey());
        assertEquals("k2", p.nextKey());

        // 冷却刚结束（now=1000）：currentKey 仍稳定在 k2，nextKey 推进后 k1 恢复可用
        now.set(1000L);
        assertEquals("k2", p.currentKey());
        assertEquals("k1", p.nextKey());

        // markBad 不存在的 key 无操作，不影响池
        p.markBad("ghost");
        assertFalse(p.allKeys().contains("ghost"));
        assertEquals(2, p.size());
    }
}
