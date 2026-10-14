package com.aismp.aismpagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于文件持久化的对话记忆
 * <p>
 * 实现 Spring AI 的 ChatMemory 接口，用 Kryo 序列化库将对话记录保存到本地文件。
 * 每个会话（conversationId）对应一个 .kryo 文件，重启后依然能恢复对话上下文。
 */
public class FileBasedChatMemory implements ChatMemory {

    // ==================== 字段定义 ====================

    // 文件存储根目录，如 "项目根目录/chat-memory/"
    private final String BASE_DIR;

    // Kryo 序列化器（静态共享，线程安全的读操作）
    private static final Kryo kryo = new Kryo();

    // ==================== 第一步：初始化 Kryo ====================
    static {
        // 不需要预先注册类，Kryo 自动处理任意 Java 类
        kryo.setRegistrationRequired(false);
        // 设置实例化策略：跳过构造函数创建对象
        // 原因：Spring AI 的 Message 子类（UserMessage / AssistantMessage）没有无参构造，
        //       Kryo 通过 StdInstantiatorStrategy 绕过构造函数直接分配内存
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    // ==================== 第二步：构造函数，指定存储目录 ====================
    public FileBasedChatMemory(String dir) {
        this.BASE_DIR = dir;
        // 目录不存在则自动创建，如 chat-memory/ 文件夹
        File baseDir = new File(dir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
    }

    // ==================== 第三步：实现 ChatMemory 接口的 3 个核心方法 ====================

    /**
     * 新增对话消息
     * <p>
     * 流程：读取已有消息 → 追加新消息 → 写回文件
     *
     * @param conversationId 会话 ID，同时也是文件名（conversationId.kryo）
     * @param messages       要追加的消息列表（包含用户消息和 AI 回复）
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        // 3.1 读取该会话已有的全部消息（从文件反序列化）
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        // 3.2 追加本轮的新消息
        conversationMessages.addAll(messages);
        // 3.3 将合并后的消息列表写回文件（序列化）
        saveConversation(conversationId, conversationMessages);
    }

    /**
     * 获取该会话的全部历史消息
     * <p>
     * MessageChatMemoryAdvisor 在请求前调用此方法，取出历史消息注入到 Prompt 中
     *
     * @param conversationId 会话 ID
     * @return 该会话的全部历史消息列表
     */
    @Override
    public List<Message> get(String conversationId) {
        // 直接返回文件中存储的全部消息
        // 注意：1.0.3 的 get 方法没有 lastN 参数，窗口控制由上层 MessageWindowChatMemory 负责
        return getOrCreateConversation(conversationId);
    }

    /**
     * 清空该会话的全部对话记录
     * <p>
     * 直接删除对应的 .kryo 文件
     *
     * @param conversationId 会话 ID
     */
    @Override
    public void clear(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            file.delete();
        }
    }

    // ==================== 第四步：私有方法 —— 文件读写（序列化/反序列化） ====================

    /**
     * 从文件读取该会话的消息列表（反序列化）
     * <p>
     * 文件不存在时返回空列表（首次对话的场景）
     */
    private List<Message> getOrCreateConversation(String conversationId) {
        File file = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        if (file.exists()) {
            // 文件存在 → 用 Kryo 反序列化读取
            try (Input input = new Input(new FileInputStream(file))) {
                messages = kryo.readObject(input, ArrayList.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return messages;
    }

    /**
     * 将消息列表写入文件（序列化）
     * <p>
     * 每次调用都会覆盖整个文件（因为 add 方法已经合并了新旧消息）
     */
    private void saveConversation(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(file))) {
            kryo.writeObject(output, messages);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==================== 第五步：私有方法 —— 文件路径映射 ====================

    /**
     * 根据 conversationId 获取对应的文件对象
     * <p>
     * 路径规则：{BASE_DIR}/{conversationId}.kryo
     * 例如：chat-memory/02c9e832-cd60-47ce-9449-225c52dd612c.kryo
     */
    private File getConversationFile(String conversationId) {
        return new File(BASE_DIR, conversationId + ".kryo");
    }
}
