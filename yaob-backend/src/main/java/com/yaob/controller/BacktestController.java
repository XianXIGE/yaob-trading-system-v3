package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.dto.BacktestRequest;
import com.yaob.entity.BacktestResult;
import com.yaob.entity.User;
import com.yaob.mapper.BacktestResultMapper;
import com.yaob.service.BacktestRunner;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测通道入口（P0-1 回测/实盘双通道）。
 * <p>
 * 复用与实盘完全一致的策略信号函数，对已落库 K 线回放给出胜率/盈亏/最大回撤。
 * 必须先执行 migration_v3.13_realtime.sql 且行情落库(线程4)积累足够样本（建议≥3个月分钟级）。
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    @Autowired
    private BacktestRunner backtestRunner;
    @Autowired
    private BacktestResultMapper backtestResultMapper;
    @Autowired
    private UserService userService;

    /** 触发一次回测，返回回测结果（同步阻塞，长周期可能耗时）。 */
    @PostMapping("/run")
    public Result<BacktestResult> run(@RequestBody BacktestRequest req, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        if (req.getStrategy() == null || req.getStrategy().isBlank())
            throw new BusinessException("缺失策略key");
        if (req.getSymbol() == null || req.getSymbol().isBlank())
            throw new BusinessException("缺失标的symbol");
        if (req.getStartTs() <= 0 || req.getEndTs() <= req.getStartTs())
            throw new BusinessException("backtest 时间范围非法");
        BacktestResult r = backtestRunner.run(user.getId(), req.getStrategy(),
                req.getSymbol().toUpperCase(), req.getInterval(),
                req.getStartTs(), req.getEndTs(), req.getSlippagePct());
        return Result.success(r);
    }

    /** 查看历史回测结果（可选按策略过滤）。 */
    @GetMapping("/results")
    public Result<List<BacktestResult>> results(@RequestParam(required = false) String strategy,
                                                HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        List<BacktestResult> list;
        if (strategy != null && !strategy.isBlank()) {
            list = backtestResultMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BacktestResult>()
                            .eq(BacktestResult::getStrategy, strategy.toUpperCase())
                            .orderByDesc(BacktestResult::getId));
        } else {
            list = backtestResultMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BacktestResult>()
                            .orderByDesc(BacktestResult::getId).last("LIMIT 50"));
        }
        return Result.success(list);
    }

    /** 回测/实盘对照摘要：并列展示回测与实盘同策略统计。 */
    @GetMapping("/compare")
    public Result<Map<String, Object>> compare(@RequestParam String strategy, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        Map<String, Object> data = new HashMap<>();
        data.put("strategy", strategy.toUpperCase());
        BacktestResult latest = backtestResultMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BacktestResult>()
                        .eq(BacktestResult::getStrategy, strategy.toUpperCase())
                        .orderByDesc(BacktestResult::getId).last("LIMIT 1"));
        data.put("backtest", latest);
        return Result.success(data);
    }
}
