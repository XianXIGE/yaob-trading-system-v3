#!/usr/bin/env python3
"""
妖币系统 V2 -> V3 数据迁移脚本
将 v2 的 JSON 文件数据迁移到 v3 的 MySQL 数据库

用法：
  python3 migrate_v2_to_v3.py --v2-dir /root/projects/yaob-v2/data --mysql-host 127.0.0.1 --mysql-port 3307

前置条件：
  1. MySQL 已启动（docker-compose up mysql）
  2. schema.sql 已执行（自动）
  3. pip install pymysql bcrypt
"""

import json
import os
import sys
import time
import argparse
from pathlib import Path

try:
    import pymysql
except ImportError:
    print("请先安装: pip install pymysql")
    sys.exit(1)

# =====================================================
# v2 默认策略参数（从 app.py _default_params 精确翻译）
# =====================================================
DEFAULT_PARAMS = {
    "a": {"lookback_days": 66, "gain_min": 0.36, "gain_max": 0.50,
          "vol_min": 1e7, "tp_ratio": 800, "sl_ratio": -20},
    "b": {"gain_threshold": 0.38, "vol_min": 1e7, "tp_ratio": 60, "sl_ratio": -20},
    "c": {"lookback_days": 7, "drop_threshold": 0.96, "vol_min": 1e8,
          "tp_ratio": 100, "sl_ratio": -20},
    "d": {"window_minutes": 5, "gain_threshold": 0.05, "vol_min": 1e7,
          "tp_ratio": 60, "sl_ratio": -20},
    "e": {"peak_gain_threshold": 0.50, "retrace_target_gain": 0.10,
          "vol_min": 1e7, "tp_ratio": 1200, "sl_ratio": -86},
    "f": {"lookback_hours": 48, "fib_long": 0.786, "fib_short": 0.618,
          "tolerance_ratio": 0.1, "vol_min": 3e7, "tp_ratio": 10, "sl_ratio": -15},
}

# v2 默认大盘币黑名单
DEFAULT_CRYPTO = [
    "BTC/USDT","ETH/USDT","BNB/USDT","SOL/USDT","XRP/USDT","ADA/USDT",
    "DOGE/USDT","DOT/USDT","LINK/USDT","LTC/USDT","BCH/USDT","AVAX/USDT",
    "SHIB/USDT","TON/USDT","TRX/USDT","UNI/USDT","ATOM/USDT","XLM/USDT",
    "FIL/USDT","SUI/USDT","NEAR/USDT","APT/USDT","ARB/USDT","OP/USDT",
    "INJ/USDT","SEI/USDT","HBAR/USDT","ICP/USDT","RENDER/USDT","WIF/USDT",
    "TRUMP/USDT","1000PEPE/USDT","ETC/USDT",
]

ADMIN_USER = os.getenv("ADMIN_USER", "XJarvis")


def load_json(path: Path, default=None):
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"  ⚠️ 读取 {path.name} 失败: {e}")
    return default if default is not None else {}


