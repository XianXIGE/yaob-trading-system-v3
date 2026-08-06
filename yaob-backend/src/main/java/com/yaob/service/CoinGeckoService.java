package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * CoinGecko 市值服务：获取市值排名靠前的币种，并按币安合约可交易过滤。
 * 接口公开免费：GET /api/v3/coins/markets?vs_currency=usd&order=market_cap_desc
 *
 * 注意：CoinGecko 免费接口限流极严（约 10-30 次/分钟）。
 * 因此本服务采用内存缓存（默认15分钟），restoreDefault 和排序都读缓存，
 * 避免重复请求触发 429 导致拉不到数据。
 */
@Slf4j
@Service
public class CoinGeckoService {

    @Value("${coingecko.proxy:http://127.0.0.1:7890}")
    private String proxyUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient = null;

    /** 市值阈值：20亿美元 */
    public static final long MARKET_CAP_THRESHOLD = 2_000_000_000L;

    /** 缓存：市值排名结果列表（symbol 降序 + market_cap 对照表） */
    private volatile List<String> cachedHighCapSymbols = null;
    private volatile Map<String, Long> cachedMarketCap = new HashMap<>();
    private volatile long cacheTime = 0;
    private static final long CACHE_TTL = 15 * 60 * 1000; // 15分钟

    private HttpClient getHttpClient() {
        if (httpClient != null) return httpClient;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            try {
                URI proxyUri = URI.create(proxyUrl);
                String host = proxyUri.getHost();
                int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : 7890;
                builder.proxy(ProxySelector.of(new java.net.InetSocketAddress(host, port)));
            } catch (Exception e) {
                log.warn("CoinGecko 代理配置解析失败: {}", e.getMessage());
            }
        }
        httpClient = builder.build();
        return httpClient;
    }

    /**
     * 获取市值 > 阈值的币种交易对列表（返回币安格式，如 BTCUSDT），按市值降序。
     * 带15分钟缓存；缓存未过期时直接返回，不打接口。
     */
    public synchronized List<String> fetchHighCapSymbols(Set<String> binanceSymbols) {
        long now = System.currentTimeMillis();
        if (cachedHighCapSymbols != null && (now - cacheTime) < CACHE_TTL) {
            return cachedHighCapSymbols;
        }

        List<Map<String, Object>> ranked = new ArrayList<>();
        int page = 1;
        int perPage = 250;
        int maxPages = 3; // 最多拉750个，覆盖市值>20亿范围内所有币
        boolean keepGoing = true;

        try {
            while (keepGoing && page <= maxPages) {
                JsonNode arr = getMarketsPage(perPage, page);
                if (arr == null || !arr.isArray() || arr.size() == 0) {
                    keepGoing = false;
                    break;
                }
                for (JsonNode c : arr) {
                    long cap = c.has("market_cap") && !c.get("market_cap").isNull()
                            ? c.get("market_cap").asLong() : 0L;
                    if (cap < MARKET_CAP_THRESHOLD) {
                        keepGoing = false; // 已到阈值以下，CoinGecko 降序，后面更小，直接停
                        break;
                    }
                    String symbol = c.has("symbol") ? c.get("symbol").asText().toUpperCase() : "";
                    if (symbol.isBlank()) continue;
                    ranked.add(Map.of("symbol", symbol, "market_cap", cap));
                }
                page++;
                // 温柔一点，避免连续请求瞬间触发限流
                if (keepGoing && page <= maxPages) {
                    Thread.sleep(1000);
                }
            }
        } catch (Exception e) {
            log.error("CoinGecko 拉取市值失败: {}", e.getMessage());
        }

        List<String> result = new ArrayList<>();
        Map<String, Long> capMap = new HashMap<>();
        for (Map<String, Object> item : ranked) {
            String base = (String) item.get("symbol");
            long cap = (Long) item.get("market_cap");
            String sym = base + "USDT";
            capMap.put(sym, cap);
            if (binanceSymbols != null && binanceSymbols.contains(sym)) {
                result.add(sym);
            }
        }

        cachedMarketCap = capMap;
        cachedHighCapSymbols = result;
        cacheTime = System.currentTimeMillis();
        log.info("CoinGecko 市值>{} 的币 {} 个，币安合约可交易 {} 个（已缓存）",
                MARKET_CAP_THRESHOLD, ranked.size(), result.size());
        return result;
    }

    /**
     * 获取指定交易对的市值（美元），缓存优先。查不到返回 0。
     */
    public long getMarketCap(String usdtSymbol) {
        return cachedMarketCap.getOrDefault(usdtSymbol, 0L);
    }

    /** 获取一页按市值降序的币种 */
    private JsonNode getMarketsPage(int perPage, int page) throws IOException, InterruptedException {
        String url = "https://api.coingecko.com/api/v3/coins/markets"
                + "?vs_currency=usd&order=market_cap_desc&per_page=" + perPage + "&page=" + page
                + "&sparkline=false";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("CoinGecko 请求失败 status={} body={}", resp.statusCode(),
                    resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
            return null;
        }
        return objectMapper.readTree(resp.body());
    }
}
