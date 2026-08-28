package com.yaob.scheduler;

import com.yaob.service.TradeEngineService;
import com.yaob.service.MarketDataService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ScanScheduler {

    @Autowired
    private TradeEngineService tradeEngine;
    @Autowired
    private MarketDataService marketDataService;

    private ExecutorService executor;

    @PostConstruct
    public void start() {
        log.info("启动妖币扫描调度器...");
        executor = Executors.newFixedThreadPool(4, r -> {
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
        // 线程3：高频止盈止损平价（15秒专属循环, 方案2）
        executor.submit(() -> {
            try { Thread.sleep(14_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
            log.info("止盈止损高频平价线程开始运行(15s)");
            tradeEngine.tpSlFastLoop();
        });
        // 线程4 [v3.13 P0-1]：行情K线增量落库（拉BTC/ETH等核心标的 1m/15m/1h/4h）
        executor.submit(() -> dataCollectionLoop());
    }

    /**
     * [v3.13 系统优化 P0-1] 行情落库循环：每 3 分钟对关注标的增量落库 K 线。
     * 落库数据是回测通道(BacktestRunner)的数据源，支撑 ATR/波动率/回测统计。
     */
    private void dataCollectionLoop() {
        List<String> watched = new ArrayList<>();
        watched.add("BTCUSDT");
        watched.add("ETHUSDT");
        // 如需覆盖当前持仓/候选币，可在 TradeEngineService 扫描后调用 marketDataService.ingestWatched() 追加。
        while (true) {
            try {
                marketDataService.ingestWatched(watched, 300);
            } catch (Exception e) {
                log.warn("[data-loop] 行情落库异常: {}", e.getMessage());
            }
            try { Thread.sleep(180_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
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
