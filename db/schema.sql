-- =====================================================
-- 妖币交易系统 V3.0 数据库设计
-- MySQL 8.0 + InnoDB + utf8mb4
-- =====================================================

CREATE DATABASE IF NOT EXISTS yaob_v3
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE yaob_v3;

-- -------------------------------------------------------
-- 1. 用户表
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
  `username`            VARCHAR(64)  NOT NULL COMMENT '用户名',
  `password_hash`       VARCHAR(255) NOT NULL COMMENT 'BCrypt密码哈希',
  `is_vip`              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否VIP',
  `vip_expire_at`       DATETIME     NULL COMMENT 'VIP到期时间(NULL=永久)',
  `is_admin`            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否管理员',
  `binance_api_key`     VARCHAR(256) NOT NULL DEFAULT '' COMMENT '币安API Key',
  `binance_api_secret`  VARCHAR(256) NOT NULL DEFAULT '' COMMENT '币安API Secret',
  `auto_trade_enabled`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '自动交易开关',
  `margin_mode`         VARCHAR(16)  NOT NULL DEFAULT 'isolated' COMMENT '全仓/逐仓: cross/isolated',
  `position_mode`       VARCHAR(16)  NOT NULL DEFAULT 'oneway' COMMENT '持仓模式: oneway=单向/BOTH, hedge=双向/LONG|SHORT',
  `open_margin`         DECIMAL(16,2) NOT NULL DEFAULT 5.00 COMMENT '单笔开仓保证金(U)',
  `leverage`            INT          NOT NULL DEFAULT 5 COMMENT '杠杆倍数',
  `exclude_large_cap`   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '排除大盘币',
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- -------------------------------------------------------
-- 2. 策略配置表（每用户6个策略各一行 A-F）
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `strategy_configs` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT       NOT NULL,
  `strategy`     CHAR(1)      NOT NULL COMMENT '策略A-G',
  `enabled`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否启用',
  `tp_ratio`     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '止盈比例(%)',
  `sl_ratio`     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '止损比例(%)',
  `params_json`  JSON         NULL COMMENT '策略专属参数(lookback/gain_threshold等)',
  `strategy_type` VARCHAR(50)  NULL COMMENT '策略类型',
  `description`   VARCHAR(200) NULL COMMENT '策略描述',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_strategy` (`user_id`, `strategy`),
  CONSTRAINT `fk_sc_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略配置表';

