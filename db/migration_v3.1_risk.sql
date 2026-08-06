-- V3.1 风控扩展字段（可选）
ALTER TABLE `users`
  ADD COLUMN IF NOT EXISTS `max_positions` INT NOT NULL DEFAULT 3 COMMENT '最大同时持仓数' AFTER `leverage`,
  ADD COLUMN IF NOT EXISTS `max_total_margin` DECIMAL(16,2) NOT NULL DEFAULT 50.00 COMMENT '最大占用保证金(U)' AFTER `max_positions`,
  ADD COLUMN IF NOT EXISTS `daily_loss_limit` DECIMAL(16,2) NOT NULL DEFAULT 30.00 COMMENT '单日亏损熔断(U)' AFTER `max_total_margin`,
  ADD COLUMN IF NOT EXISTS `max_hold_minutes` INT NOT NULL DEFAULT 1440 COMMENT '最长持仓分钟' AFTER `daily_loss_limit`;
