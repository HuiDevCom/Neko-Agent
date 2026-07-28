package org.agent.agent.listener;

import org.agent.agent.service.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 玩家成就监听器 —— 小绘发来贺电
 */
public class AchievementListener implements Listener {

    private final JavaPlugin plugin;
    private final OpenAIService openAIService;
    private final PlayerMemoryManager memoryManager;
    private final ActionExecutor actionExecutor;
    private final LocaleManager localeManager;

    public AchievementListener(JavaPlugin plugin, OpenAIService openAIService,
                               PlayerMemoryManager memoryManager, ActionExecutor actionExecutor,
                               LocaleManager localeManager) {
        this.plugin = plugin;
        this.openAIService = openAIService;
        this.memoryManager = memoryManager;
        this.actionExecutor = actionExecutor;
        this.localeManager = localeManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        var advancement = event.getAdvancement();
        var key = advancement.getKey();

        // 跳过 recipe 类成就（太频繁）
        if (key.getKey().startsWith("recipes/")) return;

        Player player = event.getPlayer();
        // 用成就 Key 作为标题
        final String achievementTitle = formatAchievementKey(key.getKey());

        List<MemoryEntry> memories = memoryManager.getMemories(player.getUniqueId());

        // 异步调 AI 生成贺电
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String memoryHint = memories.isEmpty() ? "（无）" : memories.stream().map(MemoryEntry::formatShort).collect(java.util.stream.Collectors.joining("；"));
                String lang = localeManager.resolveLang(player).name();

                List<OpenAIService.ChatMessage> messages = List.of(
                        OpenAIService.ChatMessage.system(
                                "你是风绘物语服务器的傲娇猫娘「小绘」。"
                                        + "玩家刚刚完成了一个成就，请用傲娇又开心的语气祝贺他。"
                                        + "要求：中文，1-2 句短话，用换行分隔，不要写成一整段。"
                                        + "如果记忆里有内容可适当结合。"
                        ),
                        OpenAIService.ChatMessage.user(
                                "玩家：" + player.getName()
                                        + "\n成就：" + achievementTitle
                                        + "\n记忆：" + memoryHint
                                        + "\n请祝贺！"
                        )
                );

                openAIService.chat(messages).thenAccept(response -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        String processed = actionExecutor.processActions(player, response);
                        String prefix = localeManager.get(localeManager.resolveLang(player), "prefix");
                        String[] lines = processed.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            final String line = lines[i].trim();
                            if (line.isEmpty()) continue;
                            long delay = i * 10L;
                            if (delay == 0) {
                                Bukkit.broadcastMessage(prefix + " " + line);
                            } else {
                                Bukkit.getScheduler().runTaskLater(plugin,
                                        () -> Bukkit.broadcastMessage(prefix + " " + line), delay);
                            }
                        }
                    });
                });
            } catch (Exception e) {
                plugin.getLogger().warning("成就祝贺生成失败: " + e.getMessage());
            }
        });
    }

    /** 将成就 key 转成可读标题 */
    private String formatAchievementKey(String key) {
        if (key.contains("/")) {
            key = key.substring(key.lastIndexOf("/") + 1);
        }
        key = key.replace("_", " ").replace("-", " ");
        if (!key.isEmpty()) {
            key = Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
        return key;
    }
}
