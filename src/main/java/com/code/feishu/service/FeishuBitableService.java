package com.code.feishu.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.code.feishu.dto.MessageSendDTO;
import com.code.feishu.vo.MessageParseVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 调用飞书多维表格 API，把消费记录写入「收支记录表」。
 *
 * 流程：
 *   1) 用 App ID / App Secret 换 tenant_access_token（缓存 2 小时）
 *   2) 调用 bitable 新增记录接口写入一行
 *
 * 表格实际字段（通过 API 查到）：
 *   收支类型(单选) / 分类(单选) / 金额(数字) / 时间(日期) / 备注(文本)
 *   记录编号(自动编号-不可写) / 结余金额(公式-不可写)
 */
@Service
public class FeishuBitableService {

    @Value("${feishu.app.id}")
    private String appId;
    @Value("${feishu.app.secret}")
    private String appSecret;
    @Value("${feishu.bitable.app-token}")
    private String appToken;
    @Value("${feishu.bitable.table-id}")
    private String tableId;

    private final MessageParserService parserService;

    /** token 缓存（飞书 token 有效期 7200 秒） */
    private String cachedToken;
    private long tokenExpireAt;

    public FeishuBitableService(MessageParserService parserService) {
        this.parserService = parserService;
    }

    /**
     * 写入一条记录到多维表格。
     * @return 飞书 API 原始响应 JSON（成功时 code=0）
     */
    public String writeRecord(MessageSendDTO req) {
        FieldHolder f = normalize(req);

        // 组装 fields —— 字段名必须和表格里一字不差
        Map<String, Object> fields = new HashMap<>();

        // 收支类型：支出 / 收入
        if (StringUtils.hasText(f.direction)) {
            fields.put("收支类型", f.direction);
        }

        // 金额
        if (f.amount != null) {
            fields.put("金额", f.amount.doubleValue());
        }

        // 时间：飞书日期字段需要毫秒时间戳
        Long ts = toTimestamp(f.happenTime);
        if (ts != null) {
            fields.put("时间", ts);
        }

        // 分类：根据商家名/渠道自动推断
        fields.put("分类", guessCategory(f.merchant, f.channel));

        // 备注：把商家、渠道、银行、尾号、余额、原文都塞进去
        StringBuilder note = new StringBuilder();
        if (StringUtils.hasText(f.merchant)) note.append(f.merchant);
        if (StringUtils.hasText(f.channel))  note.append(" / ").append(f.channel);
        if (StringUtils.hasText(f.bank))     note.append(" / ").append(f.bank);
        if (StringUtils.hasText(f.cardTail)) note.append(" 尾号").append(f.cardTail);
        if (f.balance != null)               note.append(" / 余额¥").append(f.balance);
        if (StringUtils.hasText(f.raw))      note.append("\n[原文] ").append(f.raw);
        fields.put("备注", note.toString());

        // 调 API
        String token = getTenantAccessToken();
        JSONObject body = new JSONObject();
        body.set("fields", fields);

        String url = String.format(
                "https://open.feishu.cn/open-apis/bitable/v1/apps/%s/tables/%s/records",
                appToken, tableId);

        return HttpRequest.post(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body.toString())
                .execute()
                .body();
    }

    // ---------- token 管理 ----------

    private synchronized String getTenantAccessToken() {
        long now = System.currentTimeMillis();
        // 提前 5 分钟刷新，避免临界过期
        if (cachedToken != null && now < tokenExpireAt - 300_000) {
            return cachedToken;
        }

        JSONObject req = new JSONObject();
        req.set("app_id", appId);
        req.set("app_secret", appSecret);

        String resp = HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .header("Content-Type", "application/json")
                .body(req.toString())
                .execute()
                .body();

        JSONObject json = JSONUtil.parseObj(resp);
        cachedToken = json.getStr("tenant_access_token");
        int expire = json.getInt("expire", 7200);
        tokenExpireAt = now + expire * 1000L;
        return cachedToken;
    }

    // ---------- 工具方法 ----------

    /** 把 happenTime 字符串转成毫秒时间戳；解析不了就用当前时间 */
    private Long toTimestamp(String happenTime) {
        if (!StringUtils.hasText(happenTime)) {
            return System.currentTimeMillis();
        }
        String[] patterns = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm"};
        for (String p : patterns) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(happenTime, DateTimeFormatter.ofPattern(p));
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) { }
        }
        return System.currentTimeMillis();
    }

    /**
     * 根据商家名/渠道简单推断消费分类。
     * 表格选项：餐饮、交通、购物、住房、其他
     */
    private String guessCategory(String merchant, String channel) {
        String text = (merchant == null ? "" : merchant) + " " + (channel == null ? "" : channel);

        if (containsAny(text, "餐", "食", "饭", "面", "堡", "饮", "茶", "咖啡", "烘焙", "蛋糕",
                "外卖", "美团", "饿了么", "麦当劳", "肯德基", "星巴克", "瑞幸", "奶茶", "小吃", "烧烤", "火锅"))
            return "餐饮";
        if (containsAny(text, "交通", "打车", "地铁", "公交", "出行", "滴滴", "高铁", "机票",
                "停车", "加油", "铁路", "航空", "单车", "骑行"))
            return "交通";
        if (containsAny(text, "购物", "商城", "百货", "电商", "京东", "淘宝", "拼多多", "天猫",
                "超市", "便利", "711", "罗森"))
            return "购物";
        if (containsAny(text, "房", "租", "物业", "水电", "燃气", "宽频", "宽带"))
            return "住房";
        return "其他";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ---------- 字段归一化（和 FeishuBotService 同逻辑） ----------

    private FieldHolder normalize(MessageSendDTO req) {
        FieldHolder f = new FieldHolder();
        if (StringUtils.hasText(req.getRawMessage())) {
            f.raw = req.getRawMessage();
            MessageParseVO parsed = parserService.parse(req.getRawMessage());
            f.cardTail   = firstNonBlank(parsed.getCardTail(),   req.getCardTail());
            f.happenTime = firstNonBlank(parsed.getHappenTime(), req.getHappenTime());
            f.direction  = firstNonBlank(parsed.getDirection(),  req.getDirection(), req.getTransType());
            f.channel    = firstNonBlank(parsed.getChannel(),    req.getChannel());
            f.merchant   = firstNonBlank(parsed.getMerchant(),   req.getMerchant());
            f.amount     = firstNonNull(parsed.getAmount(),      req.getAmount());
            f.balance    = firstNonNull(parsed.getBalance(),     req.getBalance());
            f.bank       = firstNonBlank(parsed.getBank(),       req.getBank());
        } else {
            f.cardTail   = req.getCardTail();
            f.happenTime = req.getHappenTime();
            f.direction  = StringUtils.hasText(req.getTransType()) ? req.getTransType() : req.getDirection();
            f.channel    = req.getChannel();
            f.merchant   = req.getMerchant();
            f.amount     = req.getAmount();
            f.balance    = req.getBalance();
            f.bank       = req.getBank();
        }
        return f;
    }

    private String firstNonBlank(String... xs) {
        for (String x : xs) {
            if (StringUtils.hasText(x)) return x;
        }
        return null;
    }

    private BigDecimal firstNonNull(BigDecimal... xs) {
        for (BigDecimal x : xs) {
            if (x != null) return x;
        }
        return null;
    }

    private static class FieldHolder {
        String raw;
        String cardTail;
        String happenTime;
        String direction;
        String channel;
        String merchant;
        BigDecimal amount;
        BigDecimal balance;
        String bank;
    }
}
