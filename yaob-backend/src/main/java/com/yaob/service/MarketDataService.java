package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yaob.entity.MarketData;
import com.yaob.mapper.MarketDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 行情K线落库服务（P0-1 数据采集）。
 * <p>
 * 把实时扫描中用到的 K 线从"仅内存缓存(30s TTL,重启即丢)"升级为"永久落库"。
 * 落库的数据是回测通道(BacktestRunner)的原始数据源，也用于 ATR 等波动率计算。
 * 支持增量更新：记录每个 symbol+interval 已落库的最新 open_time，只补增量，避免重复写入。
 */
@Slf4j
@Service
public class MarketDataService {

    @Autowired
    private BinanceFapiService fapi;
    @Autowired
    private MarketDataMapper marketDataMapper;

    /** 需要落库的关键周期。1m 用于短时均值回归/插针类，15m/1h/4h 用于趋势与网格/ATR。 */
    private static final String[] INTERVALS = {"1m", "15m", "1h", "4h"};

    /**
     * 增量落库指定标的的指定周期K线。limit 控制每周期拉取根数。
     * 返回写入的K线根数。
     */
    public int ingest(String symbol, String interval, int limit) {
        try {
            long maxTs = marketDataMapper.maxOpenTime(symbol, interval);
            JsonNode kl = fapi.klines(symbol, interval, limit);
            if (kl == null || !kl.isArray()) {
                log.warn("[market:{}] {} {} K线响应为空", symbol, interval);
                return 0;
            }
            List<MarketData> batch = new ArrayList<>();
            for (JsonNode k : kl) {
                long openTime = k.get(0).asLong();
                if (openTime <= maxTs) continue;   // 增量：跳过已落库的
                MarketData md = new MarketData();
                md.setSymbol(symbol);
                md.setInterval(interval);
                md.setOpenTime(openTime);
                md.setOpen(new BigDecimal(k.get(1).asText()));
                md.setHigh(new BigDecimal(k.get(2).asText()));
                md.setLow(new BigDecimal(k.get(3).asText()));
                md.setClose(new BigDecimal(k.get(4).asText()));
                md.setVolume(new BigDecimal(k.get(5).asText()));
                md.setQuoteVolume(new BigDecimal(k.get(7).asText()));
                md.setCreatedAt(LocalDateTime.now());
                batch.add(md);
            }
            if (!batch.isEmpty()) {
                // MyBatis-Plus 无内置批量 insert; 分批单条插入+捕获唯一键冲突(幂等)。
                for (MarketData md : batch) {
                    try {
                        marketDataMapper.insert(md);
                    } catch (Exception dup) {
                        // UK(symbol,interval,open_time) 冲突则跳过, 幂等。
                    }
                }
                log.info("[market] {} {} 增量落库 {} 根K线", symbol, interval, batch.size());
            }
            return batch.size();
        } catch (Exception e) {
            log.warn("[market:{}] {} {} 落库异常: {}", symbol, interval, e.getMessage());
            return 0;
        }
    }

    /**
     * 对候选池的核心标的落库（供调度器调用）。
     * 为避免对全市场600+币落库造成压力，只对"已持仓 + 当前候选池前N"等关注标的落库。
     */
    public void ingestWatched(List<String> symbols, int limitPerSym) {
        if (symbols == null || symbols.isEmpty()) return;
        for (String sym : symbols) {
            for (String iv : INTERVALS) {
                ingest(sym, iv, limitPerSym);
            }
        }
    }
}
