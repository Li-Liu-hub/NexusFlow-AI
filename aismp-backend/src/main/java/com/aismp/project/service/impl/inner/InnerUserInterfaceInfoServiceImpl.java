package com.aismp.project.service.impl.inner;

import com.aismp.project.service.UserInterfaceInfoService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import com.aismp.aismpcommon.service.InnerUserInterfaceInfoService;

/**
 * 内部用户接口信息服务实现类
 *
 */
@Service
public class InnerUserInterfaceInfoServiceImpl implements InnerUserInterfaceInfoService {

    @Resource
    private UserInterfaceInfoService userInterfaceInfoService;

    @Override
    public boolean invokeCount(long interfaceInfoId, long userId) {
        return userInterfaceInfoService.invokeCount(interfaceInfoId, userId);
    }
}
