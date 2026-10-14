package com.aismp.aismpagent.terminal.service;

import com.aismp.aismpagent.terminal.exception.BusinessException;
import com.aismp.aismpagent.terminal.mapper.WarehouseMapper;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link WarehouseScopeResolver} 纯逻辑单测（不启 Spring 上下文，Mock Mapper）。
 *
 * <p>这是「数据权限信任根」中最该被锁死的一段：口径一旦漂移，就会出现
 * 「编排层看得到、agent 却 401」这类跨进程难查的不一致。
 * 本测试把注释里声明的三条口径固化为可执行断言：
 * <ol>
 *   <li>本仓 + 所有下级仓（parentCode 递归）；</li>
 *   <li>无仓库归属（rootCode 空）→ null = 不限制；</li>
 *   <li>层级查询失败 → 最严口径，只看本仓（宁可少看，不可多看）。</li>
 * </ol>
 *
 * @author agent-platform
 */
@ExtendWith(MockitoExtension.class)
class WarehouseScopeResolverTest {

    @Mock
    private WarehouseMapper warehouseMapper;

    @InjectMocks
    private WarehouseScopeResolver resolver;

    /** 构造一棵三级仓位树：9B00(省) → 9B01(市) → 9B02(区)；另一棵独立树 7A00。 */
    private static Warehouse wh(String code, String parent) {
        Warehouse w = new Warehouse();
        w.setWarehouseCode(code);
        w.setParentCode(parent);
        return w;
    }

    private void mockTree() {
        when(warehouseMapper.listAllOrdered()).thenReturn(List.of(
                wh("9B00", null),
                wh("9B01", "9B00"),
                wh("9B02", "9B01"),
                wh("9B03", "9B00"),
                wh("7A00", null),
                wh("7A01", "7A00")
        ));
    }

    @Test
    @DisplayName("根仓 → 含自身与全部下级（递归，跨层级）")
    void visibleFrom_rootIncludesAllDescendants() {
        mockTree();
        Set<String> scope = resolver.visibleFrom("9B00");
        assertEquals(Set.of("9B00", "9B01", "9B02", "9B03"), scope);
        // 另一棵树不应被带进来
        assertFalse(scope.contains("7A00"));
    }

    @Test
    @DisplayName("中间仓 → 含自身与下级，不含上级/旁支")
    void visibleFrom_middleNode() {
        mockTree();
        Set<String> scope = resolver.visibleFrom("9B01");
        assertEquals(Set.of("9B01", "9B02"), scope);
        assertFalse(scope.contains("9B00"), "不应看到上级仓");
        assertFalse(scope.contains("9B03"), "不应看到旁支仓");
    }

    @Test
    @DisplayName("叶子仓 → 只有自身")
    void visibleFrom_leafNode() {
        mockTree();
        assertEquals(Set.of("9B02"), resolver.visibleFrom("9B02"));
    }

    @Test
    @DisplayName("查不到的仓编码 → 只有自身（不是空集，避免越权放大）")
    void visibleFrom_unknownCode() {
        mockTree();
        assertEquals(Set.of("9X99"), resolver.visibleFrom("9X99"));
    }

    @Test
    @DisplayName("rootCode 为空 → null = 不限制（演示账号）")
    void visibleFrom_blankRootMeansUnrestricted() {
        assertNull(resolver.visibleFrom(null));
        assertNull(resolver.visibleFrom(""));
        assertNull(resolver.visibleFrom("   "));
    }

    @Test
    @DisplayName("层级查询抛异常 → 最严口径：只看本仓")
    void visibleFrom_mapperFailsFallsBackToSelfOnly() {
        when(warehouseMapper.listAllOrdered()).thenThrow(new RuntimeException("db down"));
        assertEquals(Set.of("9B00"), resolver.visibleFrom("9B00"));
    }

    @Test
    @DisplayName("requireInScope：scope 为 null（不限制）时任何仓都放行")
    void requireInScope_nullScopeAllowsAll() {
        resolver.requireInScope("9B02", null);
        resolver.requireInScope(null, null);
    }

    @Test
    @DisplayName("requireInScope：请求仓在范围内 → 放行")
    void requireInScope_inScopePasses() {
        resolver.requireInScope("9B02", Set.of("9B00", "9B01", "9B02"));
    }

    @Test
    @DisplayName("requireInScope：请求仓越权 → 抛业务异常")
    void requireInScope_outOfScopeThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.requireInScope("7A00", Set.of("9B00", "9B01")));
        assertTrue(ex.getMessage().contains("7A00"), "异常信息应带上越权仓位，便于排查");
    }

    @Test
    @DisplayName("requireInScope：请求仓为空 → 放行（由上层默认口径处理）")
    void requireInScope_blankRequestedPasses() {
        resolver.requireInScope("", Set.of("9B00"));
        resolver.requireInScope(null, Set.of("9B00"));
    }
}
