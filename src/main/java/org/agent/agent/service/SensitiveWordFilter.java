package org.agent.agent.service;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词过滤 —— 玩家消息命中时直接拦截，不调 AI（省钱+安全）
 */
public class SensitiveWordFilter {

    private final JavaPlugin plugin;
    private volatile List<String> sensitiveWords = new ArrayList<>();

    public SensitiveWordFilter(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        plugin.reloadConfig();
        sensitiveWords = plugin.getConfig().getStringList("filter.sensitive_words");
    }

    /**
     * 检查消息是否包含敏感词
     * @return 匹配到的敏感词，无则返回 null
     */
    public String check(String message) {
        String lower = message.toLowerCase();
        for (String word : sensitiveWords) {
            if (word.isEmpty()) continue;
            if (lower.contains(word.toLowerCase())) {
                return word;
            }
        }
        return null;
    }

    public boolean containsSensitive(String message) {
        return check(message) != null;
    }
}
