package com.aismp.aismpagent.model;

// hutool 字符串工具，StrUtil.isBlank 判断 null、空字符串、纯空格
import cn.hutool.core.util.StrUtil;
// Lombok 注解，自动生成 getter/setter
import lombok.Data;
// Lombok 注解，自动生成日志对象 log
import lombok.extern.slf4j.Slf4j;
// Spring AI 的聊天客户端，用于调用大模型
import org.springframework.ai.chat.client.ChatClient;
// Spring AI 消息接口，所有消息类型的父类
import org.springframework.ai.chat.messages.Message;
// Spring AI 用户消息，代表用户发送的内容
import org.springframework.ai.chat.messages.UserMessage;
// Spring Web SSE 发射器，用于流式输出
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 *
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现 step 方法。
 *
 * 核心设计：模板方法模式 —— run() 固定了 Agent Loop 流程，
 * 具体的每一步逻辑由子类通过 step() 实现。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 智能体名称，用于日志标识
    private String name;

    // 系统提示词，每次调用大模型时作为 system 角色消息发送（定义 AI 的角色和行为）
    private String systemPrompt;

    // 下一步提示词，每一步 think() 开头作为 UserMessage 加入消息列表（提醒大模型该做什么）
    private String nextStepPrompt;

    // 当前执行状态，初始为空闲
    private AgentState state = AgentState.IDLE;

    // 最大步数限制，防止智能体无限循环浪费 token
    private int maxSteps = 10;

    // 当前执行的步数
    private int currentStep = 0;

    // Spring AI 聊天客户端，由子类（YuManus）创建并注入
    private ChatClient chatClient;

    // 消息上下文列表，贯穿整个智能体执行过程，保存所有历史消息（用户输入、AI回复、工具结果）
    private List<Message> messageList = new ArrayList<>();

    // 最近一次 think() 中大模型的文字回答（当大模型不选工具、直接文字回复时保存）
    // 供 ReActAgent.step() 在 think() 返回 false 时暴露给用户
    private String lastThinkText;

    /**
     * 运行代理 —— 智能体的入口方法
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 状态守卫：如果智能体不是空闲状态（比如正在运行中），直接抛异常，防止并发调用
        if (this.state != AgentState.IDLE) {
            // 抛出运行时异常
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        // 空值检查：用户提示词不能为空
        if (StrUtil.isBlank(userPrompt)) {
            // 抛出运行时异常
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 将状态从 IDLE 改为 RUNNING
        state = AgentState.RUNNING;
        // 将用户输入包装成 UserMessage，加入消息上下文列表
        messageList.add(new UserMessage(userPrompt));
        // 创建结果列表，保存每一步的执行结果
        List<String> results = new ArrayList<>();
        // 开始 try 块，捕获执行过程中的异常
        try {
            // ★★★ Agent Loop 核心循环
            // 循环条件：步数未超上限 且 状态不是 FINISHED
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                // 计算当前步数（从 1 开始显示）
                int stepNumber = i + 1;
                // 更新当前步数属性
                currentStep = stepNumber;
                // 打印日志：Executing step 1/20
                log.info("Executing step " + stepNumber + "/" + maxSteps);
                // ★ 调用 step()（抽象方法，由 ReActAgent 实现，内部会调 think() + act()）
                String stepResult = step();
                // 将本步结果格式化后加入结果列表
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 循环结束后的检查：如果步数用完了还没 FINISHED
            if (currentStep >= maxSteps) {
                // 强制标记为 FINISHED
                state = AgentState.FINISHED;
                // 记录终止信息
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            // 将所有步骤结果用换行符拼接成最终字符串返回
            return String.join("\n", results);
        // 捕获异常
        } catch (Exception e) {
            // 状态改为 ERROR
            state = AgentState.ERROR;
            // 记录错误日志
            log.error("Error executing agent", e);
            // 返回错误信息
            return "执行错误" + e.getMessage();
        // finally 块，无论成功还是失败都执行
        } finally {
            // 执行清理（默认空实现，子类可重写）
            this.cleanup();
        }
    }

    /**
     * 执行单个步骤 —— 抽象方法，由子类实现具体逻辑
     *
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 清理资源 —— 子类可以重写来释放资源
     */
    protected void cleanup() {
        // 默认空实现
    }

    /**
     * 运行代理（流式输出）—— 将每一步的执行结果通过 SseEmitter 实时推送给前端
     *
     * 与 run() 的区别：run() 等全部步骤完成后一次性返回，runStream() 每完成一步就推送
     *
     * @param userPrompt 用户提示词
     * @return SseEmitter 实例，前端通过 SSE 接收每步结果
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建 SseEmitter，设置 5 分钟超时（Agent 多步骤执行可能较慢）
        SseEmitter emitter = new SseEmitter(300000L);
        // 使用异步线程执行，避免阻塞 Web 服务器线程池
        CompletableFuture.runAsync(() -> {
            try {
                // 状态守卫
                if (this.state != AgentState.IDLE) {
                    emitter.send("错误：无法从状态运行代理: " + this.state);
                    emitter.complete();
                    return;
                }
                // 空值检查
                if (StrUtil.isBlank(userPrompt)) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
                    return;
                }
                // 更改状态为运行中
                state = AgentState.RUNNING;
                // 记录用户消息到上下文
                messageList.add(new UserMessage(userPrompt));
                // 开始 Agent Loop
                try {
                    for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                        int stepNumber = i + 1;
                        currentStep = stepNumber;
                        log.info("Executing step " + stepNumber + "/" + maxSteps);
                        // 执行单步
                        String stepResult = step();
                        String result = "Step " + stepNumber + ": " + stepResult;
                        // ★ 通过 SSE 推送本步结果
                        emitter.send(result);
                    }
                    // 步数耗尽
                    if (currentStep >= maxSteps) {
                        state = AgentState.FINISHED;
                        emitter.send("执行结束: 达到最大步骤 (" + maxSteps + ")");
                    }
                    // 正常完成
                    emitter.complete();
                } catch (Exception e) {
                    state = AgentState.ERROR;
                    log.error("执行智能体失败", e);
                    try {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                } finally {
                    this.cleanup();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        // 设置超时回调
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timed out");
        });
        // 设置完成回调
        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        // 返回 emitter
        return emitter;
    }
}
