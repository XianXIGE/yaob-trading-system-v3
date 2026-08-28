# v3.13 妖币交易系统优化改造 — 变更说明（程序员通道）

> 落地范围：**P0-1 回测/实盘双通道 + 数据采集 + P0-3 信号侧 ATR 自适应止损**
> 对应源码：`/home/ubuntu/.openclaw/workspace/agent-8c7c86fd/yaob-trading-system-v3`
> 状态：**可运行代码已写入**；改完需 `mvn package` 编译验证 + 执行库迁移。

---

## 一、改造总览（本次交付）

| 模块 | 现状 | 本次改造 | 优先级 |
|---|---|---|---|
| 数据采集 | K线只进内存缓存(30s TTL)，重启即丢 | 新增 `market_data` 落库；调度器线程4每3min增量落库 BTC/ETH | P0-1 |
| 回测通道 | 无，只有实盘 `trade_history` 归档 | 新增 `BacktestRunner`+`backtest_results`+`/api/backtest/*` 接口 | P0-1 |
| 双通道对照 | 无 | 回测复用与实盘**同一个** `StrategyDetectorService.check()`，`/compare` 并列展示 | P0-1 |
| ATR 自适应止损 | 固定结构位止盈止损，易被插针扫损 | G策略信号侧加 ATR(14)+2×ATR 缓冲绝对止损价，开仓持久化 | P0-3 |

---

## 二、新增/修改文件清单

### 1. 数据库迁移（先执行）
**`db/migration_v3.13_realtime.sql`**（新增）
- `market_data`：K线落库表，`UK(symbol,interval,open_time)` 去重
- `backtest_results`：回测结论归档表
- `alter open_positions add atr / atr_stop_price`：持久化 ATR 自适应止损
```bash
mysql -h127.0.0.1 -P3307 -uyaob -p yaob_v3 < db/migration_v3.13_realtime.sql
```

### 2. 新增实体/映射
- `entity/MarketData.java` → `mapper/MarketDataMapper.java`（含 `queryRange`/`maxOpenTime`）
- `entity/BacktestResult.java` → `mapper/BacktestResultMapper.java`

### 3. 新增服务
- `service/MarketDataService.java`：增量落库（按 symbol+interval 的 maxOpenTime 只补增量，UK 冲突幂等跳过）；`ingestWatched()` 批量入口；周期 1m/15m/1h/4h
- `service/BacktestRunner.java`：回测引擎 —— 复用实盘 `check()`，逐K线回放，模拟开/平仓，输出胜率/盈亏比/最大回撤到 `backtest_results`

### 4. 新增接口
- `dto/BacktestRequest.java`、`controller/BacktestController.java`
  - `POST /api/backtest/run`：触发回测
  - `GET /api/backtest/results`：查历史
  - `GET /api/backtest/compare`：回测/实盘对照

### 5. 修改既有文件
- `entity/OpenPosition.java`：+`atr`、`atrStopPrice`
- `service/TradeMath.java`：+`atr()`/`atrLast()`（Wilder ATR）、`marketState()`（趋势/震荡/避险三态判定，状态机前置）
- `service/StrategyDetectorService.java`：G策略信号侧计算 `atr_stop_price`（`min(defense, cur-2*atr)` 多单 / `max(defense, cur+2*atr)` 空单）并输出 `atr`
- `service/TradeEngineService.java`：G策略开仓时持久化 `atr`/`atr_stop_price`
- `scheduler/ScanScheduler.java`：线程池 3→4，新增 `dataCollectionLoop()` 每3min落库

---

## 三、关键设计决策（供评审）

1. **回测与实盘同信号函数**：`BacktestRunner` 直接调 `StrategyDetectorService.check()`，不另写一套逻辑，保证"回测得过的结论实盘可复现"（分析师核心要求）。
2. **增量落库而非全量**：`maxOpenTime()` 只补每周期新增K线，避免重复写库、减币安请求压力；只对关注标的(BTC/ETH，后续可扩展持仓/候选)落库，不铺600+币。
3. **ATR 放在信号侧而非平仓侧**：与领航员正在改的"引擎侧 ATR 止损"不冲突 —— 我在信号侧产出 ATR 波动尺度并持久化，他若在平仓侧叠加 ATR 缓冲，两处可协同（信号侧给 `atr_stop_price` 作参考，引擎侧已有 `defense_price` 对比）。
4. **成本模型**：回测已按 `slippagePct` 计入手续费/滑点；Sharpe 占位待日频收益序列接入。

---

## 四、待办 / 边界说明

- [ ] **编译验证**：本环境无 mvn/编译产物无法本地编译，需构建机 `cd yaob-backend && mvn -q package` 确认。
- [ ] **线程4关注标的扩展**：当前只落库 BTC/ETH；如需对当前持仓/候选币落库，在 `TradeEngineService` 扫描后调用 `marketDataService.ingestWatched(持仓symbols)`。
- [ ] **周/账户级二级熔断**：本次未改（与领航员单日熔断改造重叠，避免冲突），交接给领航员统一落地。
- [ ] **前端**：`/api/backtest/*` 后端已就绪，前端「回测」页/按钮未实现（可用 curl 测）。
- [ ] **样本要求**：回测需样本≥3个月且分钟级数据，落库线程4提前运行攒数据。

---

## 五、风险提示（对齐分析师）

- 妖币极端行情尾部风险远超正态假设，回测可能低估；务必"模拟盘→小资金≤5%→放大"三级验证后再上真金。
- 回测用市价成交假设，真实低流动性下滑点巨大；已用 `slippagePct` 计入，但建议回测后与实盘对照修正该参数。
