-- -------------------------------------------------------
-- v3.13 系统优化改造 - 数据落库与回测/实盘双通道前置
-- 对应「妖币系统优化方案」P0-1 / P0-4
-- 执行方式: mysql -h127.0.0.1 -P3307 -uyaob -p yaob_v3 < migration_v3.13_realtime.sql
-- -------------------------------------------------------

-- 1) 行情K线落库表（回测数据源 + 波动率/ATR 计算原始数据）
CREATE TABLE IF NOT EXISTS `market_data` (
  `id`        BIGINT       NOT NULL AUTO_INCREMENT,
  `symbol`    VARCHAR(32)  NOT NULL COMMENT '币安全原始交易对(BTCUSDT)',
  `interval`  VARCHAR(8)   NOT NULL COMMENT 'K线周期: 1m/5m/15m/1h/4h/1d',
  `open_time` BIGINT       NOT NULL COMMENT 'K线开盘时间(币安毫秒时间戳)',
  `open`      DECIMAL(20,8) NOT NULL,
  `high`      DECIMAL(20,8) NOT NULL,
  `low`       DECIMAL(20,8) NOT NULL,
  `close`     DECIMAL(20,8) NOT NULL,
  `volume`    DECIMAL(24,8) NOT NULL DEFAULT 0,
  `quote_volume` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '成交额(USDT)',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sym_int_ot` (`symbol`, `interval`, `open_time`),
  KEY `idx_symbol_interval` (`symbol`, `interval`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线行情落库(回测数据源)';

-- 2) 回测结果归档表（回测/实盘双通道: 回测结论持久化）
CREATE TABLE IF NOT EXISTS `backtest_results` (
  `id`           BIGINT NOT NULL AUTO_INCREMENT,
  `strategy`     VARCHAR(8) NOT NULL COMMENT '策略A-H',
  `params_json`  TEXT NULL COMMENT '策略参数快照',
  `symbol`       VARCHAR(32) NOT NULL COMMENT '标的交易对',
  `interval`     VARCHAR(8) NOT NULL,
  `start_ts`     BIGINT NOT NULL COMMENT '回测起始时间戳',
  `end_ts`       BIGINT NOT NULL COMMENT '回测结束时间戳',
  `total_signals` INT NOT NULL DEFAULT 0,
  `total_trades`  INT NOT NULL DEFAULT 0,
  `win_trades`    INT NOT NULL DEFAULT 0,
  `lose_trades`   INT NOT NULL DEFAULT 0,
  `win_rate`      DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '胜率(0-1)',
  `total_pnl`     DECIMAL(16,8) NOT NULL DEFAULT 0 COMMENT '累计盈亏(U)',
  `max_drawdown`  DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '最大回撤(%)',
  `avg_win`       DECIMAL(16,8) NOT NULL DEFAULT 0,
  `avg_lose`      DECIMAL(16,8) NOT NULL DEFAULT 0,
  `sharpe`        DECIMAL(10,4) NOT NULL DEFAULT 0,
  `slippage_pct`  DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '假设滑点(%)',
  `status`        VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT 'RUNNING/COMPLETED/FAILED',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_bt_strategy` (`strategy`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测结果归档表';

-- 3) open_positions 新增 ATR 自适应止损字段 (v3.13 P0-3)
ALTER TABLE `open_positions`
  ADD COLUMN IF NOT EXISTS `atr` DECIMAL(12,8) NULL COMMENT '开仓时ATR(14)' AFTER `reduce_price`,
  ADD COLUMN IF NOT EXISTS `atr_stop_price` DECIMAL(20,8) NULL COMMENT '2xATR缓冲止损绝对价' AFTER `atr`;
