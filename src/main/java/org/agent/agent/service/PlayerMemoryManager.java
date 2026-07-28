package org.agent.agent.service;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 玩家记忆管理 —— 结构化存储，支持分类/置信度/衰减
 * 存储位置：plugins/Agent/memories/<uuid>.yml
 */
public class PlayerMemoryManager {

    private final JavaPlugin plugin;
    private final File memoryDir;
    /** per-player 锁，防止 addMemory 与 decayAll 读写竞态 */
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();

    public PlayerMemoryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.memoryDir = new File(plugin.getDataFolder(), "memories");
        if (!memoryDir.exists()) memoryDir.mkdirs();
    }

    private File getPlayerFile(UUID playerId) {
        return new File(memoryDir, playerId.toString() + ".yml");
    }

    // ==================== 读写 ====================

    /** 获取所有记忆（已排除过期 + 已归档） */
    public List<MemoryEntry> getMemories(UUID playerId) {
        return getAllEntries(playerId).stream()
                .filter(e -> !e.isExpired())
                .collect(Collectors.toList());
    }

    /** 获取所有记忆包括归档 */
    public List<MemoryEntry> getAllEntries(UUID playerId) {
        File file = getPlayerFile(playerId);
        if (!file.exists()) return new ArrayList<>();
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        // 兼容旧格式（纯字符串列表）
        if (config.contains("memories") && config.getList("memories") != null) {
            List<?> raw = config.getList("memories");
            if (!raw.isEmpty() && raw.get(0) instanceof String) {
                return migrateOldFormat(playerId, config);
            }
            // 新格式：Map list
            List<MemoryEntry> entries = new ArrayList<>();
            for (Object obj : raw) {
                if (obj instanceof Map<?, ?> map) {
                    entries.add(MemoryEntry.deserialize((Map<String, Object>) map));
                }
            }
            return entries;
        }
        return new ArrayList<>();
    }

    /** 迁移旧格式到新格式 */
    private List<MemoryEntry> migrateOldFormat(UUID playerId, FileConfiguration config) {
        List<String> oldList = config.getStringList("memories");
        List<MemoryEntry> entries = new ArrayList<>();
        for (String s : oldList) {
            entries.add(new MemoryEntry(MemoryEntry.Type.FACT, s, 3));
        }
        saveAll(playerId, entries);
        plugin.getLogger().info("已迁移玩家 " + playerId + " 的 " + entries.size() + " 条旧记忆到新格式");
        return entries;
    }

    private void saveAll(UUID playerId, List<MemoryEntry> entries) {
        File file = getPlayerFile(playerId);
        FileConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> serialized = entries.stream()
                .map(MemoryEntry::serialize).collect(Collectors.toList());
        config.set("memories", serialized);
        config.set("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date()));
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "无法保存记忆文件: " + file.getName(), e);
        }
    }

    // ==================== 增删改 ====================

    /** 添加记忆（自动去重：内容相似时更新而非追加），返回条目自身以便链式调用 */
    public MemoryEntry addMemory(UUID playerId, String content) {
        return addMemory(playerId, MemoryEntry.Type.FACT, content, 3);
    }

    public MemoryEntry addMemory(UUID playerId, MemoryEntry.Type type, String content, int confidence) {
        synchronized (playerLocks.computeIfAbsent(playerId, k -> new Object())) {
            return addMemoryLocked(playerId, type, content, confidence);
        }
    }

    private MemoryEntry addMemoryLocked(UUID playerId, MemoryEntry.Type type, String content, int confidence) {
        List<MemoryEntry> entries = getAllEntries(playerId);
        String norm = normalize(content);

        // 去重：检查是否有相似内容
        for (MemoryEntry entry : entries) {
            if (entry.isExpired()) continue;
            if (normalize(entry.getContent()).equals(norm) || isSimilar(entry.getContent(), content)) {
                entry.boost(); // 提升置信度
                saveAll(playerId, entries);
                return entry;
            }
        }

        // 新增
        MemoryEntry newEntry = new MemoryEntry(type, content, confidence);
        entries.add(newEntry);

        // 超限清理：保留最多 40 条，移除最旧的低置信度
        List<MemoryEntry> active = entries.stream().filter(e -> !e.isExpired()).collect(Collectors.toList());
        if (active.size() > 40) {
            active.sort((a, b) -> {
                int cmp = Integer.compare(b.getConfidence(), a.getConfidence()); // 高置信度优先
                if (cmp != 0) return cmp;
                return Long.compare(b.getUpdatedAt(), a.getUpdatedAt()); // 新的优先
            });
            entries = active.subList(0, 40);
        }

        saveAll(playerId, entries);
        return newEntry;
    }

    /** 删除指定索引的记忆（1-based） */
    public String removeMemory(UUID playerId, int index) {
        List<MemoryEntry> entries = getAllEntries(playerId);
        if (index < 1 || index > entries.size()) return null;
        MemoryEntry removed = entries.remove(index - 1);
        saveAll(playerId, entries);
        return removed.getContent();
    }

    public void clearMemories(UUID playerId) {
        File file = getPlayerFile(playerId);
        if (file.exists()) file.delete();
    }

    // ==================== 衰减 ====================

    /** 对所有记忆执行一次衰减（可定时调用） */
    public void decayAll() {
        File[] files = memoryDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            String uuidStr = f.getName().replace(".yml", "");
            try {
                UUID uuid = UUID.fromString(uuidStr);
                synchronized (playerLocks.computeIfAbsent(uuid, k -> new Object())) {
                    List<MemoryEntry> entries = getAllEntries(uuid);
                    boolean changed = false;
                    Iterator<MemoryEntry> it = entries.iterator();
                    while (it.hasNext()) {
                        MemoryEntry e = it.next();
                        if (e.isExpired()) {
                            it.remove();
                            changed = true;
                        } else if (e.shouldSkipInjection()) {
                            e.decay();
                            changed = true;
                        }
                    }
                    if (changed) saveAll(uuid, entries);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    // ==================== 格式化输出 ====================

    /** 按分类格式化玩家画像 */
    public String formatProfile(UUID playerId) {
        List<MemoryEntry> entries = getMemories(playerId);
        if (entries.isEmpty()) return "暂无记忆";

        Map<MemoryEntry.Type, List<MemoryEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType));

        StringBuilder sb = new StringBuilder();
        for (MemoryEntry.Type type : MemoryEntry.Type.values()) {
            List<MemoryEntry> list = grouped.get(type);
            if (list == null || list.isEmpty()) continue;
            sb.append("§b=== ").append(type.getLabel()).append(" ===§r\n");
            for (MemoryEntry e : list) {
                sb.append("  §7").append(e.format()).append("§r\n");
            }
        }
        return sb.toString();
    }

    /** 注入到 AI 提示词 */
    public String formatMemoriesForPrompt(UUID playerId, String dimension, String biome) {
        List<MemoryEntry> entries = getMemories(playerId);
        if (entries.isEmpty()) return "";

        // 按场景过滤 + 场景标签匹配
        List<MemoryEntry> scored = new ArrayList<>();
        for (MemoryEntry e : entries) {
            if (e.shouldSkipInjection()) continue;
            int score = e.getConfidence();
            // 场景标签匹配加分
            if (dimension != null && e.matchesScene(dimension.toLowerCase())) score += 2;
            if (biome != null && e.matchesScene(biome.toLowerCase())) score += 1;
            scored.add(e);
        }

        // 按分数排序，取前 10 条
        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.getConfidence(), a.getConfidence());
            if (cmp != 0) return cmp;
            return Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
        });
        int limit = Math.min(scored.size(), 10);
        scored = scored.subList(0, limit);

        StringBuilder sb = new StringBuilder("\n【玩家画像】（按相关性排序）\n");
        for (MemoryEntry e : scored) {
            sb.append("- ").append(e.formatShort()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 辅助 ====================

    private String normalize(String s) {
        return s.replaceAll("[\\s,，。、！？；：\"'「」]", "").toLowerCase().trim();
    }

    private boolean isSimilar(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.equals(nb)) return true;
        // 编辑距离 ≤ 3 视为相似
        if (Math.abs(na.length() - nb.length()) > 3) return false;
        return levenshtein(na, nb) <= 3;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    public List<UUID> listPlayersWithMemories() {
        File[] files = memoryDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return List.of();
        List<UUID> result = new ArrayList<>();
        for (File f : files) {
            try {
                result.add(UUID.fromString(f.getName().replace(".yml", "")));
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }
}
