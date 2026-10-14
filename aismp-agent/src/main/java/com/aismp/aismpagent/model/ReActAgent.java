package com.aismp.aismpagent.model;

// Lombok 注解，自动生成 getter/setter，并包含父类的 equals/hashCode
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 *
 * 核心设计：模板方法模式 —— 将 step() 拆分为 think() + act() 两个抽象方法，
 * 子类（ToolCallAgent）负责具体实现。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class ReActAgent extends BaseAgent {

    /**
     * 处理当前状态并决定下一步行动 —— 抽象方法，由子类实现
     *
     * @return 是否需要执行行动，true 表示需要，false 表示不需要
     */
    public abstract boolean think();

    /**
     * 执行决定的行动 —— 抽象方法，由子类实现
     *
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：先思考，再决定是否行动
     *
     * @return 步骤执行结果
     */
    @Override
    public String step() {
        // 开始 try 块
        try {
            // ★ 调用 think()（由 ToolCallAgent 实现），大模型决定是否使用工具
            // 返回 true = 要调工具，false = 不用调
            boolean shouldAct = think();
            // 判断 think() 的返回值
            if (!shouldAct) {
                // think() 返回 false：大模型没有选工具，直接文字回答了
                // 暴露大模型的实际回答内容（而不是固定的"思考完成"文案）
                return getLastThinkText() != null ? getLastThinkText() : "思考完成 - 无需行动";
            }
            // think() 返回 true：大模型选了工具，★ 调用 act() 执行工具
            String actResult = act();
            // 如果大模型在选工具的同时还给了文字回答（常见于回答完直接 doTerminate），一并暴露
            if (getLastThinkText() != null && !getLastThinkText().isBlank()) {
                return getLastThinkText() + "\n\n" + actResult;
            }
            // 没有文字回答，只返回工具执行结果
            return actResult;
        // 捕获异常
        } catch (Exception e) {
            // 打印异常堆栈
            e.printStackTrace();
            // 返回错误信息
            return "步骤执行失败: " + e.getMessage();
        }
    }
}
