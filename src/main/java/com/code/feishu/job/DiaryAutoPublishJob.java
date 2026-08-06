package com.code.feishu.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.code.feishu.controller.DiaryController;
import com.code.feishu.entity.Diary;
import com.code.feishu.entity.DiaryPublishConfig;
import com.code.feishu.mapper.DiaryMapper;
import com.code.feishu.mapper.DiaryPublishConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 日记自动发布定时任务。
 *
 * 执行策略：每小时整点扫描一次，找出 publish_hour = 当前小时 且 enabled=1 的所有用户配置，
 *           把这些用户的所有草稿日记（status=draft）批量发布（status=published）。
 *
 * 设计说明：
 *   - 用户可在「我的」页配置自动发布的小时（0-23），默认 1:00
 *   - 每小时一次轮询，开销极小（配置表数据量 = 用户数）
 *   - 批量 UPDATE 一条 SQL 完成，避免循环逐条更新
 *   - 放在 job 包下，和 PendingMessageRetryJob 风格统一
 */
@Component
public class DiaryAutoPublishJob {

    private static final Logger log = LoggerFactory.getLogger(DiaryAutoPublishJob.class);

    private final DiaryMapper diaryMapper;
    private final DiaryPublishConfigMapper configMapper;

    public DiaryAutoPublishJob(DiaryMapper diaryMapper, DiaryPublishConfigMapper configMapper) {
        this.diaryMapper = diaryMapper;
        this.configMapper = configMapper;
    }

    /**
     * 每小时整点执行。
     * cron 表达式：秒 分 时 日 月 周
     *   0 0 * * * ?  = 每小时整点（00:00:00, 01:00:00, 02:00:00, ...）
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void autoPublishDraftDiaries() {
        int currentHour = LocalTime.now().getHour();
        log.info("[日记自动发布] 开始扫描，当前小时={}点", currentHour);

        try {
            // 1. 查询到点的用户配置（publish_hour = 当前小时 且 enabled=1）
            List<DiaryPublishConfig> configs = configMapper.selectList(
                    new LambdaQueryWrapper<DiaryPublishConfig>()
                            .eq(DiaryPublishConfig::getPublishHour, currentHour)
                            .eq(DiaryPublishConfig::getEnabled, 1)
            );

            if (configs.isEmpty()) {
                log.info("[日记自动发布] 当前 {} 点无待发布用户，任务结束", currentHour);
                return;
            }

            // 2. 提取用户 ID 列表
            List<Long> userIds = configs.stream()
                    .map(DiaryPublishConfig::getUserId)
                    .collect(Collectors.toList());
            log.info("[日记自动发布] 匹配到 {} 个用户待发布：{}", userIds.size(), userIds);

            // 3. 批量查询这些用户的草稿日记（用于日志记录）
            List<Diary> drafts = diaryMapper.selectList(
                    new LambdaQueryWrapper<Diary>()
                            .in(Diary::getUserId, userIds)
                            .eq(Diary::getStatus, DiaryController.STATUS_DRAFT)
            );

            if (drafts.isEmpty()) {
                log.info("[日记自动发布] 这些用户无草稿日记，任务结束");
                return;
            }

            // 4. 批量更新为已发布（一条 SQL）
            int updated = diaryMapper.update(null,
                    new LambdaUpdateWrapper<Diary>()
                            .in(Diary::getUserId, userIds)
                            .eq(Diary::getStatus, DiaryController.STATUS_DRAFT)
                            .set(Diary::getStatus, DiaryController.STATUS_PUBLISHED)
            );

            // 5. 记录日志（含日记 ID 列表，便于排查）
            List<Long> diaryIds = drafts.stream().map(Diary::getId).collect(Collectors.toList());
            log.info("[日记自动发布] 成功发布 {} 篇草稿日记，IDs: {}", updated, diaryIds);

        } catch (Exception e) {
            log.error("[日记自动发布] 任务执行失败", e);
        }
    }
}
