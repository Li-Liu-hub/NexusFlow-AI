package com.aismp.project.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.aismp.aismpcommon.model.entity.InterfaceInfo;

/**
 * 接口信息服务
 *
 */
public interface InterfaceInfoService extends IService<InterfaceInfo> {

    void validInterfaceInfo(InterfaceInfo interfaceInfo, boolean add);
}
