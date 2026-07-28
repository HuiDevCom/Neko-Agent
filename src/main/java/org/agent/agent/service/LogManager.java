package org.agent.agent.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 统一日志体系 —— 对话 / 费用 / 统计 / 错误 / 审计 / 调试 + 自动轮转清理
 */
public class LogManager {

    private final JavaPlugin plugin;
    private final File logDir;
    private boolean debugMode;

    // 保留期配置（天）
    private int chatRetention = 30;
    private int errorRetention = 7;
    private int statsRetention = 90;
    private int auditRetention = 90;

    // 当日统计（内存计数器，每分钟落盘）
    private final AtomicInteger todayCalls = new AtomicInteger(0);
    private final AtomicInteger todaySuccess = new AtomicInteger(0);
    private final AtomicInteger todayFailed = new AtomicInteger(0);
    private final AtomicLong todayTokensIn = new AtomicLong(0);
    private final AtomicLong todayTokensOut = new AtomicLong(0);

    // 最近的错误列表（内存中保留最多 100 条）
    private final List<String> recentErrors = new CopyOnWriteArrayList<>();

    // 玩家对话次数统计
    private final Map<String, AtomicInteger> playerCallCount = new ConcurrentHashMap<>();

    public LogManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        for (String sub : new String[]{"chat", "error", "audit", "stats"}) {
            new File(logDir, sub).mkdirs();
        }
        this.debugMode = plugin.getConfig().getBoolean("agent.debug", false);
        loadRetentionConfig();