-- -------------------------------------------------------
-- 3. 持仓记录表（核心！需要事务保护）
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `open_positions` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT       NOT NULL,
  `symbol`       VARCHAR(32)  NOT NULL COMMENT '交易对(BTCUSDT)',
  `strategy`     CHAR(1)      NOT NULL COMMENT '开仓策略A-F',
  `direction`    VARCHAR(8)   NOT NULL COMMENT 'LONG/SHORT',
  `qty`          DECIMAL(20,8) NOT NULL COMMENT '持仓数量',
  `entry_price`  DECIMAL(20,8) NOT NULL COMMENT '开仓价格',
  `leverage`     INT          NOT NULL COMMENT '杠杆',
  `tp_ratio`     DECIMAL(10,2) NOT NULL COMMENT '止盈比例(快照)',
  `sl_ratio`     DECIMAL(10,2) NOT NULL COMMENT '止损比例(快照)',
  `status`       VARCHAR(8)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
  `opened_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `closed_at`    DATETIME     NULL,
  `close_price`  DECIMAL(20,8) NULL COMMENT '平仓价格',
  `close_reason` VARCHAR(16)  NULL COMMENT 'tp/sl/manual/timeout',
  `pnl`          DECIMAL(16,8) NULL COMMENT '盈亏金额(U)',
  `pnl_ratio`    DECIMAL(10,4) NULL COMMENT '盈亏比例(%)',
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_symbol` (`symbol`),
  CONSTRAINT `fk_op_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓记录表';

-- -------------------------------------------------------
-- 4. 交易流水表（平仓后归档，用于统计/回测）
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `trade_history` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT       NOT NULL,
  `position_id`  BIGINT       NULL COMMENT '关联持仓ID',
  `symbol`       VARCHAR(32)  NOT NULL,
  `strategy`     CHAR(1)      NOT NULL,
  `direction`    VARCHAR(8)   NOT NULL COMMENT 'LONG/SHORT',
  `qty`          DECIMAL(20,8) NOT NULL,
  `entry_price`  DECIMAL(20,8) NOT NULL,
  `exit_price`   DECIMAL(20,8) NULL,
  `leverage`     INT          NOT NULL,
  `pnl`          DECIMAL(16,8) NULL COMMENT '盈亏金额(U)',
  `pnl_ratio`    DECIMAL(10,4) NULL COMMENT '盈亏比例(%)',
  `close_reason` VARCHAR(16)  NULL COMMENT 'tp/sl/manual/timeout',
  `opened_at`    DATETIME     NOT NULL,
  `closed_at`    DATETIME     NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_strategy` (`strategy`),
  KEY `idx_opened` (`opened_at`),
  CONSTRAINT `fk_th_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易流水表';

-- -------------------------------------------------------
-- 5. 黑名单表
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `excluded_symbols` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT       NOT NULL,
  `symbol`       VARCHAR(32)  NOT NULL,
  `category`     VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT 'manual/large_cap',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_symbol` (`user_id`, `symbol`),
  CONSTRAINT `fk_es_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单表';

-- -------------------------------------------------------
-- 6. 策略统计表（缓存层，定期从 trade_history 汇总）
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `strategy_stats` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT       NOT NULL,
  `strategy`      CHAR(1)      NOT NULL,
  `total_trades`  INT          NOT NULL DEFAULT 0,
  `win_trades`    INT          NOT NULL DEFAULT 0,
  `total_pnl`     DECIMAL(16,8) NOT NULL DEFAULT 0,
  `tp_count`      INT          NOT NULL DEFAULT 0,
  `sl_count`      INT          NOT NULL DEFAULT 0,
  `manual_count`  INT          NOT NULL DEFAULT 0,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_strategy` (`user_id`, `strategy`),
  CONSTRAINT `fk_ss_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略统计表';

-- -------------------------------------------------------
-- 初始数据：默认大盘币黑名单模板（插入时按需 per-user）
-- -------------------------------------------------------
-- 大盘币种列表（迁移自 v2 DEFAULT_CRYPTO）
-- 这些会在用户注册时自动插入为 large_cap 类别黑名单

-- -------------------------------------------------------
-- 初始管理员账号（修复注册即管理员漏洞后，管理员无法再通过注册创建，
-- 必须在数据库初始化时预置。密码通过 BCrypt 存储，来源：
--   用户名: XJarvis
--   密码:  942693Xw!
-- 如需更换，用 BCrypt 工具生成新哈希替换下方 password_hash。
-- -------------------------------------------------------
INSERT INTO `users`
  (`username`, `password_hash`, `is_vip`, `is_admin`, `binance_api_key`, `binance_api_secret`, `auto_trade_enabled`)
VALUES
  ('XJarvis', '$2a$10$JbKD/z7GMPguEuKx8GsNROKyg5OdQ8NN.oMzlB6osvTT/oLNkmR6y', 1, 1, '', '', 0)
ON DUPLICATE KEY UPDATE `is_admin` = 1;

-- -------------------------------------------------------
-- 预置策略配置种子数据（A-G 七条默认策略，参数与 UserService.DEFAULT_PARAMS 对齐）
-- 方案B清库重建后策略配置表为空，需预置否则前端策略页无数据、保存/开关失效
-- -------------------------------------------------------
INSERT INTO `strategy_configs`
  (`user_id`, `strategy`, `enabled`, `tp_ratio`, `sl_ratio`, `params_json`, `strategy_type`, `description`)
VALUES
  (1,'A',1,800.00,-20.00,'{"lookback_days":66,"gain_min":36,"gain_max":50,"vol_min":10000000,"tp_ratio":800,"sl_ratio":-20}','short','做空 - 24h涨幅区间扫描，寻找高涨幅标的做空'),
  (1,'B',0,60.00,-20.00,'{"gain_threshold":38,"vol_min":10000000,"tp_ratio":60,"sl_ratio":-20}','short','做空 - 当日涨幅突破，大幅上涨后做空'),
  (1,'C',0,100.00,-20.00,'{"lookback_days":7,"drop_threshold":96,"vol_min":100000000,"tp_ratio":100,"sl_ratio":-20}','long','做多 - 高点回撤反弹，回落后做多'),
  (1,'D',0,60.00,-20.00,'{"window_minutes":5,"gain_threshold":5,"vol_min":10000000,"tp_ratio":60,"sl_ratio":-20}','short','做空 - 短时急涨做空，分钟级急涨做空'),
  (1,'E',1,1200.00,-86.00,'{"gain_30d_min":100,"ema_period":50,"pullback_min":20,"pullback_max":40,"fib_entry":0.618,"volume_mult":1.5,"rsi_threshold":30,"vol_min":10000000,"tp_ratio":1200,"sl_ratio":-86}','long','强趋势回踩：30天涨幅>100%，EMA50上方，回撤20%-40%至0.618 Fib'),
  (1,'F',1,120.00,-20.00,'{"lookback_hours":48,"fib_long":0.618,"fib_short":0.382,"tolerance_ratio":0.1,"vol_min":30000000,"tp_ratio":120,"sl_ratio":-20}','fibonacci','1小时斐波那契位置，15分钟确认入场'),
  (1,'G',0,10.00,-5.00,'{"ema_short":20,"ema_long":60,"vol_ratio_min":1.3,"rsi_oversold":32,"wick_body_ratio":1.5,"vol_min":10000000,"tp_ratio":10,"sl_ratio":-5}','multi','日内多空三重过滤：1h EMA20/60趋势+量比+RSI')
ON DUPLICATE KEY UPDATE `enabled`=VALUES(`enabled`), `tp_ratio`=VALUES(`tp_ratio`), `sl_ratio`=VALUES(`sl_ratio`), `params_json`=VALUES(`params_json`), `strategy_type`=VALUES(`strategy_type`), `description`=VALUES(`description`);
