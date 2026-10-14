package com.aismp.project.service.impl.inner;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aismp.project.common.ErrorCode;
import com.aismp.project.exception.BusinessException;
import com.aismp.project.mapper.UserMapper;
import com.aismp.aismpcommon.model.entity.User;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import com.aismp.aismpcommon.service.InnerUserService;

/**
 * 内部用户服务实现类
 *
 * <p>阶段3 去 Dubbo 后：原 {@code @DubboService} 换成 Spring 的 {@code @Service}，
 * 由 {@code InnerApiController} 以普通 Spring Bean 方式注入。
 */
@Service
public class InnerUserServiceImpl implements InnerUserService {

    @Resource
    private UserMapper userMapper;


    @Override
    public User getInvokeUser(String accessKey) {
        if (StringUtils.isAnyBlank(accessKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("accessKey", accessKey);
        return userMapper.selectOne(queryWrapper);
    }
}
