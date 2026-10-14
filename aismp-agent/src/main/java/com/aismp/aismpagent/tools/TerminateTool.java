package com.aismp.aismpagent.tools;

// @Tool 注解：声明一个可被 AI 调用的工具
import org.springframework.ai.tool.annotation.Tool;
// Spring 组件注解
import org.springframework.stereotype.Component;

/**
 * 终止工具，让智能体可以自行决定任务结束
 *
 * 当大模型认为任务已完成时，会调用这个工具，
 * ToolCallAgent.act() 检测到 doTerminate 后会将状态改为 FINISHED。
 */
@Component
public class TerminateTool {

    /**
     * 终止交互
     *
     * @return 固定返回"任务结束"
     */
    @Tool(description = "Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task. " +
            "When you have finished all the tasks, call this tool to end the work.")
    public String doTerminate() {
        // 返回固定字符串
        return "任务结束";
    }
}
