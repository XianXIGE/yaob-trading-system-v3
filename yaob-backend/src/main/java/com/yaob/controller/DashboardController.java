package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.dto.DashboardVO;
import com.yaob.entity.User;
import com.yaob.service.TradeEngineService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    @Autowired
    private UserService userService;
    @Autowired
    private TradeEngineService tradeEngine;

    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVipExpiry(user);

        TradeEngineService.RuntimeState rt = tradeEngine.getRuntime(user.getId());
        Map<String, Boolean> strategyStates = userService.getStrategyStates(user.getId());

        DashboardVO vo = new DashboardVO();
        vo.setScannerStatus(rt.scannerStatus);
        vo.setLastScanDuration(rt.lastScanDuration);
        vo.setNextScanTimestamp(rt.nextScanTimestamp);
        vo.setScanStartTimestamp(rt.scanStartTimestamp);
        vo.setAccountTotalAssets(rt.accountTotalAssets);
        vo.setAvailableMargin(rt.availableMargin);
        vo.setOpenMargin(user.getOpenMargin());
        vo.setLeverage(user.getLeverage());
        vo.setAutoTradeEnabled(user.getAutoTradeEnabled());
        vo.setMarginMode(user.getMarginMode());
        vo.setPositionMode(user.getPositionMode());
        vo.setExcludeLargeCap(user.getExcludeLargeCap());
        vo.setHasApiKey(user.getBinanceApiKey() != null && !user.getBinanceApiKey().isBlank());
        vo.setIsVip(userService.isVip(user));
        vo.setVipExpireAt(user.getVipExpireAt() != null ? user.getVipExpireAt().toString() : "");
        vo.setStrategyStates(strategyStates);
        vo.setCandidatePool(rt.candidatePool);
        vo.setPositions(rt.positions);
        vo.setDailyPnl(rt.dailyPnl);
        vo.setRealizedPnl(rt.realizedPnl);
        vo.setUnrealizedPnl(rt.unrealizedPnl);
        vo.setCircuitBreaker(rt.circuitBreaker);
        return Result.success(vo);
    }
}
