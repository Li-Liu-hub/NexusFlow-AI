package com.aismp.aismpagent.model;

/**
 * 代理执行状态的枚举类
 * 控制智能体的生命周期：空闲 → 运行中 → 已完成/错误
 */
public enum AgentState {

    /**
     * 空闲状态：智能体还没开始执行，可以启动
     */
    IDLE,

    /**
     * 运行中状态：智能体正在执行 Agent Loop，不能重复启动
     */
    RUNNING,

    /**
     * 已完成状态：智能体执行结束（正常完成 或 调用了终止工具 或 步数用完）
     */
    FINISHED,

    /**
     * 错误状态：智能体执行过程中抛了异常
     */
    ERROR
}
