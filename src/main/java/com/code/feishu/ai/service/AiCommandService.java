package com.code.feishu.ai.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.ai.client.AiClient;
import com.code.feishu.ai.dto.AiCommandResult;
import com.code.feishu.ai.prompt.PromptTemplates;
import com.code.feishu.entity.MessageRecord;
import com.code.feishu.mapper.MessageRecordMapper;
import com.code.feishu.service.FeishuBitableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 指令理解 + 执行服务。
 *
 * 用户用自然语言描述操作（"帮我删除第三条记录"），
 * AI 理解意图返回结构化指令，本服务执行指令并返回结果。
 *
 * 支持的操作：
 *   - delete  删除记录（按序号/最新/商家名/ID）
 *   - update  更新记录（按序号/最新/商家名/ID）
 *   - query   查询记录
 *   - unknown 无法理解
 *
 * 为了让 AI 能理解"第三条"是哪一条，会把最近 N 条记录作为上下文传给 AI。
 */
@Service
public class AiCommandService {

    private static final Logger log = LoggerFactory.getLogger(AiCommandService.class);

    /** 给 AI 看的记录上下文条数 */
    private static final int CONTEXT_LIMIT = 20;

    /** 异步任务结果存储（taskId → task），5分钟后自动过期 */
    private static final Map<String, com.code.feishu.ai.dto.AiCommandTask> TASK_STORE = new ConcurrentHashMap<>();

    private final AiClient aiClient;
    private final MessageRecordMapper recordMapper;
    private final FeishuBitableService bitableService;

    public AiCommandService(AiClient aiClient, MessageRecordMapper recordMapper, FeishuBitableService bitableService) {
        this.aiClient = aiClient;
        this.recordMapper = recordMapper;
        this.bitableService = bitableService;
    }

    /**
     * 异步提交指令，立即返回 taskId。
     * 前端用 taskId 轮询 getTask() 获取结果。
     */
    public String submitCommand(String userInput) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        com.code.feishu.ai.dto.AiCommandTask task = new com.code.feishu.ai.dto.AiCommandTask();
        task.setTaskId(taskId);
        task.setStatus("pending");
        task.setReply("处理中...");
        task.setCreateTime(System.currentTimeMillis());
        TASK_STORE.put(taskId, task);

        // 异步执行（不阻塞当前线程）
        CompletableFuture.runAsync(() -> {
            try {
                AiCommandResult result = execute(userInput);
                task.setStatus(result.isSuccess() ? "success" : "error");
                task.setReply(result.getReply());
            } catch (Exception e) {
                log.error("AI 指令异步处理失败", e);
                task.setStatus("error");
                task.setReply("处理失败：" + e.getMessage());
            }
        });

