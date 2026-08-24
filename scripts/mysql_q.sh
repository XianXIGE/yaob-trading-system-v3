#!/usr/bin/env bash
# yaob-mysql 查询助手 (2026-08-24)
#
# 背景: 旧临时命令把 2>&1 合并进管道再 grep -v Warning 过滤,
#       mysql 成功但结果为空时 grep 无输出返回 1, 造成假 "Exec failed" 告警。
# 修正: 退出码只跟 mysql 走; 用 MYSQL_PWD 环境变量传密码,
#       彻底不产生 "insecure" 警告, 无需 grep 过滤。
#
# 用法:
#   ./mysql_q.sh 'SHOW COLUMNS FROM strategy_configs LIKE "strategy_type";'
#   ./mysql_q.sh 'SELECT 1;' yaob_v3        # 第二参数可选, 默认 yaob_v3
#
# 退出码: 0=mysql 执行成功(结果可能为空), 非0=mysql 真实失败
set -uo pipefail

SQL="${1:?用法: mysql_q.sh 'SQL语句' [库名,默认yaob_v3]}"
DB="${2:-yaob_v3}"

sudo docker exec -e SQL="$SQL" -e DB="$DB" yaob-mysql \
  sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$DB" -e "$SQL"'
