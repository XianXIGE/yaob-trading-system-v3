package com.yaob.scheduler;

import com.yaob.service.TradeEngineService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ScanScheduler {

    @Autowired
    private TradeEngineService tradeEngine;

    private ExecutorService executor;

    @PostConstruct
    public void start() {
        log.info("启动妖币扫描调度器...");
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "yaob-scan-loop");
            t.setDaemon(true);
            return t;
        });
        // 线程1：完整扫描（策略候选池）
        executor.submit(() -> {
            try { Thread.sleep(10_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            log.info("扫描线程开始运行");
            tradeEngine.scanLoop();
        });
        // 线程2：轻量级持仓刷新（每10秒更新资产和持仓当前价）
        executor.submit(() -> {
            try { Thread.sleep(12_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            log.info("持仓刷新线程开始运行");
            tradeEngine.positionRefreshLoop();
        });
    }

    @PreDestroy
    public void stop() {
        log.info("停止妖币扫描调度器...");
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
