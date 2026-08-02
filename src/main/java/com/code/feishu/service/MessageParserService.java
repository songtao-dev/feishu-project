package com.code.feishu.service;

import com.code.feishu.vo.MessageParseVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 银行短信 -> 结构化字段
 * 样例：
 *   尾号6566卡8月1日19:48支出(消费抖音支付-上海牛约堡餐饮集团有限公司)9.89元，余额1,247.81元。【工商银行】
 */
@Service
public class MessageParserService {

    // 尾号 / 卡号末 4 位
    private static final Pattern CARD_TAIL = Pattern.compile("尾号\\s*(\\d{4})|卡号.*?(\\d{4})[^0-9]");

    // 日期时间：如 8月1日19:48 / 08-01 19:48 / 2026-08-01 19:48
    private static final Pattern DT_1 = Pattern.compile("(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern DT_2 = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\s*(\\d{1,2})?:?(\\d{2})?");
    private static final Pattern DT_3 = Pattern.compile("(\\d{1,2})[-/](\\d{1,2})\\s*(\\d{1,2}):(\\d{2})");

    // 金额：9.89元 / 支出9.89元 / 人民币1,247.81元
    private static final Pattern AMOUNT = Pattern.compile("([收支出取入帐转].*?)(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)\\s*元");
    private static final Pattern AMOUNT_FIRST = Pattern.compile("(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)\\s*元");

    // 余额：余额1,247.81元
    private static final Pattern BALANCE = Pattern.compile("余额\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)\\s*元");

    // 银行：【工商银行】
    private static final Pattern BANK = Pattern.compile("【([^】]+)】|\\[([^\\]]+)\\]");

    // 方向：支出 / 收入 / 存入 / 取款 / 转入 / 转出 / 入账 / 扣费 等
    private static final Pattern DIR = Pattern.compile("(支出|消费|扣(?:除|费|款)|取(?:款|现)|转出|收入|存入|转入|入账|退款|到账|代发|工资)");

    // 商家：(消费抖音支付-上海牛约堡餐饮集团有限公司) 或 支付给XXXX 等
    private static final Pattern MERCHANT_IN_PAREN = Pattern.compile("[(（]([^)）]+)[)）]");
    private static final Pattern MERCHANT_AFTER_TO = Pattern.compile("(?:支付给|商户|收款方)[:：]?\\s*([^，。；;\n]{2,40})");

    // 渠道：消费抖音支付 / 支付宝 / 微信 等
    private static final Pattern CHANNEL_KEYWORDS = Pattern.compile("(支付宝|微信|云闪付|美团支付|京东支付|抖音支付|拼多多支付|Apple Pay|POS|ATM|网银|手机银行|快捷支付|扫码支付)");

    public MessageParseVO parse(String raw) {
        MessageParseVO vo = new MessageParseVO();
        List<String> missed = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            vo.setSuccess(false);
            vo.setTip("请先粘贴完整的短信内容");
            return vo;
        }

