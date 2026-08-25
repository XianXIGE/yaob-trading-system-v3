package com.yaob.service;

import com.yaob.entity.OpenPosition;
import com.yaob.entity.StrategyStat;
import com.yaob.entity.TradeHistory;
import com.yaob.mapper.OpenPositionMapper;
import com.yaob.mapper.StrategyStatMapper;
import com.yaob.mapper.TradeHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓平仓落库服务。
 * <p>
 * 独立成单独 Bean 的原因：原先平仓/归档/统计更新逻辑写在 TradeEngineService 内部，
 * 却被同类内自调用（this.method()），导致 Spring 的 @Transactional 代理不生效，三步操作不是原子的。
 * 抽取到本 Bean 后，Spring 代理能正确拦截，保证"关闭持仓 → 归档流水 → 更新统计"在同一事务内。
 */
@Slf4j
@Service
public class PositionCloseService {

    @Autowired
    private OpenPositionMapper openPositionMapper;
    @Autowired
    private TradeHistoryMapper tradeHistoryMapper;
    @Autowired
    private StrategyStatMapper strategyStatMapper;

    /**
     * 原子地关闭一笔持仓并归档到流水、更新策略统计。
     * 任一环节失败整体回滚，避免产生"已平仓但无流水/统计不一致"的半成品状态。
     */
    @Transactional
    public void closePositionRecord(OpenPosition pos, BigDecimal closePrice, String reason,
                                    BigDecimal pnl, BigDecimal pnlRatio, Long userId) {
        pos.setStatus("CLOSED");
        pos.setClosedAt(LocalDateTime.now());
        pos.setClosePrice(closePrice);
        pos.setCloseReason(reason);
        pos.setPnl(pnl);
        pos.setPnlRatio(pnlRatio);
        openPositionMapper.updateById(pos);

        // 归档到 trade_history
        TradeHistory th = new TradeHistory();
        th.setUserId(userId);
        th.setPositionId(pos.getId());
        th.setSymbol(pos.getSymbol());
        th.setStrategy(pos.getStrategy());
        th.setDirection(pos.getDirection());
        th.setQty(pos.getQty());
        th.setEntryPrice(pos.getEntryPrice());
        th.setExitPrice(closePrice);
        th.setLeverage(pos.getLeverage());
        th.setPnl(pnl);
        th.setPnlRatio(pnlRatio);
        th.setCloseReason(reason);
        th.setOpenedAt(pos.getOpenedAt());
        th.setClosedAt(LocalDateTime.now());
        tradeHistoryMapper.insert(th);

        updateStrategyStats(userId, pos.getStrategy(), reason, pnl);
    }

    /**
     * 更新策略统计（total_trades / win_trades / total_pnl / 平仓原因计数）。
     * 与平仓、归档在同一事务内由 closePositionRecord 统一调用；本方法保留 public，
     * 便于其他需要单独更新统计的场景复用。
     */
    @Transactional
    public void updateStrategyStats(Long userId, String strategy, String reason, BigDecimal pnl) {
        StrategyStat stat = strategyStatMapper.findByUserIdAndStrategy(userId, strategy);
        if (stat == null) {
            stat = new StrategyStat();
            stat.setUserId(userId);
            stat.setStrategy(strategy);
            stat.setTotalTrades(0);
            stat.setWinTrades(0);
            stat.setTotalPnl(BigDecimal.ZERO);
            stat.setTpCount(0);
            stat.setSlCount(0);
            stat.setManualCount(0);
            strategyStatMapper.insert(stat);
        }
        stat.setTotalTrades(stat.getTotalTrades() + 1);
        if (pnl != null && pnl.compareTo(BigDecimal.ZERO) > 0) {
            stat.setWinTrades(stat.getWinTrades() + 1);
        }
        if (pnl != null) {
            stat.setTotalPnl(stat.getTotalPnl().add(pnl));
        }
        if ("tp".equals(reason)) stat.setTpCount(stat.getTpCount() + 1);
        else if ("sl".equals(reason)) stat.setSlCount(stat.getSlCount() + 1);
        else stat.setManualCount(stat.getManualCount() + 1);
        strategyStatMapper.updateById(stat);
    }
}
