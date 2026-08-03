package com.code.feishu.controller;

import com.code.feishu.config.RabbitMQConfig;
import com.code.feishu.dto.MessageSendDTO;
import com.code.feishu.entity.MessageRecord;
import com.code.feishu.mapper.MessageRecordMapper;
import com.code.feishu.service.FeishuBitableService;
import com.code.feishu.service.MessageParserService;
import com.code.feishu.vo.MessageParseVO;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 接口。
 *
 *   POST /api/parse   解析完整短信 -> 拆分字段（只解析不发送，用于前端表单回填）
 *   POST /api/send    发送消息（两种模式：rawMessage 或 拆分字段）
 *                     同时做三件事：① 发群消息 ② 写入多维表格 ③ 存入本地数据库
 *   POST /api/sms     给 SmsForwarder 手机应用调用的接口
 *                     收到短信后自动解析 + 发群消息 + 写表格 + 存数据库
 *   GET  /api/ping    健康检查
 *   GET  /api/records 查询数据库里的历史记录
 */
@RestController
@RequestMapping("/api")
public class MessageController {

    private final MessageParserService parser;
    private final MessageRecordMapper recordMapper;
    private final RabbitTemplate rabbitTemplate;
    private final FeishuBitableService bitableService;

    public MessageController(MessageParserService parser,
                             MessageRecordMapper recordMapper,
                             RabbitTemplate rabbitTemplate,
                             FeishuBitableService bitableService) {
        this.parser = parser;
        this.recordMapper = recordMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.bitableService = bitableService;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true, "ts", System.currentTimeMillis());
    }

    @PostMapping("/parse")
    public MessageParseVO parse(@RequestBody Map<String, String> body) {
        String raw = body == null ? null : body.get("rawMessage");
        return parser.parse(raw);
    }

    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody MessageSendDTO dto) {
        // ① 解析字段（如果传的是 rawMessage，拆字段用于存库）
        MessageParseVO vo = parser.parse(dto.getRawMessage());
        fillDtoFromVo(dto, vo);

        // ② 存入数据库（状态=待处理，飞书/表格还没发）
        MessageRecord record = buildRecord(dto, vo, "manual");
        record.setStatus(MessageRecord.STATUS_PENDING);
        record.setFeishuSent(0);
        record.setBitableSent(0);
        record.setRetryCount(0);
        record.setSortNum(getNextSortNum());
        recordMapper.insert(record);

        // ③ 投递到 MQ（只发 recordId，消费者异步处理发飞书+写表格）
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                record.getId()
        );

        // ④ 立即返回（不等飞书/表格处理完）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("recordId", record.getId());
        result.put("msg", "消息已接收，正在异步处理");
        return result;
    }

    /**
     * 批量发送消息。
     *
     * 前端一次传多条消息（JSON 数组），后端：
     *   ① 批量解析每条消息
     *   ② saveBatch 一次性插入数据库（一条 SQL 插入多条，比循环 insert 高效）
     *   ③ 循环投递每条记录的 id 到 MQ（复用现有消费者，逐条异步处理）
     *
     * 请求体示例：
     *   [
     *     {"rawMessage": "尾号1234卡...9.89元...【工商银行】"},
     *     {"rawMessage": "尾号5678卡...50.00元...【建设银行】"}
     *   ]
     *
     * 也可以传拆分字段：
     *   [
     *     {"bank":"工商银行","amount":"9.89","merchant":"牛约堡",...},
     *     {"bank":"建设银行","amount":"50.00","merchant":"美团",...}
     *   ]
     */
    @PostMapping("/batch-send")
    public Map<String, Object> batchSend(@RequestBody List<MessageSendDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("msg", "消息列表为空");
            return err;
        }

        // 限制单次最多 100 条，防止恶意大批量
        if (dtoList.size() > 100) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("msg", "单次最多批量 100 条，当前：" + dtoList.size());
            return err;
        }

        // ① 批量解析 + 构建记录列表
        int nextSortNum = getNextSortNum();
        List<MessageRecord> records = new ArrayList<>();
        for (MessageSendDTO dto : dtoList) {
            MessageParseVO vo = parser.parse(dto.getRawMessage());
            fillDtoFromVo(dto, vo);
            MessageRecord record = buildRecord(dto, vo, "manual");
            record.setStatus(MessageRecord.STATUS_PENDING);
            record.setFeishuSent(0);
            record.setBitableSent(0);
            record.setRetryCount(0);
            record.setSortNum(nextSortNum++);
            records.add(record);
        }

        // ② 批量插入数据库（saveBatch = 一条 SQL 插入多条，比循环 insert 高效）
        Db.saveBatch(records);

        // ③ 循环投递到 MQ（复用现有消费者，逐条异步发飞书 + 写表格）
        for (MessageRecord record : records) {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY,
                    record.getId()
            );
        }

        // ④ 返回结果
        List<Long> ids = new ArrayList<>();
        for (MessageRecord r : records) {
            ids.add(r.getId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("count", records.size());
        result.put("recordIds", ids);
        result.put("msg", "批量接收成功，正在异步处理");
        return result;
    }

    /**
     * 获取下一个 sortNum（用户可见序号）。
     * 取当前最大值 +1，如果表空则从 1 开始。
     */
    private int getNextSortNum() {
        Integer max = recordMapper.selectObjs(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageRecord>()
                        .select(MessageRecord::getSortNum)
                        .orderByDesc(MessageRecord::getSortNum)
                        .last("LIMIT 1")
        ).stream()
                .filter(o -> o instanceof Integer)
                .map(o -> (Integer) o)
                .findFirst()
                .orElse(0);
        return max + 1;
    }

    /**
     * 查询数据库里的历史记录（最新的 N 条）。
     * 按 sortNum 降序（最新记录在前），用户看到的编号就是 sortNum。
     * 用法：GET /api/records?limit=20
     */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(defaultValue = "20") int limit) {
        if (limit <= 0 || limit > 500) limit = 20;
        var list = recordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageRecord>()
                        .orderByDesc(MessageRecord::getSortNum)
                        .last("LIMIT " + limit)
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("total", list.size());
        result.put("records", list);
        return result;
    }

    /**
     * 删除一条记录（按 ID）。
     * 同步删除飞书多维表格中的对应记录。
     * 用法：DELETE /api/records/{id}
     */
    @DeleteMapping("/records/{id}")
    public Map<String, Object> deleteRecord(@PathVariable Long id) {
        MessageRecord record = recordMapper.selectById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (record == null) {
            result.put("ok", false);
            result.put("msg", "记录不存在，id=" + id);
            return result;
        }

        // 先删飞书表格（如果有多维表格记录 ID）
        boolean bitableDeleted = false;
        if (record.getBitableRecordId() != null && !record.getBitableRecordId().isEmpty()) {
            try {
                String resp = bitableService.deleteRecord(record.getBitableRecordId());
                bitableDeleted = resp != null && resp.contains("\"code\":0");
            } catch (Exception e) {
                // 飞书删除失败不阻断数据库删除
            }
        }

        recordMapper.deleteById(id);
        result.put("ok", true);
        result.put("msg", "删除成功" + (bitableDeleted ? "（飞书表格已同步删除）" : ""));
        result.put("deleted", record);
        result.put("bitableDeleted", bitableDeleted);
        return result;
    }

    /**
     * 更新一条记录（按 ID）。
     * 同步更新飞书多维表格中的对应记录。
     * 只更新请求体里传了的字段，没传的不动。
     * 用法：PUT /api/records/{id}  body={"merchant":"嘉豪水果","amount":2}
     */
    @PutMapping("/records/{id}")
    public Map<String, Object> updateRecord(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MessageRecord record = recordMapper.selectById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (record == null) {
            result.put("ok", false);
            result.put("msg", "记录不存在，id=" + id);
            return result;
        }

        // 只更新前端传了的字段
        if (body.containsKey("merchant")) record.setMerchant((String) body.get("merchant"));
        if (body.containsKey("amount")) record.setAmount(new java.math.BigDecimal(body.get("amount").toString()));
        if (body.containsKey("balance")) record.setBalance(new java.math.BigDecimal(body.get("balance").toString()));
        if (body.containsKey("bank")) record.setBank((String) body.get("bank"));
        if (body.containsKey("cardTail")) record.setCardTail((String) body.get("cardTail"));
        if (body.containsKey("happenTime")) record.setHappenTime((String) body.get("happenTime"));
        if (body.containsKey("direction")) record.setDirection((String) body.get("direction"));
        if (body.containsKey("channel")) record.setChannel((String) body.get("channel"));
        if (body.containsKey("transType")) record.setTransType((String) body.get("transType"));

        recordMapper.updateById(record);

        // 同步更新飞书表格
        boolean bitableUpdated = false;
        if (record.getBitableRecordId() != null && !record.getBitableRecordId().isEmpty()) {
            try {
                MessageSendDTO dto = new MessageSendDTO();
                dto.setRawMessage(record.getRawMessage());
                dto.setBank(record.getBank());
                dto.setCardTail(record.getCardTail());
                dto.setHappenTime(record.getHappenTime());
                dto.setDirection(record.getDirection());
                dto.setChannel(record.getChannel());
                dto.setMerchant(record.getMerchant());
                dto.setAmount(record.getAmount());
                dto.setBalance(record.getBalance());
                dto.setTransType(record.getTransType());

                String resp = bitableService.updateRecord(record.getBitableRecordId(), dto);
                bitableUpdated = resp != null && resp.contains("\"code\":0");
            } catch (Exception e) {
                // 飞书更新失败不阻断数据库更新
            }
        }

        result.put("ok", true);
        result.put("msg", "更新成功" + (bitableUpdated ? "（飞书表格已同步更新）" : ""));
        result.put("record", record);
        result.put("bitableUpdated", bitableUpdated);
        return result;
    }

    /**
     * SmsForwarder 手机应用调用这个接口转发短信。
     *
     * SmsForwarder 收到短信后会 POST 到这里，
     * 后端自动解析短信内容，然后发飞书群消息 + 写多维表格，
     * 全自动，不需要人工操作。
     *
     * 支持多种请求格式（都能兼容，不用纠结 SmsForwarder 到底发的什么格式）：
     *   1) JSON Body（Content-Type: application/json）
     *   2) 表单（Content-Type: application/x-www-form-urlencoded）
     *   3) 纯文本 Body（Content-Type: text/plain）
     *   4) SmsForwarder 的默认 payload=xxxx 格式
     *
     * SmsForwarder 的请求体模板建议配成：
     *   {"content":"{{短信内容}}","from":"{{来源号码}}","time":"{{接收时间}}"}
     *
     * 本接口对字段名做了容错：content / msg / text / message 都能识别为短信内容。
     */
    @PostMapping(value = "/sms", consumes = {"application/json", "application/x-www-form-urlencoded", "text/plain", "*/*"})
    public Map<String, Object> sms(HttpServletRequest request) throws IOException {

        String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);

        String content = extractContent(request, rawBody);

        if (content == null || content.isBlank()) {
            return Map.of("code", -1, "msg", "未找到短信内容", "debug-body", rawBody);
        }

        // 构造 DTO
        MessageSendDTO dto = new MessageSendDTO();
        dto.setRawMessage(content);

        // 解析字段
        MessageParseVO vo = parser.parse(dto.getRawMessage());
        fillDtoFromVo(dto, vo);

        // 存库（状态=待处理，来源=sms）
        MessageRecord record = buildRecord(dto, vo, "sms");
        record.setStatus(MessageRecord.STATUS_PENDING);
        record.setFeishuSent(0);
        record.setBitableSent(0);
        record.setRetryCount(0);
        recordMapper.insert(record);

        // 投递到 MQ（消费者异步发飞书+写表格）
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                record.getId()
        );

        // 返回给 SmsForwarder（它只看 HTTP 200）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("msg", "success");
        result.put("recordId", record.getId());
        return result;
    }

    /**
     * 把解析结果 VO 里的字段填到 DTO 里（DTO 为空的字段才填，不覆盖前端传的值）。
     * 这样发飞书消息和写多维表格时，字段是完整的。
     */
    private void fillDtoFromVo(MessageSendDTO dto, MessageParseVO vo) {
        if (vo == null) return;
        if (dto.getBank() == null) dto.setBank(vo.getBank());
        if (dto.getCardTail() == null) dto.setCardTail(vo.getCardTail());
        if (dto.getHappenTime() == null) dto.setHappenTime(vo.getHappenTime());
        if (dto.getDirection() == null) dto.setDirection(vo.getDirection());
        if (dto.getChannel() == null) dto.setChannel(vo.getChannel());
        if (dto.getMerchant() == null) dto.setMerchant(vo.getMerchant());
        if (dto.getAmount() == null) dto.setAmount(vo.getAmount());
        if (dto.getBalance() == null) dto.setBalance(vo.getBalance());
        if (dto.getTransType() == null) dto.setTransType(vo.getTransType());
    }

    /**
     * 把 DTO + VO 的数据组装成数据库实体。
     */
    private MessageRecord buildRecord(MessageSendDTO dto, MessageParseVO vo, String source) {
        MessageRecord r = new MessageRecord();
        r.setRawMessage(dto.getRawMessage());
        r.setBank(dto.getBank() != null ? dto.getBank() : (vo != null ? vo.getBank() : null));
        r.setCardTail(dto.getCardTail() != null ? dto.getCardTail() : (vo != null ? vo.getCardTail() : null));
        r.setHappenTime(dto.getHappenTime() != null ? dto.getHappenTime() : (vo != null ? vo.getHappenTime() : null));
        r.setDirection(dto.getDirection() != null ? dto.getDirection() : (vo != null ? vo.getDirection() : null));
        r.setChannel(dto.getChannel() != null ? dto.getChannel() : (vo != null ? vo.getChannel() : null));
        r.setMerchant(dto.getMerchant() != null ? dto.getMerchant() : (vo != null ? vo.getMerchant() : null));
        r.setAmount(dto.getAmount() != null ? dto.getAmount() : (vo != null ? vo.getAmount() : null));
        r.setBalance(dto.getBalance() != null ? dto.getBalance() : (vo != null ? vo.getBalance() : null));
        r.setTransType(dto.getTransType() != null ? dto.getTransType() : (vo != null ? vo.getTransType() : null));
        r.setSource(source);
        // feishuSent / bitableSent / status / retryCount 在调用处设置
        return r;
    }

    /**
     * 从各种可能的请求体格式里提取短信内容。
     */
    private String extractContent(HttpServletRequest request, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;

        String contentType = request.getContentType() != null ? request.getContentType().toLowerCase() : "";

        // 情况 1：JSON 格式
        if (contentType.contains("application/json") || rawBody.trim().startsWith("{")) {
            try {
                cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(rawBody);
                for (String key : new String[]{"content", "msg", "text", "message", "sms", "data"}) {
                    if (json.containsKey(key)) {
                        Object val = json.get(key);
                        if (val != null && !val.toString().isBlank()) {
                            return val.toString();
                        }
                    }
                }
                // JSON 里没有命中字段，但整个 JSON 只有一个字符串值，也兜底用
                if (json.size() > 0) {
                    // 尝试随便拿第一个非空字符串
                    for (Object v : json.values()) {
                        if (v instanceof String s && !s.isBlank()) return s;
                    }
                }
            } catch (Exception ignored) { }
        }

        // 情况 2：表单格式 / SmsForwarder 默认 payload=xxxx 格式
        if (contentType.contains("x-www-form-urlencoded") || rawBody.contains("=") && !rawBody.startsWith("{")) {
            String[] pairs = rawBody.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                // URL decode
                try {
                    k = URLDecoder.decode(k, StandardCharsets.UTF_8);
                    v = URLDecoder.decode(v, StandardCharsets.UTF_8);
                } catch (Exception ignored) { }
                // SmsForwarder 默认模板是 payload=xxx%7B...%7D，payload 里的值可能还嵌套了 JSON
                if ("payload".equalsIgnoreCase(k)) {
                    // 看看 payload 里面是不是 JSON
                    if (v.trim().startsWith("{")) {
                        try {
                            cn.hutool.json.JSONObject inner = cn.hutool.json.JSONUtil.parseObj(v);
                            for (String key : new String[]{"content", "msg", "text", "message", "sms"}) {
                                if (inner.containsKey(key)) {
                                    return inner.getStr(key);
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                    // 不是 JSON 就直接用 payload 的值
                    if (!v.isBlank()) return v;
                }
                // 普通表单字段命中
                for (String ck : new String[]{"content", "msg", "text", "message", "sms"}) {
                    if (ck.equalsIgnoreCase(k) && !v.isBlank()) {
                        return v;
                    }
                }
            }
        }

        // 情况 3：纯文本 —— 直接把整个 Body 当短信内容
        if (!rawBody.isBlank()) {
            return rawBody.trim();
        }
        return null;
    }
}