        // 1. 尾号
        Matcher m = CARD_TAIL.matcher(raw);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            vo.setCardTail(g);
        } else {
            missed.add("尾号");
        }

        // 2. 日期时间
        String time = null;
        int year = LocalDate.now().getYear();
        Matcher m1 = DT_1.matcher(raw);
        if (m1.find()) {
            int mo = Integer.parseInt(m1.group(1));
            int da = Integer.parseInt(m1.group(2));
            int hh = Integer.parseInt(m1.group(3));
            int mi = Integer.parseInt(m1.group(4));
            LocalDateTime dt = LocalDateTime.of(year, mo, da, hh, mi);
            time = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        if (time == null) {
            Matcher m2 = DT_2.matcher(raw);
            if (m2.find()) {
                int yy = Integer.parseInt(m2.group(1));
                int mo = Integer.parseInt(m2.group(2));
                int da = Integer.parseInt(m2.group(3));
                int hh = m2.group(4) != null ? Integer.parseInt(m2.group(4)) : 0;
                int mi = m2.group(5) != null ? Integer.parseInt(m2.group(5)) : 0;
                time = LocalDateTime.of(yy, mo, da, hh, mi)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        }
        if (time == null) {
            Matcher m3 = DT_3.matcher(raw);
            if (m3.find()) {
                int mo = Integer.parseInt(m3.group(1));
                int da = Integer.parseInt(m3.group(2));
                int hh = Integer.parseInt(m3.group(3));
                int mi = Integer.parseInt(m3.group(4));
                time = LocalDateTime.of(year, mo, da, hh, mi)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        }
        if (time == null) missed.add("时间");
        vo.setHappenTime(time);

        // 3. 方向
        Matcher md = DIR.matcher(raw);
        if (md.find()) {
            String d = md.group(1);
            // 归一化
            String dir = switch (d) {
                case "支出", "消费", "扣除", "扣费", "扣款", "取款", "取现", "转出" -> "支出";
                case "收入", "存入", "转入", "入账", "退款", "到账", "代发", "工资" -> "收入";
                default -> d;
            };
            vo.setDirection(dir);
            vo.setTransType(dir);
        } else {
            missed.add("收支方向");
        }

        // 4. 渠道
        Matcher mc = CHANNEL_KEYWORDS.matcher(raw);
        if (mc.find()) {
            vo.setChannel(mc.group(1));
        } else {
            missed.add("支付渠道");
        }

        // 5. 商家：优先括号里"渠道-商家"结构，再兜底"支付给"
        Matcher mp = MERCHANT_IN_PAREN.matcher(raw);
        if (mp.find()) {
            String inner = mp.group(1);
            // "消费抖音支付-上海牛约堡餐饮集团有限公司" -> 渠道+商家
            int idx = inner.lastIndexOf('-');
            if (idx > 0 && idx < inner.length() - 1) {
                String left = inner.substring(0, idx);
                String right = inner.substring(idx + 1);
                // left 里可能含渠道词
                Matcher chInLeft = CHANNEL_KEYWORDS.matcher(left);
                if (chInLeft.find() && vo.getChannel() == null) {
                    vo.setChannel(chInLeft.group(1));
                }
                vo.setMerchant(right.trim());
            } else {
                vo.setMerchant(inner.trim());
            }
        }
        if (vo.getMerchant() == null) {
            Matcher mpa = MERCHANT_AFTER_TO.matcher(raw);
            if (mpa.find()) vo.setMerchant(mpa.group(1).trim());
        }
        if (vo.getMerchant() == null) missed.add("商家");

        // 6. 金额（交易金额，不是余额）
        BigDecimal amount = null;
        Matcher ma = AMOUNT.matcher(raw);
        if (ma.find()) {
            amount = cleanMoney(ma.group(2));
        }
        if (amount == null) {
            Matcher maf = AMOUNT_FIRST.matcher(raw);
            // 取第一个"X元"但不能是"余额X元"，因此避开余额位置
            int balanceStart = -1;
            Matcher mb0 = BALANCE.matcher(raw);
            if (mb0.find()) balanceStart = mb0.start();
            while (maf.find()) {
                if (balanceStart >= 0 && maf.start() >= balanceStart) continue;
                amount = cleanMoney(maf.group(1));
                break;
            }
        }
        vo.setAmount(amount);
        if (amount == null) missed.add("交易金额");

        // 7. 余额
        Matcher mb = BALANCE.matcher(raw);
        if (mb.find()) {
            vo.setBalance(cleanMoney(mb.group(1)));
        } else {
            missed.add("余额");
        }

        // 8. 银行
        Matcher mBank = BANK.matcher(raw);
        if (mBank.find()) {
            vo.setBank(mBank.group(1) != null ? mBank.group(1) : mBank.group(2));
        } else {
            missed.add("银行");
        }

        if (!missed.isEmpty()) {
            vo.setTip("部分字段未命中：" + String.join("、", missed) + "，可手动补充");
        } else {
            vo.setTip("解析成功，确认无误后可直接发送");
        }
        return vo;
    }

    private BigDecimal cleanMoney(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
