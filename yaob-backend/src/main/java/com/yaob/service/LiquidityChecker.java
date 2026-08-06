package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LiquidityChecker {

    @Autowired
    private BinanceFapiService fapi;

    @Value("${risk.depth-notional-factor:3.0}")
    private double depthNotionalFactor;

    @Value("${risk.max-slippage:0.005}")
    private double maxSlippage;

    @Data
    public static class DepthResult {
        private boolean ok;
        private String reason;
        private double estSlippage;
        private double availableNotional;

        public static DepthResult pass(double slip, double avail) {
            DepthResult r = new DepthResult();
            r.ok = true; r.reason = "OK"; r.estSlippage = slip; r.availableNotional = avail;
            return r;
        }
        public static DepthResult fail(String reason, double slip, double avail) {
            DepthResult r = new DepthResult();
            r.ok = false; r.reason = reason; r.estSlippage = slip; r.availableNotional = avail;
            return r;
        }
    }

    public DepthResult check(String symbol, String side, double notional) {
        if (notional <= 0) return DepthResult.fail("名义价值无效", 0, 0);
        try {
            JsonNode book = fapi.depth(symbol, 20);
            if (book == null) return DepthResult.fail("无法获取订单簿", 0, 0);
            JsonNode levels = "BUY".equalsIgnoreCase(side) ? book.get("asks") : book.get("bids");
            if (levels == null || !levels.isArray() || levels.isEmpty()) {
                return DepthResult.fail("订单簿为空", 0, 0);
            }
            double best = levels.get(0).get(0).asDouble();
            if (best <= 0) return DepthResult.fail("盘口价格无效", 0, 0);

            double remain = notional, cost = 0, filledQty = 0;
            for (JsonNode lv : levels) {
                double price = lv.get(0).asDouble();
                double qty = lv.get(1).asDouble();
                double levelNotional = price * qty;
                if (levelNotional >= remain) {
                    filledQty += remain / price;
                    cost += remain;
                    remain = 0;
                    break;
                } else {
                    filledQty += qty;
                    cost += levelNotional;
                    remain -= levelNotional;
                }
            }
            double consumed = notional - remain;
            if (remain > 0) {
                return DepthResult.fail(String.format("深度不足(可吃约%.1fU < 需要%.1fU)", consumed, notional), 0, consumed);
            }
            double avgPrice = filledQty > 0 ? cost / filledQty : best;
            double slip = "BUY".equalsIgnoreCase(side) ? (avgPrice - best) / best : (best - avgPrice) / best;
            if (slip < 0) slip = 0;

            double totalDepth = 0;
            for (JsonNode lv : levels) totalDepth += lv.get(0).asDouble() * lv.get(1).asDouble();
            if (totalDepth < notional * depthNotionalFactor) {
                return DepthResult.fail(String.format("盘口过薄(深度%.1fU < %.1fx名义)", totalDepth, depthNotionalFactor), slip, totalDepth);
            }
            if (slip > maxSlippage) {
                return DepthResult.fail(String.format("预估滑点过高(%.2f%% > %.2f%%)", slip * 100, maxSlippage * 100), slip, totalDepth);
            }
            return DepthResult.pass(slip, totalDepth);
        } catch (Exception e) {
            log.warn("[liquidity] {} check fail: {}", symbol, e.getMessage());
            return DepthResult.pass(0, 0);
        }
    }
}
