package com.code.feishu.ai.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.code.feishu.ai.config.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Qwen 大模型 API 客户端。
 *
 * 封装 HTTP 调用细节，对上层提供简洁的 chat 接口。
 * 兼容 OpenAI 格式的 /chat/completions 接口。
 *
 * 后续扩展 function calling 时，在这里加带 tools 参数的方法：
 *   public String chatWithTools(String systemPrompt, String userMessage, JSONArray tools) { ... }
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private final AiConfig aiConfig;

    public AiClient(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    /**
     * 调用大模型 chat 接口（单轮对话）。
     *
     * @param systemPrompt 系统提示词（定义 AI 角色和任务）
     * @param userMessage  用户输入
     * @return AI 返回的文本内容，失败返回 null
     */
    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, null);
    }

    /**
     * 调用大模型 chat 接口（支持多轮对话）。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户输入
     * @param history      历史对话消息（可为 null），每条是 {"role":"user/assistant","content":"xxx"}
     * @return AI 返回的文本内容，失败返回 null
     */
    public String chat(String systemPrompt, String userMessage, List<JSONObject> history) {
        // 构造 messages 数组
        JSONArray messages = new JSONArray();

        // system message
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JSONObject sys = new JSONObject();
            sys.set("role", "system");
            sys.set("content", systemPrompt);
            messages.add(sys);
        }

        // 历史对话
        if (history != null) {
            for (JSONObject msg : history) {
                messages.add(msg);
            }
        }

        // 当前用户输入
        JSONObject user = new JSONObject();
        user.set("role", "user");
        user.set("content", userMessage);
        messages.add(user);

        // 构造请求体
        JSONObject body = new JSONObject();
        body.set("model", aiConfig.getModel());
        body.set("temperature", aiConfig.getTemperature());
        body.set("messages", messages);

        // 调用 API
        String url = aiConfig.getBaseUrl() + "/chat/completions";
        try {
            log.debug("[AI] 请求: model={}, messages={}", aiConfig.getModel(), messages.size());

            String resp = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + aiConfig.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(aiConfig.getTimeout())
                    .body(body.toString())
                    .execute()
                    .body();

            // 解析响应
            JSONObject json = JSONUtil.parseObj(resp);
            JSONArray choices = json.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject message = firstChoice.getJSONObject("message");
                if (message != null) {
                    String content = message.getStr("content");
                    log.debug("[AI] 返回: {}", content);
                    return content;
                }
            }

            // 响应格式异常（可能是 API Key 错误、额度不足等）
            log.error("[AI] 响应异常: {}", resp);
            return null;
        } catch (Exception e) {
            log.error("[AI] 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