        return taskId;
    }

    /**
     * 查询异步任务结果。5分钟后自动清理。
     */
    public com.code.feishu.ai.dto.AiCommandTask getTask(String taskId) {
        com.code.feishu.ai.dto.AiCommandTask task = TASK_STORE.get(taskId);
        if (task == null) return null;
        // 5分钟过期清理
        if (task.getCreateTime() < System.currentTimeMillis() - 5 * 60 * 1000) {
            TASK_STORE.remove(taskId);
            return null;
        }
        return task;
    }

    /**
     * 理解用户指令并执行。
     *
     * @param userInput 用户的自然语言输入
     * @return 执行结果
     */
    public AiCommandResult execute(String userInput) {
        AiCommandResult result = new AiCommandResult();

        if (userInput == null || userInput.isBlank()) {
            result.setSuccess(false);
            result.setErrorMsg("输入为空");
            return result;
        }

        // 1. 查询最近的记录作为上下文（按 sortNum 降序，最新的在前）
        List<MessageRecord> recentRecords = recordMapper.selectList(
                new LambdaQueryWrapper<MessageRecord>()
                        .orderByDesc(MessageRecord::getSortNum)
                        .last("LIMIT " + CONTEXT_LIMIT)
        );

        // 2. 构造上下文文本（给 AI 看的记录列表，用 sortNum 作为编号）
        String recordsContext = buildRecordsContext(recentRecords);

        // 3. 构造 user message
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String userMessage = "当前日期：" + today + "\n\n"
                + "最近的记录列表（序号从1开始，1是最新的一条）：\n"
                + recordsContext + "\n\n"
                + "用户输入：" + userInput;

        // 4. 调用 AI
        log.info("[AI-Command] 用户输入: {}", userInput);
        String aiResponse = aiClient.chat(PromptTemplates.COMMAND_SYSTEM, userMessage);

        if (aiResponse == null || aiResponse.isBlank()) {
            result.setSuccess(false);
            result.setErrorMsg("AI 服务不可用");
            return result;
        }

        result.setRawResponse(aiResponse);

        // 5. 提取 JSON
        String jsonStr = extractJson(aiResponse);
        if (jsonStr == null) {
            result.setSuccess(false);
            result.setErrorMsg("AI 返回非 JSON 格式");
            log.warn("[AI-Command] 返回非JSON: {}", aiResponse);
            return result;
        }

        // 6. 解析指令并执行
        try {
            JSONObject json = JSONUtil.parseObj(jsonStr);
            String action = json.getStr("action");
            result.setAction(action);

            if ("unknown".equals(action)) {
                result.setSuccess(true);
                result.setReply(json.getStr("reply"));
                return result;
            }

            // 解析 target
            JSONObject target = json.getJSONObject("target");
            if (target != null) {
                result.setTargetType(target.getStr("type"));
                Object value = target.get("value");
                result.setTargetValue(value != null ? value.toString() : null);
            }

            // 解析 fields（update 时）
            JSONObject fieldsJson = json.getJSONObject("fields");
            if (fieldsJson != null) {
                Map<String, Object> fields = new HashMap<>();
                for (String key : fieldsJson.keySet()) {
                    fields.put(key, fieldsJson.get(key));
                }
                result.setFields(fields);
            }

            // 7. 执行指令
            switch (action) {
                case "delete" -> executeDelete(result, recentRecords);
                case "update" -> executeUpdate(result, recentRecords);
                case "query" -> executeQuery(result);
                default -> {
                    result.setSuccess(false);
                    result.setErrorMsg("不支持的操作: " + action);
                }
            }

            log.info("[AI-Command] 执行完成: action={}, success={}", action, result.isSuccess());
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMsg("指令解析/执行失败: " + e.getMessage());
            log.error("[AI-Command] 执行失败", e);
        }

        return result;
    }

    // ==================== 指令执行 ====================

    /**
     * 执行删除操作。同步删除飞书表格记录。
     */
    private void executeDelete(AiCommandResult result, List<MessageRecord> records) {
        MessageRecord target = findTarget(result, records);
        if (target == null) {
            result.setSuccess(false);
            result.setReply("没有找到对应的记录");
            return;
        }

        // 同步删除飞书表格
        boolean bitableDeleted = false;
        if (target.getBitableRecordId() != null && !target.getBitableRecordId().isEmpty()) {
            try {
                String resp = bitableService.deleteRecord(target.getBitableRecordId());
                bitableDeleted = resp != null && resp.contains("\"code\":0");
            } catch (Exception e) {
                log.warn("[AI-Command] 删除飞书表格失败, recordId={}", target.getId(), e);
            }
        }

        recordMapper.deleteById(target.getId());
        result.setSuccess(true);
        result.setResult(target);
        result.setReply("已删除记录：" + describeRecord(target) + (bitableDeleted ? "（飞书表格已同步）" : ""));
    }

    /**
     * 执行更新操作。
     */
    private void executeUpdate(AiCommandResult result, List<MessageRecord> records) {
        MessageRecord target = findTarget(result, records);
        if (target == null) {
            result.setSuccess(false);
            result.setReply("没有找到对应的记录");
            return;
        }

        Map<String, Object> fields = result.getFields();
        if (fields == null || fields.isEmpty()) {
            result.setSuccess(false);
            result.setReply("没有指定要更新的字段");
            return;
        }

        // 应用更新
        StringBuilder changes = new StringBuilder();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            switch (key) {
                case "merchant" -> {
                    target.setMerchant(val.toString());
                    changes.append("商家→").append(val).append(" ");
                }
                case "amount" -> {
                    target.setAmount(new BigDecimal(val.toString()));
                    changes.append("金额→").append(val).append(" ");
                }
                case "balance" -> {
                    target.setBalance(new BigDecimal(val.toString()));
                    changes.append("余额→").append(val).append(" ");
                }
                case "direction" -> {
                    target.setDirection(val.toString());
                    changes.append("方向→").append(val).append(" ");
                }
                case "channel" -> {
                    target.setChannel(val.toString());
                    changes.append("渠道→").append(val).append(" ");
                }
                case "happenTime" -> {
                    target.setHappenTime(val.toString());
                    changes.append("时间→").append(val).append(" ");
                }
                case "bank" -> {
                    target.setBank(val.toString());
                    changes.append("银行→").append(val).append(" ");
                }
                default -> log.warn("[AI-Command] 未知字段: {}", key);
            }
        }

        recordMapper.updateById(target);

        // 同步更新飞书表格
        boolean bitableUpdated = false;
        if (target.getBitableRecordId() != null && !target.getBitableRecordId().isEmpty()) {
            try {
                com.code.feishu.dto.MessageSendDTO dto = new com.code.feishu.dto.MessageSendDTO();
                dto.setRawMessage(target.getRawMessage());
                dto.setBank(target.getBank());
                dto.setCardTail(target.getCardTail());
                dto.setHappenTime(target.getHappenTime());
                dto.setDirection(target.getDirection());
                dto.setChannel(target.getChannel());
                dto.setMerchant(target.getMerchant());
                dto.setAmount(target.getAmount());
                dto.setBalance(target.getBalance());
                dto.setTransType(target.getTransType());

                String resp = bitableService.updateRecord(target.getBitableRecordId(), dto);
                bitableUpdated = resp != null && resp.contains("\"code\":0");
            } catch (Exception e) {
                log.warn("[AI-Command] 更新飞书表格失败, recordId={}", target.getId(), e);
            }
        }

        result.setSuccess(true);
        result.setResult(target);
        result.setReply("已更新记录：" + changes.toString().trim() + (bitableUpdated ? "（飞书表格已同步）" : ""));
    }

    /**
     * 执行查询操作。
     */
    private void executeQuery(AiCommandResult result) {
        List<MessageRecord> all = recordMapper.selectList(
                new LambdaQueryWrapper<MessageRecord>()
                        .orderByDesc(MessageRecord::getSortNum)
                        .last("LIMIT 50")
        );
        result.setSuccess(true);
        result.setResult(all);
        result.setReply("共查询到 " + all.size() + " 条记录");
    }

    // ==================== 工具方法 ====================

    /**
     * 根据 target 定位记录。按 sortNum（用户可见编号）来定位。
     */
    private MessageRecord findTarget(AiCommandResult result, List<MessageRecord> records) {
        String type = result.getTargetType();
        String value = result.getTargetValue();

        if (type == null) return null;

        return switch (type) {
            case "latest" -> records.isEmpty() ? null : records.get(0);
            case "index" -> {
                if (value == null) yield null;
                try {
                    int sortNum = Integer.parseInt(value);
                    // 按 sortNum 精确匹配
                    yield records.stream()
                            .filter(r -> r.getSortNum() != null && r.getSortNum() == sortNum)
                            .findFirst()
                            .orElse(null);
                } catch (NumberFormatException ignored) {}
                yield null;
            }
            case "id" -> {
                if (value == null) yield null;
                try {
                    yield recordMapper.selectById(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
            case "merchant" -> {
                if (value == null) yield null;
                // 模糊匹配商家名
                for (MessageRecord r : records) {
                    if (r.getMerchant() != null && r.getMerchant().contains(value)) {
                        yield r;
                    }
                }
                // 数据库里再查一次
                List<MessageRecord> found = recordMapper.selectList(
                        new LambdaQueryWrapper<MessageRecord>()
                                .like(MessageRecord::getMerchant, value)
                                .orderByDesc(MessageRecord::getId)
                                .last("LIMIT 1")
                );
                yield found.isEmpty() ? null : found.get(0);
            }
            default -> null;
        };
    }

    /**
     * 构造给 AI 看的记录列表上下文。
     * 用 sortNum 作为编号，用户说"第3条"时 AI 返回 index=3，
     * 后端按 sortNum=3 精确定位。
     */
    private String buildRecordsContext(List<MessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return "（暂无记录）";
        }

        StringBuilder sb = new StringBuilder();
        for (MessageRecord r : records) {
            sb.append("#").append(r.getSortNum()).append(" ");
            if (r.getHappenTime() != null) sb.append(r.getHappenTime()).append(" ");
            if (r.getMerchant() != null) sb.append(r.getMerchant()).append(" ");
            if (r.getAmount() != null) sb.append(r.getAmount()).append("元 ");
            if (r.getDirection() != null) sb.append("(").append(r.getDirection()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 描述一条记录（用于回复用户）。
     */
    private String describeRecord(MessageRecord r) {
        StringBuilder sb = new StringBuilder();
        if (r.getMerchant() != null) sb.append(r.getMerchant()).append(" ");
        if (r.getAmount() != null) sb.append(r.getAmount()).append("元 ");
        if (r.getHappenTime() != null) sb.append(r.getHappenTime());
        return sb.toString().trim();
    }

    /**
     * 从 AI 返回中提取 JSON（复用 AiParseService 的逻辑）。
     */
    private String extractJson(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }
}
