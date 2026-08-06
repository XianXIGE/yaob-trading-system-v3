package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class BinanceFapiService {

    @Value("${fapi.base:https://fapi.binance.com}")
    private String base;

    @Value("${fapi.proxy:http://127.0.0.1:7890}")
    private String proxyUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String apiKey = "";
    private String apiSecret = "";
    private boolean dryRun = true;

    // Symbol metadata cache: symbol -> {stepSize, tickSize}
    private Map<String, Map<String, Double>> symbolMeta = new HashMap<>();

    // 币安合约全部交易对集合缓存（用于市值过滤）
    private volatile Set<String> allSymbolsCache = null;
    private volatile long allSymbolsCacheTime = 0;
    private static final long SYMBOL_CACHE_TTL = 30 * 60 * 1000; // 30分钟

    /**
     * 获取币安合约全部可交易 USDT 交易对集合（带30分钟缓存）。
     * 仅返回以 USDT 报价的永续交易对。
     */
    public Set<String> getAllUsdtSymbols() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (allSymbolsCache != null && (now - allSymbolsCacheTime) < SYMBOL_CACHE_TTL) {
            return allSymbolsCache;
        }
        Set<String> symbols = new HashSet<>();
        JsonNode resp = exchangeInfo();
        if (resp.has("symbols")) {
            for (JsonNode s : resp.get("symbols")) {
                String sym = s.get("symbol").asText();
                String quote = s.has("quoteAsset") ? s.get("quoteAsset").asText() : "";
                String status = s.has("status") ? s.get("status").asText() : "";
                String contractType = s.has("contractType") ? s.get("contractType").asText() : "";
                boolean tradable = !s.has("tradable") || s.get("tradable").asBoolean(false);
                // 只保留 USDT 报价、可交易、永续合约
                if (tradable && "USDT".equals(quote)
                        && "TRADING".equals(status)
                        && ("PERPETUAL".equals(contractType) || contractType.isBlank())) {
                    symbols.add(sym);
                }
            }
        }
        allSymbolsCache = symbols;
        allSymbolsCacheTime = now;
        log.info("币安合约 USDT 永续交易对数量: {}", symbols.size());
        return symbols;
    }

    private HttpClient httpClient;

    private HttpClient getHttpClient() {
        if (httpClient != null) return httpClient;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            try {
                URI proxyUri = URI.create(proxyUrl);
                String host = proxyUri.getHost();
                int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : 7890;
                builder.proxy(ProxySelector.of(new java.net.InetSocketAddress(host, port)));
            } catch (Exception e) {
                log.warn("代理配置解析失败: {}", e.getMessage());
            }
        }
        httpClient = builder.build();
        return httpClient;
    }

    public void setApiKeys(String key, String secret) {
        this.apiKey = key == null ? "" : key;
        this.apiSecret = secret == null ? "" : secret;
        this.dryRun = (apiKey.isBlank() || apiSecret.isBlank());
    }

    public boolean isDryRun() {
        return dryRun;
    }

    // ==================== Public Endpoints ====================

    public JsonNode ping() throws IOException, InterruptedException {
        return _get("/fapi/v1/ping", null, false);
    }

    public JsonNode exchangeInfo() throws IOException, InterruptedException {
        JsonNode resp = _get("/fapi/v1/exchangeInfo", null, false);
        symbolMeta.clear();
        if (resp.has("symbols")) {
            for (JsonNode s : resp.get("symbols")) {
                String sym = s.get("symbol").asText();
                Map<String, Double> meta = new HashMap<>();
                meta.put("stepSize", 0.0);
                meta.put("tickSize", 0.0);
                if (s.has("filters")) {
                    for (JsonNode f : s.get("filters")) {
                        String ft = f.get("filterType").asText();
                        if ("LOT_SIZE".equals(ft)) {
                            meta.put("stepSize", f.get("stepSize").asDouble());
                        } else if ("PRICE_FILTER".equals(ft)) {
                            meta.put("tickSize", f.get("tickSize").asDouble());
                        }
                    }
                }
                symbolMeta.put(sym, meta);
            }
        }
        return resp;
    }

    public List<Map<String, Object>> allTickers() throws IOException, InterruptedException {
        JsonNode resp = _get("/fapi/v1/ticker/24hr", null, false);
        List<Map<String, Object>> tickers = new ArrayList<>();
        if (resp.isArray()) {
            for (JsonNode t : resp) {
                tickers.add(objectMapper.convertValue(t, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            }
            log.info("allTickers: 获取到 {} 个交易对", tickers.size());
        } else {
            log.warn("allTickers: 币安返回非数组响应: {}", resp.toString().substring(0, Math.min(500, resp.toString().length())));
        }
        return tickers;
    }

    public JsonNode klines(String symbol, String interval, int limit) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("interval", interval);
        params.put("limit", String.valueOf(limit));
        return _get("/fapi/v1/klines", params, false);
    }

    /** 全市场溢价指数（含资金费率 lastFundingRate） */
    public JsonNode premiumIndex() throws IOException, InterruptedException {
        return _get("/fapi/v1/premiumIndex", null, false);
    }

    // ==================== Signed Endpoints ====================

    public JsonNode account() throws IOException, InterruptedException {
        if (dryRun) throw new RuntimeException("未配置有效API Key, 处于模拟模式(不实际下单)");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("recvWindow", "5000");
        return _get("/fapi/v2/account", params, true);
    }

    public JsonNode setLeverage(String symbol, int leverage) throws IOException, InterruptedException {
        if (dryRun) throw new RuntimeException("未配置有效API Key, 处于模拟模式");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));
        return _post("/fapi/v1/leverage", params, true);
    }

    public JsonNode newOrder(String symbol, String side, double qty) throws IOException, InterruptedException {
        if (dryRun) throw new RuntimeException("未配置有效API Key, 处于模拟模式");
        String quantity = roundQty(symbol, qty, "down");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "MARKET");
        params.put("quantity", quantity);
        params.put("reduceOnly", "false");
        return _post("/fapi/v1/order", params, true);
    }

    /**
     * 平仓: reduceOnly=true, 数量向上取整(ceil)到stepSize, 确保清仓彻底不残留碎单
     */
    public JsonNode closePosition(String symbol, double qty, String side) throws IOException, InterruptedException {
        if (dryRun) throw new RuntimeException("未配置有效API Key, 处于模拟模式");
        String quantity = roundQty(symbol, qty, "up");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "MARKET");
        params.put("quantity", quantity);
        params.put("reduceOnly", "true");
        return _post("/fapi/v1/order", params, true);
    }

    // ==================== Qty Rounding ====================

    /**
     * 按合约 LOT_SIZE.stepSize 对齐 quantity, 避免 -1111 精度错误.
     * mode "down" -> floor: 开仓用, 防止超额
     * mode "up"   -> ceil:  平仓用, 确保清光不残留碎单
     */
    public String roundQty(String symbol, double qty, String mode) {
        if (symbolMeta.isEmpty()) {
            try {
                exchangeInfo();
            } catch (Exception e) {
                log.warn("获取 exchangeInfo 失败: {}", e.getMessage());
            }
        }
        Map<String, Double> meta = symbolMeta.get(symbol);
        double step = (meta != null) ? meta.getOrDefault("stepSize", 0.0) : 0.0;
        if (step > 0) {
            double raw = qty / step;
            double rounded = "up".equals(mode) ? Math.ceil(raw) * step : Math.floor(raw) * step;
            int dec = 0;
            String stepStr = String.valueOf(step);
            if (stepStr.contains(".")) {
                dec = stepStr.split("\\.")[1].length();
            }
            return String.format("%." + dec + "f", rounded);
        }
        return String.format("%.3f", qty);
    }

    // ==================== HTTP + Signing ====================

    private JsonNode _get(String path, Map<String, String> params, boolean signed) throws IOException, InterruptedException {
        String query = buildQuery(params, signed);
        String url = base + path + (query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "yaob/3.0")
                .timeout(Duration.ofSeconds(10));
        if (signed) {
            reqBuilder.header("X-MBX-APIKEY", apiKey);
        }
        HttpRequest request = reqBuilder.GET().build();
        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return handleResponse(response);
    }

    private JsonNode _post(String path, Map<String, String> params, boolean signed) throws IOException, InterruptedException {
        String query = buildQuery(params, signed);
        String url = base + path + (query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "yaob/3.0")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10));
        if (signed) {
            reqBuilder.header("X-MBX-APIKEY", apiKey);
        }
        HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.ofString("")).build();
        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return handleResponse(response);
    }

    private String buildQuery(Map<String, String> params, boolean signed) {
        StringBuilder sb = new StringBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
        }
        if (signed) {
            if (dryRun) throw new RuntimeException("未配置有效API Key, 处于模拟模式(不实际下单)");
            if (sb.length() > 0) sb.append("&");
            sb.append("timestamp=").append(System.currentTimeMillis());
            String sig = hmacSha256(sb.toString(), apiSecret);
            sb.append("&signature=").append(sig);
        }
        return sb.toString();
    }

    private JsonNode handleResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() >= 400) {
            String body = response.body();
            String msg = body != null && body.length() > 200 ? body.substring(0, 200) : body;
            throw new RuntimeException("FAPI " + response.statusCode() + ": " + msg);
        }
        return objectMapper.readTree(response.body());
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 签名失败", e);
        }
    }
}
