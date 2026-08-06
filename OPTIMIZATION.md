# 妖币交易系统 V3.1 优化说明

分支：`optimize/v3.1-risk-and-refactor`

## 已落地
1. **RiskManager**：最大持仓 / 总保证金 / 日亏损熔断 / 持仓超时强平
2. **AesEncryptor**：API Key AES-GCM 加密存储（`ENC:` 前缀，兼容明文）
3. **TradeEngineService**：开仓风控拦截、解密调币安、timeout 平仓
4. **docker-compose**：强制 `DB_*` / `CRYPTO_SECRET`，去掉弱默认密码
5. **application.yml**：`risk.*` + `crypto.secret`

## 启动前环境变量
```bash
export DB_ROOT_PASSWORD='强密码1'
export DB_PASSWORD='强密码2'
export CRYPTO_SECRET='至少32位随机字符串'
docker compose up -d --build
```

已有用户下次在前端重新保存 API Key 会自动加密。

## 第二阶段（本提交）

### 1. MarketDataService 共享行情缓存
- 全市场 ticker Redis/本地缓存（默认 TTL 45s）
- K 线缓存（默认 TTL 60s）
- 资金费率缓存（premiumIndex，TTL 120s）
- 多用户扫描共用同一份数据，降低币安限频风险

### 2. StrategyChecker 策略拆分
- A–F 策略检测从 TradeEngineService 抽离
- TradeEngine 只负责调度，策略逻辑独立可测

### 3. 资金费率过滤
- 候选信号在 `|lastFundingRate| >= 0.3%` 时跳过，避免极端费率环境开仓

### 4. BinanceFapiService
- 新增 `premiumIndex()` 接口
