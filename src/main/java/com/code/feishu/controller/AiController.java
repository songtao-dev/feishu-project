package com.code.feishu.controller;

import com.code.feishu.ai.dto.AiCommandResult;
import com.code.feishu.ai.dto.AiParseResult;
import com.code.feishu.ai.service.AiCommandService;
import com.code.feishu.ai.service.AiParseService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 相关接口。
 *
 *   POST /api/ai-parse    用大模型解析自然语言文本为结构化消费记录
 *   POST /api/ai-command  用大模型理解指令并执行（删除/更新/查询记录）
 */
@RestController
@RequestMapping("/api")
public class AiController {

    private final AiParseService aiParseService;
    private final AiCommandService aiCommandService;

    public AiController(AiParseService aiParseService, AiCommandService aiCommandService) {
        this.aiParseService = aiParseService;
        this.aiCommandService = aiCommandService;
    }

    /**
     * 用大模型解析自然语言文本。
     *
     * 请求：{"text": "我买了个水果花了13块"}
     * 返回：{"success":true,"bank":null,"merchant":"水果","amount":13,...}
     *
     * 和 /api/parse（正则解析）的区别：
     *   - /api/parse   只能识别银行短信固定格式，快速免费
     *   - /api/ai-parse 能理解任意自然语言，但有大模型调用延迟
     */
    @PostMapping("/ai-parse")
    public Map<String, Object> aiParse(@RequestBody Map<String, String> body) {
        String text = body == null ? null : body.get("text");

        AiParseResult result = aiParseService.parse(text);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.isSuccess());
        resp.put("bank", result.getBank());
        resp.put("cardTail", result.getCardTail());
        resp.put("happenTime", result.getHappenTime());
        resp.put("direction", result.getDirection());
        resp.put("channel", result.getChannel());
        resp.put("merchant", result.getMerchant());
        resp.put("amount", result.getAmount());
        resp.put("balance", result.getBalance());
        resp.put("transType", result.getTransType());
        if (!result.isSuccess()) {
            resp.put("errorMsg", result.getErrorMsg());
        }
        return resp;
    }

    /**
     * AI 指令理解 + 执行。
     *
     * 用户用自然语言描述操作，AI 理解意图并执行。
     *
     * 请求：{"text": "帮我删除第三条记录"}
     * 返回：{"success":true,"action":"delete","reply":"已删除记录：水果 13元",...}
     *
     * 支持的指令示例：
     *   - "帮我删除第三条记录"
     *   - "帮我删除刚刚那条"
     *   - "帮我删除嘉兴水果那条"
     *   - "帮我更新嘉兴水果为嘉豪水果"
     *   - "刚才不是3块是2块"
     *   - "查询所有记录"
     */
    @PostMapping("/ai-command")
    public Map<String, Object> aiCommand(@RequestBody Map<String, String> body) {
        String text = body == null ? null : body.get("text");

        AiCommandResult result = aiCommandService.execute(text);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.isSuccess());
        resp.put("action", result.getAction());
        resp.put("reply", result.getReply());
        resp.put("result", result.getResult());
        if (!result.isSuccess() && result.getErrorMsg() != null) {
            resp.put("errorMsg", result.getErrorMsg());
        }
        return resp;
    }
}
