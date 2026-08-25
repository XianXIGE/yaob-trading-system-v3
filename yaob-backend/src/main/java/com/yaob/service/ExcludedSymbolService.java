package com.yaob.service;

import com.yaob.entity.ExcludedSymbol;
import com.yaob.mapper.ExcludedSymbolMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExcludedSymbolService {

    @Autowired
    private ExcludedSymbolMapper excludedSymbolMapper;

    @Autowired
    private CoinGeckoService coinGeckoService;

    @Autowired
    private BinanceFapiService binanceFapiService;

    // 旧大盘币默认列表 (v2 遗留) —— 保留用于兼容/降级，当前自动黑名单从 CoinGecko 市值拉取
    public static final List<String> DEFAULT_LARGE_CAP = List.of(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT",
            "DOGEUSDT", "DOTUSDT", "LINKUSDT", "LTCUSDT", "BCHUSDT", "AVAXUSDT",
            "SHIBUSDT", "TONUSDT", "TRXUSDT", "UNIUSDT", "ATOMUSDT", "XLMUSDT",
            "FILUSDT", "SUIUSDT", "NEARUSDT", "APTUSDT", "ARBUSDT", "OPUSDT",
            "INJUSDT", "SEIUSDT", "HBARUSDT", "ICPUSDT", "RENDERUSDT", "WIFUSDT",
            "TRUMPUSDT", "1000PEPEUSDT", "ETCUSDT"
    );

    public Map<String, Object> getExcluded(Long userId) {
        List<ExcludedSymbol> all = excludedSymbolMapper.findByUserId(userId);
        List<String> manual = new ArrayList<>();
        List<String> largeCap = new ArrayList<>();
        for (ExcludedSymbol e : all) {
            if ("manual".equals(e.getCategory())) {
                manual.add(e.getSymbol());
            } else if ("large_cap".equals(e.getCategory())) {
                largeCap.add(e.getSymbol());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        // 币种市值（manual）：按市值降序
        result.put("manual", sortByMarketCap(manual));
        // 股指市值（large_cap）自动黑名单：保持添加顺序（用户自行维护/自动拉取的高市值币）
        result.put("large_cap", largeCap);
        result.put("all", all.stream().map(ExcludedSymbol::getSymbol).collect(Collectors.toList()));
        return result;
    }

    /** 币种市值按市值降序排序（未知市值排最后） */
    private List<String> sortByMarketCap(List<String> symbols) {
        Set<String> binance = null;
        try {
            binance = binanceFapiService.getAllUsdtSymbols();
        } catch (Exception e) {
            log.warn("获取币安交易对失败，使用本地排序: {}", e.getMessage());
        }
        // symbolic: 先尝试 CoinGecko 拉市值排序（缓存），失败则按原顺序
        try {
            List<String> highCap = coinGeckoService.fetchHighCapSymbols(
                    binance == null ? Collections.emptySet() : binance);
            Set<String> highCapSet = new HashSet<>(highCap);
            List<String> sorted = new ArrayList<>();
            for (String s : highCap) {
                if (symbols.contains(s)) sorted.add(s);
            }
            for (String s : symbols) {
                if (!highCapSet.contains(s)) sorted.add(s);
            }
            return sorted;
        } catch (Exception e) {
            log.warn("市值排序失败，返回原顺序: {}", e.getMessage());
            return new ArrayList<>(symbols);
        }
    }

    public List<String> addExcluded(Long userId, List<String> symbols, String category) {
        List<ExcludedSymbol> existingAll = excludedSymbolMapper.findByUserId(userId);
        Set<String> existingSymbols = existingAll.stream()
                .map(ExcludedSymbol::getSymbol)
                .collect(Collectors.toSet());
        List<String> added = new ArrayList<>();
        for (String s : symbols) {
            String sym = s.toUpperCase();
            if (!existingSymbols.contains(sym)) {
                ExcludedSymbol ex = new ExcludedSymbol();
                ex.setUserId(userId);
                ex.setSymbol(sym);
                ex.setCategory(category);
                excludedSymbolMapper.insert(ex);
                existingSymbols.add(sym);
                added.add(sym);
            }
        }
        return added;
    }

    public void removeExcluded(Long userId, List<String> symbols, String category) {
        List<ExcludedSymbol> all = excludedSymbolMapper.findByUserId(userId);
        Set<String> toRemove = symbols.stream().map(String::toUpperCase).collect(Collectors.toSet());
        for (ExcludedSymbol e : all) {
            if (toRemove.contains(e.getSymbol())) {
                // 如果指定了 category，只删该分类的；否则全删
                if (category == null || category.equals(e.getCategory())) {
                    excludedSymbolMapper.deleteById(e.getId());
                }
            }
        }
    }

    public void clearExcluded(Long userId) {
        List<ExcludedSymbol> all = excludedSymbolMapper.findByUserId(userId);
        for (ExcludedSymbol e : all) {
            excludedSymbolMapper.deleteById(e.getId());
        }
    }

    public int restoreDefault(Long userId) {
        // 获取当前用户所有已存在 symbol（含任意分类），避免重复插入
        List<ExcludedSymbol> all = excludedSymbolMapper.findByUserId(userId);
        Set<String> existingSymbols = new HashSet<>();
        for (ExcludedSymbol e : all) {
            existingSymbols.add(e.getSymbol());
        }
        // 从 CoinGecko 拉取市值>阈值的币，过滤币安合约可交易，增量添加为自动黑名单(large_cap)
        // 注意：不删除任何已有记录（用户手动添加的保留，避免误删）
        Set<String> binance = Collections.emptySet();
        try {
            binance = binanceFapiService.getAllUsdtSymbols();
        } catch (Exception e) {
            log.error("获取币安交易对失败: {}", e.getMessage());
        }
        List<String> highCap = coinGeckoService.fetchHighCapSymbols(binance);
        int added = 0;
        for (String sym : highCap) {
            if (existingSymbols.contains(sym)) continue; // 跳过已在黑名单（含自动黑名单/手动添加）的币
            ExcludedSymbol ex = new ExcludedSymbol();
            ex.setUserId(userId);
            ex.setSymbol(sym);
            ex.setCategory("large_cap");
            excludedSymbolMapper.insert(ex);
            existingSymbols.add(sym);
            added++;
        }
        log.info("恢复自动黑名单：市值>{} 的币 {} 个，新增 {} 个",
                CoinGeckoService.MARKET_CAP_THRESHOLD, highCap.size(), added);
        return added;
    }
}
