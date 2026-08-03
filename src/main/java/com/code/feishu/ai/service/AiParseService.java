package com.code.feishu.ai.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.code.feishu.ai.client.AiClient;
import com.code.feishu.ai.dto.AiParseResult;
import com.code.feishu.ai.prompt.PromptTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 语义解析服务。
 *
 * 调用 Qwen 大模型，把自然语言文本解析成结构化消费记录。
 *
 * 和 MessageParserService（正则解析）的关系：
 *   - MessageParserService：快速、免费，专门处理银行短信固定格式
 *   - AiParseService：通用，能处理任意自然语言（"买水果花了13块"），但有 API 调用成本
 *
 * 容错设计：
 *   1. AI 返回非 JSON（可能包含 markdown 代码块）→ 自动提取 JSON 部分
 *   2. AI 超时/不可用 → 返回 success=false，前端可降级到正则解析
 *   3. JSON 字段缺失 → 填 null
 */
@Service
public class AiParseService {

    private static final Logger log = LoggerFactory.getLogger(AiParseService.class);

    private final AiClient aiClient;

    public AiParseService(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    /**
     * 解析自然语言文本为结构化消费记录。
     *
     * @param text 用户输入的自然语言（如"我买了个水果花了13块"）
     * @return 解析结果，success=false 表示解析失败
     */
    public AiParseResult parse(String text) {
        AiParseResult result = new AiParseResult();

        if (text == null || text.isBlank()) {
            result.setSuccess(false);
            result.setErrorMsg("输入为空");
            return result;
        }

        // 构造 user message，注入当前日期让 AI 能推算"昨天"等相对时间
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String userMessage = "当前日期：" + today + "\n用户输入：" + text;

        // 调用 AI
        log.info("[AI] 开始解析: {}", text);
        String aiResponse = aiClient.chat(PromptTemplates.PARSE_SYSTEM, userMessage);

        if (aiResponse == null || aiResponse.isBlank()) {
            result.setSuccess(false);
            result.setErrorMsg("AI 服务不可用或返回为空");
            log.warn("[AI] 返回为空");
            return result;
        }

        result.setRawResponse(aiResponse);

        // 从 AI 返回中提取 JSON（AI 可能返回纯 JSON，也可能包裹在 markdown 中）
        String jsonStr = extractJson(aiResponse);
        if (jsonStr == null) {
            result.setSuccess(false);
            result.setErrorMsg("AI 返回非 JSON 格式");
            log.warn("[AI] 返回非JSON: {}", aiResponse);
            return result;
        }

        try {
            JSONObject json = JSONUtil.parseObj(jsonStr);
            result.setBank(json.getStr("bank"));
            result.setCardTail(json.getStr("cardTail"));
            result.setHappenTime(json.getStr("happenTime"));
            result.setDirection(json.getStr("direction"));
            result.setChannel(json.getStr("channel"));
            result.setMerchant(json.getStr("merchant"));
            result.setAmount(json.getBigDecimal("amount"));
            result.setBalance(json.getBigDecimal("balance"));
            result.setTransType(json.getStr("transType"));
            result.setSuccess(true);

            log.info("[AI] 解析成功: amount={}, merchant={}, direction={}",
                    result.getAmount(), result.getMerchant(), result.getDirection());
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMsg("JSON 解析失败: " + e.getMessage());
            log.error("[AI] JSON解析失败: {}", jsonStr, e);
        }

        return result;
    }

    /**
     * 从 AI 返回文本中提取 JSON。
     *
     * AI 可能返回：
     *   1. 纯 JSON：{"bank":null,...}
     *   2. markdown 包裹：```json\n{...}\n```
     *   3. 带其他文字：好的，结果是 {...}
     */
    private String extractJson(String response) {
        if (response == null) return null;

        String trimmed = response.trim();

        // 情况 1：直接就是 JSON（最理想情况）
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // 情况 2 & 3：找到第一个 { 和最后一个 }，截取中间部分
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return null;
    }
}