        // 每分钟落盘统计
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushStats, 1200L, 1200L);
        // 每天凌晨清理旧日志
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanOldLogs, 6000L, 72000L);
    }

    private void loadRetentionConfig() {
        var config = plugin.getConfig();
        chatRetention = config.getInt("logs.retention.chat", 30);
        errorRetention = config.getInt("logs.retention.error", 7);
        statsRetention = config.getInt("logs.retention.stats", 90);
        auditRetention = config.getInt("logs.retention.audit", 90);
    }

    // ==================== 对话日志 ====================

    /** 记录一次完整对话 */
    public void logChat(Map<String, String> fields) {
        try {
            String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File file = new File(logDir, "chat/" + date + ".csv");
            boolean isNew = !file.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
                if (isNew) {
                    pw.println("timestamp,player,uuid,world,dimension,loc,trigger,user_msg,ai_response,tokens_in,tokens_out,model,cost_est,latency,actions,lang,success");
                }
                pw.println(formatCsvLine(fields));
            }

            // 更新统计
            todayCalls.incrementAndGet();
            if ("true".equals(fields.getOrDefault("success", "true"))) {
                todaySuccess.incrementAndGet();
            } else {
                todayFailed.incrementAndGet();
            }
            String tokensInStr = fields.getOrDefault("tokens_in", "0");
            String tokensOutStr = fields.getOrDefault("tokens_out", "0");
            todayTokensIn.addAndGet(parseIntSafe(tokensInStr));
            todayTokensOut.addAndGet(parseIntSafe(tokensOutStr));

            String player = fields.getOrDefault("player", "?");
            playerCallCount.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();

        } catch (IOException e) {
            plugin.getLogger().warning("写入对话日志失败: " + e.getMessage());
        }
    }

    // ==================== 错误日志 ====================

    public void logError(String context, String message, Throwable ex) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String entry = String.format("[%s] [%s] %s: %s", ts, context, message,
                ex != null ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : "");
        recentErrors.add(0, entry);
        if (recentErrors.size() > 100) recentErrors.remove(recentErrors.size() - 1);

        try {
            File file = new File(logDir, "error/" + date + ".log");
            try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
                pw.println(entry);
                if (ex != null) {
                    for (var ste : ex.getStackTrace()) {
                        pw.println("  at " + ste);
                        if (ste.toString().contains("org.agent")) break; // 只打印插件相关栈
                    }
                }
                // 附建议
                String suggestion = getSuggestion(context, message);
                if (suggestion != null) {
                    pw.println("  → 建议: " + suggestion);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("写入错误日志失败: " + e.getMessage());
        }
    }

    private String getSuggestion(String context, String message) {
        if (message.contains("timed out") || message.contains("timeout"))
            return "检查服务器网络能否访问 API 地址，或增大 timeout_seconds";
        if (message.contains("401") || message.contains("403"))
            return "API Key 无效，检查 config.yml 中的 api_key 配置";
        if (message.contains("429"))
            return "请求过于频繁，检查 rate_limit 配置或等待后重试";
        if (message.contains("status code: 5"))
            return "API 服务端错误，通常是暂时性问题，等待后重试即可";
        if (context.contains("Kimi") || context.contains("search"))
            return "Kimi 搜索失败，检查 kimi.api_key 和网络连接";
        return null;
    }

    /** 获取最近错误 */
    public List<String> getRecentErrors(int limit) {
        return recentErrors.stream().limit(limit).collect(Collectors.toList());
    }

    // ==================== 审计日志 ====================

    public void logAudit(String player, String action, String detail) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        try {
            File file = new File(logDir, "audit/" + date + ".csv");
            boolean isNew = !file.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
                if (isNew) pw.println("timestamp,player,action,detail");
                pw.printf("%s,%s,%s,\"%s\"%n", ts, player, action, detail.replace("\"", "\"\""));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("写入审计日志失败: " + e.getMessage());
        }
    }

    // ==================== 调试日志 ====================

    public void debug(String message) {
        if (!debugMode) return;
        plugin.getLogger().info("[DEBUG] " + message);
    }

    /** 重新加载配置 */
    public void reloadConfig() {
        this.debugMode = plugin.getConfig().getBoolean("agent.debug", false);
        loadRetentionConfig();
    }

    /** 估算 token 数（中文≈2, 英文≈1） */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int tokens = 0;
        for (char c : text.toCharArray()) {
            tokens += (c > 127) ? 2 : 1;
        }
        return tokens / 4;
    }

    // ==================== 统计 ====================

    private void flushStats() {
        int calls = todayCalls.get();
        if (calls == 0) return;
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        long tokensIn = todayTokensIn.get();
        long tokensOut = todayTokensOut.get();
        double costEst = estimateCost(tokensIn, tokensOut);
        long success = todaySuccess.get();
        long failed = todayFailed.get();

        try {
            File statsFile = new File(logDir, "stats/" + date + ".csv");
            boolean isNew = !statsFile.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(statsFile, true))) {
                if (isNew) pw.println("timestamp,calls,success,failed,tokens_in,tokens_out,cost_est,active_players");
                pw.printf("%s,%d,%d,%d,%d,%d,%.6f,%d%n", ts, calls, success, failed, tokensIn, tokensOut, costEst, playerCallCount.size());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("写入统计日志失败: " + e.getMessage());
        }
    }

    /** 估算费用（DeepSeek Flash ~¥1/1M tokens） */
    private double estimateCost(long tokensIn, long tokensOut) {
        double ratePer1M = 1.0; // DeepSeek Flash ¥1/1M tokens
        return (tokensIn + tokensOut) * ratePer1M / 1_000_000.0;
    }

    /** 获取今日统计摘要 */
    public String getTodayStats() {
        return String.format(
                "§e调用: §f%d次 §7(成功 %d / 失败 %d)" +
                        "\n§eToken: §f输入 %d / 输出 %d" +
                        "\n§e估算费用: §f¥%.4f" +
                        "\n§e活跃玩家: §f%d人",
                todayCalls.get(), todaySuccess.get(), todayFailed.get(),
                todayTokensIn.get(), todayTokensOut.get(),
                estimateCost(todayTokensIn.get(), todayTokensOut.get()),
                playerCallCount.size()
        );
    }

    /** 获取今日费用 */
    public String getTodayCost() {
        double cost = estimateCost(todayTokensIn.get(), todayTokensOut.get());
        return String.format("§e今日估算费用: §f¥%.4f (输入 %d / 输出 %d tokens)",
                cost, todayTokensIn.get(), todayTokensOut.get());
    }

    // ==================== 日志查看 ====================

    /** 读取今日最近 N 条对话记录 */
    public List<String> readRecentChats(int limit) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        File file = new File(logDir, "chat/" + date + ".csv");
        if (!file.exists()) return List.of("§7暂无对话记录");
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            // 跳过表头，从后往前取
            List<String> result = new ArrayList<>();
            for (int i = lines.size() - 1; i > 0 && result.size() < limit; i--) {
                String[] parts = parseCsvLine(lines.get(i));
                if (parts.length >= 6) {
                    String ts = parts[0];
                    String player = parts[1];
                    String trigger = parts.length > 5 ? parts[5] : "?";
                    String msg = parts.length > 6 ? parts[6].replace("\"", "") : "";
                    String success = parts.length > 16 ? parts[16] : "?";
                    result.add("§7[" + ts + "] §f" + player + " §7(" + trigger + ") §f" +
                            msg.substring(0, Math.min(msg.length(), 40)) +
                            (msg.length() > 40 ? "..." : "") +
                            " §a" + ("true".equals(success) ? "✓" : "✗"));
                }
            }
            return result.isEmpty() ? List.of("§7暂无对话记录") : result;
        } catch (IOException e) {
            return List.of("§c读取日志失败: " + e.getMessage());
        }
    }

    /** 搜索包含关键词的对话 */
    public List<String> searchChats(String keyword, int limit) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        File file = new File(logDir, "chat/" + date + ".csv");
        if (!file.exists()) return List.of("§7暂无对话记录");
        try {
            return Files.lines(file.toPath())
                    .skip(1)
                    .filter(l -> l.contains(keyword))
                    .limit(limit)
                    .map(l -> {
                        String[] parts = parseCsvLine(l);
                        return "§7[" + parts[0] + "] §f" + parts[1] + ": §7" +
                                (parts.length > 6 ? parts[6].replace("\"", "").substring(0, Math.min(parts[6].length(), 50)) : "");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of("§c搜索失败: " + e.getMessage());
        }
    }

    /** 查询指定玩家的记录 */
    public List<String> getPlayerLogs(String playerName, int limit) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        File file = new File(logDir, "chat/" + date + ".csv");
        if (!file.exists()) return List.of("§7该玩家暂无记录");
        try {
            // 匹配 CSV 第二列（player 字段）
            return Files.lines(file.toPath())
                    .skip(1)
                    .filter(l -> {
                        String[] parts = parseCsvLine(l);
                        return parts.length > 1 && parts[1].equals(playerName);
                    })
                    .limit(limit)
                    .map(l -> {
                        String[] parts = parseCsvLine(l);
                        return "§7[" + parts[0] + "] §f" + (parts.length > 6 ? parts[6].replace("\"", "").substring(0, Math.min(parts[6].length(), 50)) : "");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of("§c查询失败: " + e.getMessage());
        }
    }

    // ==================== 日志轮转清理 ====================

    private void cleanOldLogs() {
        long now = System.currentTimeMillis();
        cleanDir(new File(logDir, "chat"), chatRetention, now);
        cleanDir(new File(logDir, "error"), errorRetention, now);
        cleanDir(new File(logDir, "stats"), statsRetention, now);
        cleanDir(new File(logDir, "audit"), auditRetention, now);
    }

    private void cleanDir(File dir, int retentionDays, long now) {
        File[] files = dir.listFiles((d, name) -> name.contains("."));
        if (files == null) return;
        long cutoff = now - retentionDays * 86400000L;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    // ==================== 工具 ====================

    private String formatCsvLine(Map<String, String> fields) {
        return String.format("%s,%s,%s,%s,%s,%s,%s,\"%s\",\"%s\",%s,%s,%s,%s,%s,%s,%s,%s",
                fields.getOrDefault("timestamp", "?"),
                fields.getOrDefault("player", "?"),
                fields.getOrDefault("uuid", "?"),
                fields.getOrDefault("world", "?"),
                fields.getOrDefault("dimension", "?"),
                fields.getOrDefault("loc", "?"),
                fields.getOrDefault("trigger", "?"),
                safeCsv(fields.get("user_msg")),
                safeCsv(fields.get("ai_response")),
                fields.getOrDefault("tokens_in", "0"),
                fields.getOrDefault("tokens_out", "0"),
                fields.getOrDefault("model", "?"),
                fields.getOrDefault("cost_est", "0"),
                fields.getOrDefault("latency", "0"),
                fields.getOrDefault("actions", "0"),
                fields.getOrDefault("lang", "?"),
                fields.getOrDefault("success", "true")
        );
    }

    private String safeCsv(String s) {
        return s == null ? "" : s.replace("\"", "\"\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** 标准 CSV 行解析：处理双引号包裹字段 */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++; // 跳过转义引号
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    // ==================== 审计快捷方法 ====================

    public void auditReload(String player) {
        logAudit(player, "reload", "重新加载配置");
    }

    public void auditClearAll(String player) {
        logAudit(player, "clearall", "清除所有对话历史");
    }

    public void auditMute(String player, boolean muted) {
        logAudit(player, muted ? "mute" : "unmute", "切换静音状态");
    }
}
