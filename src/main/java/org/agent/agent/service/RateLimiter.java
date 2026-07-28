package org.agent.agent.service;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流熔断 —— 防止 API 费用爆炸
 */
public class RateLimiter {

    private final JavaPlugin plugin;
    private int globalMaxPerMinute;
    private int playerMaxPerMinute;
    private long cooldownMs;

    // 全局调用记录
    private final long[] globalTimestamps = new long[60];
    private int globalIndex = 0;

    // 玩家级调用记录
    private final Map<UUID, List<Long>> playerLogs = new ConcurrentHashMap<>();

    public RateLimiter(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.globalMaxPerMinute = config.getInt("rate_limit.global_max_per_minute", 30);
        this.playerMaxPerMinute = config.getInt("rate_limit.player_max_per_minute", 10);
        this.cooldownMs = config.getInt("agent.cooldown_seconds", 3) * 1000L;
    }

    /** 检查是否允许调用，false = 被限流 */
    public boolean allow(UUID playerId) {
        long now = System.currentTimeMillis();

        // 全局限流
        synchronized (globalTimestamps) {
            int count = 0;
            for (long t : globalTimestamps) {
                if (now - t < 60000) count++;
            }
            if (count >= globalMaxPerMinute) return false;
            globalTimestamps[globalIndex % 60] = now;
            globalIndex++;
        }

        // 玩家级限流
        List<Long> playerLog = playerLogs.computeIfAbsent(playerId, k -> new ArrayList<>());
        synchronized (playerLog) {
            // 移除 60 秒前的记录
            while (!playerLog.isEmpty() && now - playerLog.get(0) > 60000) {
                playerLog.remove(0);
            }
            if (playerLog.size() >= playerMaxPerMinute) return false;
            playerLog.add(now);
        }

        return true;
    }

    /** 检查是否在冷却期 */
    public boolean isCooldown(UUID playerId, long lastTriggerTime) {
        return System.currentTimeMillis() - lastTriggerTime < cooldownMs;
    }

    public int getGlobalMaxPerMinute() { return globalMaxPerMinute; }
    public int getPlayerMaxPerMinute() { return playerMaxPerMinute; }
}
