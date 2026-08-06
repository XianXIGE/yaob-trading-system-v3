package com.yaob.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享行情缓存：全市场 ticker / K线 / 资金费率
 * 多用户扫描共用同一份数据，显著降低币安 API 压力。
 */
@Slf4j
@Service
public class MarketDataService {

    private static final String TICKER_KEY = "yaob:md:tickers";
    private static final String KLINE_PREFIX = "yaob:md:kline:";
    private static final String FUNDING_KEY = "yaob:md:funding";

    @Autowired
    private BinanceFapiService fapi;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Value("${market.ticker-ttl-seconds:45}")
    private long tickerTtlSeconds;

    @Value("${market.kline-ttl-seconds:60}")
    private long klineTtlSeconds;

    @Value("${market.funding-ttl-seconds:120}")
    private long fundingTtlSeconds;

    /** 进程内兜底缓存（Redis 不可用时） */
    private volatile List<Map<String, Object>> localTickers;
    private volatile long localTickersAt;
    private final ConcurrentHashMap<String, CacheEntry<JsonNode>> localKlines = new ConcurrentHashMap<>();
    private volatile Map<String, Double> localFunding;
    private volatile long localFundingAt;

    private static class CacheEntry<T> {
        final T value;
        final long at;
        CacheEntry(T v, long at) { this.value = v; this.at = at; }
    }

    /** 获取全市场 24h ticker（带缓存） */
    public List<Map<String, Object>> getAllTickers() throws Exception {
        // Redis
        if (redis != null) {
            try {
                String cached = redis.opsForValue().get(TICKER_KEY);
                if (cached != null && !cached.isBlank()) {
                    return objectMapper.readValue(cached, new TypeReference<List<Map<String, Object>>>() {});
                }
            } catch (Exception e) {
                log.debug("[md] redis ticker read fail: {}", e.getMessage());
            }
        }
        // 本地
        long now = System.currentTimeMillis();
        if (localTickers != null && now - localTickersAt < tickerTtlSeconds * 1000) {
            return localTickers;
        }

        List<Map<String, Object>> tickers = fapi.allTickers();
        localTickers = tickers;
        localTickersAt = now;

        if (redis != null) {
            try {
                redis.opsForValue().set(TICKER_KEY, objectMapper.writeValueAsString(tickers),
                        Duration.ofSeconds(tickerTtlSeconds));
            } catch (Exception e) {
                log.debug("[md] redis ticker write fail: {}", e.getMessage());
            }
        }
        log.info("[md] ticker 刷新完成, size={}", tickers.size());
        return tickers;
    }

    /** symbol -> ticker map */
    public Map<String, Map<String, Object>> getTickerMap() throws Exception {
        List<Map<String, Object>> list = getAllTickers();
        Map<String, Map<String, Object>> map = new HashMap<>(list.size() * 2);
        for (Map<String, Object> t : list) {
            Object sym = t.get("symbol");
            if (sym != null) map.put(sym.toString(), t);
        }
        return map;
    }

    /** K线缓存 */
    public JsonNode getKlines(String symbol, String interval, int limit) throws Exception {
        String key = KLINE_PREFIX + symbol + ":" + interval + ":" + limit;
        if (redis != null) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null && !cached.isBlank()) {
                    return objectMapper.readTree(cached);
                }
            } catch (Exception ignored) {}
        }
        CacheEntry<JsonNode> local = localKlines.get(key);
        long now = System.currentTimeMillis();
        if (local != null && now - local.at < klineTtlSeconds * 1000) {
            return local.value;
        }

        JsonNode k = fapi.klines(symbol, interval, limit);
        localKlines.put(key, new CacheEntry<>(k, now));
        if (redis != null && k != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(k),
                        Duration.ofSeconds(klineTtlSeconds));
            } catch (Exception ignored) {}
        }
        return k;
    }

    /**
     * 资金费率 map: symbol -> lastFundingRate (小数，如 0.0001 = 0.01%)
     * 使用 premiumIndex 接口一次性拉取。
     */
    public Map<String, Double> getFundingRates() {
        long now = System.currentTimeMillis();
        if (localFunding != null && now - localFundingAt < fundingTtlSeconds * 1000) {
            return localFunding;
        }
        if (redis != null) {
            try {
                String cached = redis.opsForValue().get(FUNDING_KEY);
                if (cached != null && !cached.isBlank()) {
                    Map<String, Double> m = objectMapper.readValue(cached, new TypeReference<Map<String, Double>>() {});
                    localFunding = m;
                    localFundingAt = now;
                    return m;
                }
            } catch (Exception ignored) {}
        }
        Map<String, Double> rates = new HashMap<>();
        try {
            JsonNode arr = fapi.premiumIndex();
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.has("symbol") && n.has("lastFundingRate")) {
                        rates.put(n.get("symbol").asText(), n.get("lastFundingRate").asDouble());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[md] funding rates fetch fail: {}", e.getMessage());
        }
        localFunding = rates;
        localFundingAt = now;
        if (redis != null && !rates.isEmpty()) {
            try {
                redis.opsForValue().set(FUNDING_KEY, objectMapper.writeValueAsString(rates),
                        Duration.ofSeconds(fundingTtlSeconds));
            } catch (Exception ignored) {}
        }
        return rates;
    }

    /** 判断资金费率是否过于极端（绝对值超过阈值，默认 0.3% = 0.003） */
    public boolean isFundingExtreme(String symbol, double threshold) {
        Map<String, Double> rates = getFundingRates();
        Double r = rates.get(symbol);
        if (r == null) return false;
        return Math.abs(r) >= threshold;
    }
}
