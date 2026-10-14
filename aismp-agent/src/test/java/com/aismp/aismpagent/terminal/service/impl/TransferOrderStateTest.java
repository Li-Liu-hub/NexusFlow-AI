package com.aismp.aismpagent.terminal.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调拨单状态机契约测试（2026-10-08 链路重构后的「单一事实来源」锁定）。
 *
 * <p><b>为什么需要它</b>：{@code TransferOrderServiceImpl} 里新旧两套状态常量并存
 * （新版 APPLIED/OUTBOUND/DONE/REJECTED，旧版 PENDING/SENT/ACCOUNT_CONFIRMED 仅作历史展示）。
 * 旧常量的"仅供展示"约束原先只写在文档里，没有任何强制 —— 一旦有人在新逻辑里误用旧常量，
 * 就会出现状态机错乱，且难以在 E2E 里定位。本测试把它变成可执行断言。
 *
 * <p>测试对象是 private 的 {@code statusLabel} / {@code pendingAction}（用反射调用），
 * 因为这两个方法定义了"状态 → 用户可见文案"的完整映射，是状态机的对外呈现口径。
 *
 * @author agent-platform
 */
class TransferOrderStateTest {

    /** 新链路的完整状态集合（只有这 4 个）。 */
    private static final Set<String> NEW_STATES =
            Set.of("APPLIED", "OUTBOUND", "DONE", "REJECTED");

    /** 已下线的历史状态（仍可能出现在老数据的 status 列里）。 */
    private static final Set<String> LEGACY_STATES =
            Set.of("PENDING", "SENT", "ACCOUNT_CONFIRMED");

    private static String invokePrivate(Object target, String method, String arg) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        return (String) m.invoke(target, arg);
    }

    private static Object newService() throws Exception {
        // 该测试只调用纯字符串映射方法，不触碰任何 @Resource 注入的依赖。
        var ctor = TransferOrderServiceImpl.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    @DisplayName("新链路 4 个状态：每个都有非空中文标签，且不含「历史」字样")
    void newStatesHaveCleanLabels() throws Exception {
        Object svc = newService();
        for (String st : NEW_STATES) {
            String label = invokePrivate(svc, "statusLabel", st);
            assertFalse(label.isBlank(), st + " 必须有中文标签");
            assertFalse(label.contains("历史"),
                    st + " 是新链路状态，标签不应带「历史」：实际=" + label);
        }
    }

    @Test
    @DisplayName("历史状态：标签必须显式带「历史」字样（防止被当成当前状态误读）")
    void legacyStatesAreMarkedAsHistory() throws Exception {
        Object svc = newService();
        for (String st : LEGACY_STATES) {
            String label = invokePrivate(svc, "statusLabel", st);
            assertTrue(label.contains("历史"),
                    st + " 是历史状态，标签必须标「历史」，实际=" + label);
        }
    }

    @Test
    @DisplayName("pendingAction：只有「进行中」的状态才有待办提示，两个终态为空")
    void pendingActionOnlyForActiveStates() throws Exception {
        Object svc = newService();
        assertFalse(invokePrivate(svc, "pendingAction", "APPLIED").isBlank());
        assertFalse(invokePrivate(svc, "pendingAction", "OUTBOUND").isBlank());
        // 终态：轮到谁 = 没人
        assertEquals("", invokePrivate(svc, "pendingAction", "DONE"));
        assertEquals("", invokePrivate(svc, "pendingAction", "REJECTED"));
        // 历史状态不应再出现在待办口径里
        for (String st : LEGACY_STATES) {
            assertEquals("", invokePrivate(svc, "pendingAction", st),
                    st + " 是历史状态，不应产出待办提示");
        }
    }

    @Test
    @DisplayName("空状态：标签与待办都返回空串（不抛异常）")
    void nullStatusIsSafe() throws Exception {
        Object svc = newService();
        assertEquals("", invokePrivate(svc, "statusLabel", null));
        assertEquals("", invokePrivate(svc, "pendingAction", null));
    }

    @Test
    @DisplayName("未知状态：原样透传（不吞成空串，便于发现脏数据）")
    void unknownStatusPassesThrough() throws Exception {
        Object svc = newService();
        assertEquals("WHATEVER", invokePrivate(svc, "statusLabel", "WHATEVER"));
    }

    @Test
    @DisplayName("常量契约：新状态常量值就是那 4 个；历史常量值就是那 3 个（防手滑改名）")
    void constantValuesAreFrozen() {
        assertEquals("APPLIED", TransferOrderServiceImpl.ST_APPLIED);
        assertEquals("OUTBOUND", TransferOrderServiceImpl.ST_OUTBOUND);
        assertEquals("DONE", TransferOrderServiceImpl.ST_DONE);
        assertEquals("REJECTED", TransferOrderServiceImpl.ST_REJECTED);
        // 历史常量值来自老数据，改了就查不到老单据
        assertEquals("PENDING", TransferOrderServiceImpl.ST_PENDING);
        assertEquals("SENT", TransferOrderServiceImpl.ST_SENT);
        assertEquals("ACCOUNT_CONFIRMED", TransferOrderServiceImpl.ST_ACCOUNT_CONFIRMED);
    }
}
