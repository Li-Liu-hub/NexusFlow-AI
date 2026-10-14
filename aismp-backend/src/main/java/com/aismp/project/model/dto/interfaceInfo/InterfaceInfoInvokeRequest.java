package com.aismp.project.model.dto.interfaceInfo;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建请求
 *
 * @TableName product
 */
@Data
public class InterfaceInfoInvokeRequest implements Serializable {

        /**
         * id
         */
        private long id;


        /**
         * 用户请求参数
         */
        private String UserRequestParams;


    private static final long serialVersionUID = 1L;
}