def migrate(v2_dir: Path, mysql_config: dict):
    print("=" * 60)
    print("妖币系统 V2 -> V3 数据迁移")
    print(f"  源目录: {v2_dir}")
    print(f"  MySQL:  {mysql_config['host']}:{mysql_config['port']}")
    print("=" * 60)

    # 连接 MySQL
    try:
        conn = pymysql.connect(**mysql_config, charset="utf8mb4")
        cursor = conn.cursor()
        print("✅ MySQL 连接成功")
    except Exception as e:
        print(f"❌ MySQL 连接失败: {e}")
        sys.exit(1)

    # 检查 schema 是否已建
    cursor.execute("SHOW TABLES")
    tables = [r[0] for r in cursor.fetchall()]
    if "users" not in tables:
        print("❌ 数据库表不存在，请先执行 schema.sql")
        sys.exit(1)
    print(f"✅ 数据库表就绪 ({len(tables)} 张表)")

    # =====================================================
    # 1. 迁移用户数据
    # =====================================================
    print("\n📋 1. 迁移用户数据...")
    users_data = load_json(v2_dir / "users.json", {})
    if not users_data:
        print("  ⚠️ users.json 为空或不存在，跳过")
    else:
        print(f"  发现 {len(users_data)} 个用户")
        for username, udata in users_data.items():
            # 检查是否已存在
            cursor.execute("SELECT id FROM users WHERE username = %s", (username,))
            existing = cursor.fetchone()

            is_admin = 1 if username == ADMIN_USER else 0
            is_vip = 1 if udata.get("is_vip") else 0
            vip_expire = udata.get("vip_expiry", "")
            if vip_expire:
                try:
                    # v2 格式 "2026-08-10 12:00:00" -> MySQL DATETIME
                    from datetime import datetime
                    vip_expire_dt = datetime.strptime(vip_expire, "%Y-%m-%d %H:%M:%S")
                except Exception:
                    vip_expire_dt = None
            else:
                vip_expire_dt = None

            # 交易配置
            trade = udata.get("trade", {})
            api_key = trade.get("api", {}).get("key", "")
            api_secret = trade.get("api", {}).get("secret", "")
            auto_trade = 1 if trade.get("auto_trade_enabled") else 0
            margin_mode = trade.get("margin_mode", "isolated")
            open_margin = trade.get("open_margin", 5.0)
            leverage = trade.get("leverage", 5)
            exclude_large = 1 if trade.get("exclude_large_cap", True) else 0

            # 密码：v2 用 werkzeug generate_password_hash，v3 用 BCrypt
            # 直接迁移原 hash（werkzeug 的 hash 也是 BCrypt 格式，但带前缀）
            password_hash = udata.get("password", "")
            if not password_hash:
                # 没有密码的用户跳过或设默认密码
                print(f"  ⚠️ 用户 {username} 无密码，跳过")
                continue

            if existing:
                print(f"  用户 {username} 已存在，跳过")
                continue

            cursor.execute("""
                INSERT INTO users (username, password_hash, is_vip, vip_expire_at, is_admin,
                                   binance_api_key, binance_api_secret, auto_trade_enabled,
                                   margin_mode, open_margin, leverage, exclude_large_cap)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (username, password_hash, is_vip, vip_expire_dt, is_admin,
                  api_key, api_secret, auto_trade, margin_mode, open_margin, leverage, exclude_large))
            user_id = cursor.lastrowid
            print(f"  ✅ 用户 {username} -> id={user_id}")

            # =====================================================
            # 2. 迁移策略配置（从 trade.params 或默认值）
            # =====================================================
            params = trade.get("params") or DEFAULT_PARAMS
            states = trade.get("strategy_states", {"a": True, "b": False, "c": False,
                                                    "d": False, "e": True, "f": True})
            for sk in "abcdef":
                p = params.get(sk, DEFAULT_PARAMS[sk])
                tp = p.get("tp_ratio", 0)
                sl = p.get("sl_ratio", 0)
                enabled = 1 if states.get(sk) else 0
                # params_json: 存除 tp/sl 外的专属参数
                exclusive = {k: v for k, v in p.items() if k not in ("tp_ratio", "sl_ratio")}
                params_json = json.dumps(exclusive, ensure_ascii=False) if exclusive else None
                cursor.execute("""
                    INSERT INTO strategy_configs (user_id, strategy, enabled, tp_ratio, sl_ratio, params_json)
                    VALUES (%s, %s, %s, %s, %s, %s)
                """, (user_id, sk.upper(), enabled, tp, sl, params_json))
            print(f"  ✅ 策略配置 A-F 已写入")

            # =====================================================
            # 3. 迁移持仓记录 (open_records -> open_positions)
            # =====================================================
            open_records = trade.get("open_records", {})
            if open_records:
                for symbol, rec in open_records.items():
                    strategy = rec.get("strategy", "A").upper()[:1]
                    direction = "SHORT" if rec.get("strategy", "").upper() in ("A", "B", "D") else "LONG"
                    # F 策略可能是多或空
                    if strategy == "F":
                        direction = rec.get("direction", "LONG").upper()
                    qty = rec.get("qty", 0)
                    entry_price = rec.get("entry_price", 0)
                    tp = rec.get("tp_ratio", 0)
                    sl = rec.get("sl_ratio", 0)
                    open_time = rec.get("open_time", "")
                    # 尝试解析时间
                    try:
                        from datetime import datetime
                        opened_at = datetime.strptime(open_time, "%Y-%m-%d %H:%M:%S") if open_time else None
                    except Exception:
                        opened_at = None

                    cursor.execute("""
                        INSERT INTO open_positions (user_id, symbol, strategy, direction, qty, entry_price,
                                                    leverage, tp_ratio, sl_ratio, status, opened_at)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'OPEN', %s)
                    """, (user_id, symbol, strategy, direction, qty, entry_price,
                          leverage, tp, sl, opened_at))
                print(f"  ✅ 持仓记录 {len(open_records)} 条已迁移")

            # =====================================================
            # 4. 迁移交易历史 (trade_history)
            # =====================================================
            history = trade.get("trade_history", [])
            if history:
                for h in history:
                    symbol = h.get("symbol", "")
                    strategy = h.get("strategy", "A").upper()[:1]
                    direction = h.get("direction", "")
                    qty = h.get("qty", 0)
                    entry_price = h.get("open_price", 0)
                    exit_price = h.get("close_price")
                    pnl_ratio = h.get("pnl_ratio")
                    close_reason = h.get("close_reason")
                    status = h.get("status", "OPEN")

                    # 解析时间
                    from datetime import datetime
                    opened_at = None
                    closed_at = None
                    try:
                        if h.get("open_time"):
                            opened_at = datetime.strptime(h["open_time"], "%Y-%m-%d %H:%M:%S")
                    except Exception:
                        pass
                    try:
                        if h.get("close_time"):
                            closed_at = datetime.strptime(h["close_time"], "%Y-%m-%d %H:%M:%S")
                    except Exception:
                        pass

                    cursor.execute("""
                        INSERT INTO trade_history (user_id, symbol, strategy, direction, qty,
                                                   entry_price, exit_price, leverage, pnl_ratio,
                                                   close_reason, opened_at, closed_at)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """, (user_id, symbol, strategy, direction, qty, entry_price, exit_price,
                          leverage, pnl_ratio, close_reason, opened_at, closed_at))
                print(f"  ✅ 交易历史 {len(history)} 条已迁移")

            # =====================================================
            # 5. 迁移黑名单
            # =====================================================
            excluded_data = load_json(v2_dir / "excluded.json", {"crypto": [], "index": []})
            # 大盘币黑名单
            for sym in excluded_data.get("crypto", []):
                cursor.execute("""
                    INSERT IGNORE INTO excluded_symbols (user_id, symbol, category)
                    VALUES (%s, %s, 'large_cap')
                """, (user_id, sym))
            # 手动黑名单
            for sym in excluded_data.get("index", []):
                cursor.execute("""
                    INSERT IGNORE INTO excluded_symbols (user_id, symbol, category)
                    VALUES (%s, %s, 'manual')
                """, (user_id, sym))
            total_excluded = len(excluded_data.get("crypto", [])) + len(excluded_data.get("index", []))
            if total_excluded:
                print(f"  ✅ 黑名单 {total_excluded} 条已迁移")

    # =====================================================
    # 6. 如果 users.json 为空，创建管理员账号
    # =====================================================
    if not users_data:
        print("\n  ⚠️ 无用户数据，创建默认管理员账号...")
        import bcrypt
        default_hash = bcrypt.hashpw(b"admin123", bcrypt.gensalt()).decode()
        cursor.execute("""
            INSERT INTO users (username, password_hash, is_vip, is_admin, auto_trade_enabled)
            VALUES (%s, %s, 1, 1, 0)
        """, (ADMIN_USER, default_hash))
        admin_id = cursor.lastrowid
        # 初始化策略配置
        for sk in "abcdef":
            p = DEFAULT_PARAMS[sk]
            cursor.execute("""
                INSERT INTO strategy_configs (user_id, strategy, enabled, tp_ratio, sl_ratio, params_json)
                VALUES (%s, %s, %s, %s, %s, %s)
            """, (admin_id, sk.upper(), 1 if sk in ("a", "e", "f") else 0,
                  p["tp_ratio"], p["sl_ratio"],
                  json.dumps({k: v for k, v in p.items() if k not in ("tp_ratio", "sl_ratio")}, ensure_ascii=False)))
        # 初始化默认黑名单
        for sym in DEFAULT_CRYPTO:
            cursor.execute("""
                INSERT IGNORE INTO excluded_symbols (user_id, symbol, category)
                VALUES (%s, %s, 'large_cap')
            """, (admin_id, sym))
        print(f"  ✅ 管理员 {ADMIN_USER} 已创建 (密码: admin123)")
        print(f"  ✅ 策略配置 + 黑名单已初始化")

    # =====================================================
    # 7. 迁移统计数据 (stats.json -> strategy_stats)
    # =====================================================
    print("\n📋 2. 迁移统计数据...")
    stats_data = load_json(v2_dir / "stats.json", {})
    real_stats = stats_data.get("real", {})
    if real_stats:
        # 全局统计（不按用户分），写入管理员
        cursor.execute("SELECT id FROM users WHERE is_admin = 1 LIMIT 1")
        admin_row = cursor.fetchone()
        if admin_row:
            admin_id = admin_row[0]
            total = real_stats.get("total_trades", 0)
            wins = real_stats.get("win_trades", 0)
            total_pnl = real_stats.get("total_pnl", 0)
            cursor.execute("""
                INSERT INTO strategy_stats (user_id, strategy, total_trades, win_trades, total_pnl)
                VALUES (%s, 'A', %s, %s, %s)
                ON DUPLICATE KEY UPDATE total_trades=VALUES(total_trades), win_trades=VALUES(win_trades), total_pnl=VALUES(total_pnl)
            """, (admin_id, total, wins, total_pnl))
            print(f"  ✅ 全局统计已迁移: {total} 笔交易, {wins} 胜, 盈亏 {total_pnl}")

    conn.commit()
    print("\n" + "=" * 60)
    print("✅ 迁移完成！")
    print("=" * 60)

    # 验证
    cursor.execute("SELECT COUNT(*) FROM users")
    user_count = cursor.fetchone()[0]
    cursor.execute("SELECT COUNT(*) FROM strategy_configs")
    sc_count = cursor.fetchone()[0]
    cursor.execute("SELECT COUNT(*) FROM open_positions WHERE status = 'OPEN'")
    op_count = cursor.fetchone()[0]
    cursor.execute("SELECT COUNT(*) FROM trade_history")
    th_count = cursor.fetchone()[0]
    cursor.execute("SELECT COUNT(*) FROM excluded_symbols")
    ex_count = cursor.fetchone()[0]

    print(f"\n📊 迁移结果验证：")
    print(f"  用户:       {user_count}")
    print(f"  策略配置:   {sc_count}")
    print(f"  活跃持仓:   {op_count}")
    print(f"  交易历史:   {th_count}")
    print(f"  黑名单:     {ex_count}")

    cursor.close()
    conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="妖币系统 V2->V3 数据迁移")
    parser.add_argument("--v2-dir", default="/root/projects/yaob-v2/data",
                        help="V2 data 目录路径")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3307)
    parser.add_argument("--mysql-user", default="yaob")
    parser.add_argument("--mysql-password", default="yaob_pass_2026")
    parser.add_argument("--mysql-db", default="yaob_v3")
    args = parser.parse_args()

    v2_dir = Path(args.v2_dir)
    if not v2_dir.exists():
        print(f"❌ V2 data 目录不存在: {v2_dir}")
        sys.exit(1)

    mysql_config = {
        "host": args.mysql_host,
        "port": args.mysql_port,
        "user": args.mysql_user,
        "password": args.mysql_password,
        "database": args.mysql_db,
    }

    migrate(v2_dir, mysql_config)
