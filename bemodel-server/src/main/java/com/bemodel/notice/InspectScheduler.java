package com.bemodel.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 指标定时巡检：cron 读 bemodel.inspect.cron（默认每 30 分钟），纯探针 SQL 不依赖 LLM */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectScheduler {

    private final InspectService inspectService;

    @Scheduled(cron = "${bemodel.inspect.cron:0 0/30 * * * *}")
    public void inspect() {
        try {
            log.info("定时巡检完成: {}", inspectService.runAll());
        } catch (Exception e) {
            log.warn("定时巡检失败（下个周期重试）: {}", e.getMessage());
        }
    }
}
