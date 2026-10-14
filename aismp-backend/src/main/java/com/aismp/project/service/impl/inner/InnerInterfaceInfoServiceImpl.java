package com.aismp.project.service.impl.inner;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aismp.project.common.ErrorCode;
import com.aismp.project.exception.BusinessException;
import com.aismp.project.mapper.InterfaceInfoMapper;
import com.aismp.aismpcommon.model.entity.InterfaceInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import jakarta.annotation.Resource;
import java.util.List;
import com.aismp.aismpcommon.service.InnerInterfaceInfoService;

/**
 * 内部接口服务实现类
 *
 * <p><b>匹配策略：精确优先，通配兜底。</b>
 *
 * <p>网关按 (url, method) 决定一个请求有没有权限、算到哪个接口的调用量，
 * 早期实现只做精确匹配（{@code WHERE url = ? AND method = ?}），
 * 这对固定路径够用，但挡不住带路径变量的接口 —— 例如数字员工内核的
 * {@code /digitalHuman/agent/{agentCode}/chat}，agentCode 是可变的，
 * 不可能为每个取值登记一行。
 *
 * <p>所以在精确匹配落空后，再对登记表里**含通配符**的行做一次 Ant 风格匹配，
 * 使登记 {@code /digitalHuman/agent/**} 即可覆盖整段前缀。
 *
 * <p><b>为什么不直接用通配替换精确匹配</b>：精确匹配走的是数据库索引等值查询、
 * 且语义无歧义；通配匹配要捞出候选行再逐条比对。把通配放在第二步，
 * 既保证存量接口的行为一字不变，又只在必要时付出这点代价。
 */
@Service
public class InnerInterfaceInfoServiceImpl implements InnerInterfaceInfoService {

    /** 支持 * 与 ** 的 Ant 风格匹配 */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Resource
    private InterfaceInfoMapper interfaceInfoMapper;

    @Override
    public InterfaceInfo getInterfaceInfo(String url, String method) {
        if (StringUtils.isAnyBlank(url, method)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 第一步：精确匹配（存量接口全部走这条路径，行为不变）
        QueryWrapper<InterfaceInfo> exact = new QueryWrapper<>();
        exact.eq("url", url);
        exact.eq("method", method);
        InterfaceInfo hit = interfaceInfoMapper.selectOne(exact);
        if (hit != null) {
            return hit;
        }

        // 第二步：通配匹配 —— 只捞出登记里含 * 的行，逐条做 Ant 匹配
        List<InterfaceInfo> wildcardCandidates = interfaceInfoMapper.selectList(
                new QueryWrapper<InterfaceInfo>()
                        .eq("method", method)
                        .like("url", "*"));
        if (wildcardCandidates == null || wildcardCandidates.isEmpty()) {
            return null;
        }
        return wildcardCandidates.stream()
                .filter(candidate -> PATH_MATCHER.match(candidate.getUrl(), url))
                .findFirst()
                .orElse(null);
    }
}
