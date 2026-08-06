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
