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
