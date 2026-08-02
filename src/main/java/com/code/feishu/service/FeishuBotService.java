package com.code.feishu.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import com.code.feishu.dto.MessageSendDTO;
import com.code.feishu.vo.MessageParseVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 负责把「原始短信」或「拆分字段」拼装成一条易读的飞书富文本（post）消息，
 * 并通过自定义机器人 webhook 发送。
 */
@Service
public class FeishuBotService {

    // 飞书自定义机器人 webhook，在 application.properties 里配置 feishu.bot.webhook
    @Value("${feishu.bot.webhook:}")
    private String webhook;

    private final MessageParserService parserService;

    public FeishuBotService(MessageParserService parserService) {
        this.parserService = parserService;
    }

    /**
     * 发送消息到飞书机器人。
     *   1) 如果带了 rawMessage：优先按解析后的字段拼（哪怕解析不完整，也会尽力呈现原始文本）
     *   2) 否则按拆分字段拼
     *
     * @return 飞书返回的原始响应（例如 {"StatusCode":0,"StatusMessage":"success"} ）
     */
    public String send(MessageSendDTO req) {
        // 先拿到一个「归一化的展示用对象」
        Display d = normalize(req);

        // 拼飞书 post（富文本）消息，PC 和手机上都好看
        JSONObject body = buildPostBody(d);

        return HttpRequest.post(webhook)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .execute()
                .body();
    }

    // ---------- 内部 ----------

    private Display normalize(MessageSendDTO req) {
        Display d = new Display();
        if (StringUtils.hasText(req.getRawMessage())) {
            d.raw = req.getRawMessage();
            MessageParseVO parsed = parserService.parse(req.getRawMessage());
            // 解析到的字段优先用，解析不到再看前端有没有单独传
            d.cardTail  = firstNonNull(parsed.getCardTail(),  req.getCardTail());
            d.happenTime= firstNonNull(parsed.getHappenTime(),req.getHappenTime());
            d.direction = firstNonNull(parsed.getDirection(), req.getDirection(), req.getTransType());
            d.channel   = firstNonNull(parsed.getChannel(),   req.getChannel());
            d.merchant  = firstNonNull(parsed.getMerchant(),  req.getMerchant());
            d.amount    = firstNonNull(parsed.getAmount(),    req.getAmount());
            d.balance   = firstNonNull(parsed.getBalance(),   req.getBalance());
            d.bank      = firstNonNull(parsed.getBank(),      req.getBank());
        } else {
            d.cardTail  = req.getCardTail();
            d.happenTime= req.getHappenTime();
            d.direction = StringUtils.hasText(req.getTransType()) ? req.getTransType() : req.getDirection();
            d.channel   = req.getChannel();
            d.merchant  = req.getMerchant();
            d.amount    = req.getAmount();
            d.balance   = req.getBalance();
            d.bank      = req.getBank();
        }
        return d;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... xs) {
        for (T x : xs) {
            if (x instanceof String s) {
                if (StringUtils.hasText(s)) return x;
            } else if (x != null) {
                return x;
            }
        }
        return null;
    }

    /**
     * 构造飞书 post 消息体。参考官方文档：
     *   https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot
     * post 消息在卡片/PC 端/移动端都比纯 text 更美观。
     */
    private JSONObject buildPostBody(Display d) {
        // 标题：[支出]9.89元 上海牛约堡餐饮集团有限公司
        String title = buildTitle(d);

        // 正文行（富文本数组）
        // 每行是一个 List<Map>，每个 Map 是 {tag:"text", content:"xxx"} 或 {tag:"a",...}
        List<Object> lines = Stream.of(
                line(label("🏦 银行："), plain(d.bank)),
                line(label("💳 卡号："), plain(d.cardTail == null ? null : "尾号 " + d.cardTail)),
                line(label("🕒 时间："), plain(d.happenTime)),
                line(label("💰 金额："), plain(d.amount == null ? null : d.direction + " ¥" + d.amount)),
                line(label("🧾 商家："), plain(d.merchant)),
                line(label("🛒 渠道："), plain(d.channel)),
                line(label("💵 余额："), plain(d.balance == null ? null : "¥" + d.balance))
        ).filter(l -> l != null && !l.isEmpty()).collect(Collectors.toList());

        // 如果原始短信还在，附在最后便于追溯
        if (StringUtils.hasText(d.raw)) {
            lines.add(line(label("📨 原文："), plain(d.raw)));
        }

        JSONObject zhCn = new JSONObject();
        zhCn.set("title", title);
        zhCn.set("content", lines);

        JSONObject post = new JSONObject();
        post.set("zh_cn", zhCn);

        JSONObject content = new JSONObject();
        content.set("post", post);

        JSONObject body = new JSONObject();
        body.set("msg_type", "post");
        body.set("content", content);
        return body;
    }

    private String buildTitle(Display d) {
        StringBuilder sb = new StringBuilder();
        if (d.direction != null) sb.append('[').append(d.direction).append(']');
        if (d.amount != null) sb.append('¥').append(d.amount);
        if (d.merchant != null) sb.append(' ').append(d.merchant);
        if (sb.isEmpty()) {
            // 一个字段都没命中时，退化为原文前 20 字
            String r = d.raw == null ? "新消息" : d.raw;
            return r.length() <= 20 ? r : r.substring(0, 20) + "…";
        }
        return sb.toString();
    }

    // 构建一行内容：[{tag:"text", content:"xxx"}, {tag:"text", content:"yyy"}]，会过滤空值
    private List<Object> line(JSONObject label, JSONObject value) {
        // 飞书 post 富文本：每个元素 {tag:"text", text:"xxx"}，文本字段名是 text
        if (value == null || !StringUtils.hasText(value.getStr("text", ""))) {
            return List.of();
        }
        if (label != null && StringUtils.hasText(label.getStr("text", ""))) {
            return List.of(label, value);
        }
        return List.of(value);
    }

    private JSONObject label(String s) {
        JSONObject o = new JSONObject();
        o.set("tag", "text");
        o.set("text", s);
        return o;
    }
    private JSONObject plain(String s) {
        if (!StringUtils.hasText(s)) return null;
        JSONObject o = new JSONObject();
        o.set("tag", "text");
        o.set("text", s);
        return o;
    }

    // 纯内部 DTO，用 record 最方便，但为了兼容 Java 17+ 我们直接写类
    private static class Display {
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
