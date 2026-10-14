package com.aismp.aismpagent.terminal.exception;

import com.aismp.aismpagent.terminal.common.BaseResponse;
import com.aismp.aismpagent.terminal.common.ErrorCode;
import com.aismp.aismpagent.terminal.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（2026-10-08 补）
 *
 * <p>此前没有任何处理器，BusinessException 直接穿透到 dispatcherServlet，
 * 变成 Spring 默认的 HTTP 500 + 英文报错（"Internal Server Error"），
 * 调用方（编排层/门户）只能展示无意义的原始 JSON。
 * 现在统一按项目契约返回 BaseResponse（HTTP 200 + 业务码非 0）：
 * 编排层 _unwrap 按业务码透出可读的中文文案，前端弹窗不再是天书。
 *
 * <p>注意：@Transactional 里的 BusinessException 照常触发回滚 ——
 * 处理器在事务代理之外，只负责把已抛出的异常翻译成响应。
 *
 * @author agent-platform
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 业务异常：可预期的校验/规则拒绝（如库存不足、层级方向不对、无权操作），
     * message 面向用户可直接展示，业务码原样透传。
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusinessException(BusinessException e) {
        log.error("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 兜底异常：不可预期的运行时错误，不外泄堆栈细节，只留日志供排查。
     */
    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleException(Exception e) {
        log.error("系统异常", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误，请稍后重试");
    }
}
