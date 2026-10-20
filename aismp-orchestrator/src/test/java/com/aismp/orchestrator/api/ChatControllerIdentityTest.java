package com.aismp.orchestrator.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份解析：网关注入的头 vs 浏览器能改的 query 参数。
 *
 * <p>这是数据可见范围的信任根。写错的后果不是报错，而是<b>静默越权</b>——
 * 用户改一下 URL 里的 warehouse_code 就能看到别的仓的库存，日志里还一切正常。
 * 所以这块必须有用例钉死，而且必须测到「头存在但为空」这种边界。
 */
class ChatControllerIdentityTest {

    @Test
    void headerWinsOverQuery() {
        var id = ChatController.resolveIdentity("zhangsan", "9B00", "lisi", "9BZA");

        assertThat(id.userId()).isEqualTo("zhangsan");
        assertThat(id.warehouseCode()).isEqualTo("9B00");
    }

    @Test
    void emptyHeaderWarehouseDoesNotFallBackToQuery() {
        // 头里的空串含义是「该账号没有仓位」（演示账号），不是「没注入」
        // 一旦退回 query，就等于允许这类账号自报一个仓位
        var id = ChatController.resolveIdentity("zhangsan", "", "zhangsan", "9BZA");

        assertThat(id.warehouseCode()).isEmpty();
    }

    @Test
    void nullHeaderWarehouseIsTreatedAsEmpty() {
        var id = ChatController.resolveIdentity("zhangsan", null, "zhangsan", "9BZA");
        assertThat(id.warehouseCode()).isEmpty();
    }

    @Test
    void withoutHeaderQueryIsUsedForLocalScripts() {
        // 非门户通道（本机脚本 / 服务间直连）没有注入头，此时才回退 query
        var id = ChatController.resolveIdentity(null, null, "script-user", "9B00");

        assertThat(id.userId()).isEqualTo("script-user");
        assertThat(id.warehouseCode()).isEqualTo("9B00");
    }

    @Test
    void blankEverythingFallsBackToAnonymous() {
        var id = ChatController.resolveIdentity("", "", null, null);

        assertThat(id.userId()).isEqualTo("anonymous");
        assertThat(id.warehouseCode()).isEmpty();
    }

    @Test
    void valuesAreTrimmed() {
        var id = ChatController.resolveIdentity("  zhangsan  ", " 9B00 ", " x ", " y ");
        assertThat(id.userId()).isEqualTo("zhangsan");
        assertThat(id.warehouseCode()).isEqualTo("9B00");
    }

    @Test
    void whitespaceOnlyHeaderCountsAsAbsent() {
        var id = ChatController.resolveIdentity("   ", "", "query-user", "9B00");
        assertThat(id.userId()).isEqualTo("query-user");
    }
}
