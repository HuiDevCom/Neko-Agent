package org.agent.agent.listener;

import org.agent.agent.service.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 监听玩家死亡事件 —— 让小绘傲娇吐槽 + 给出攻略
 *
 * 所有 Bukkit API 在主线程收集数据，再异步调 AI。
 */
public class DeathListener implements Listener {

    private final JavaPlugin plugin;
    private final OpenAIService openAIService;
    private final ConversationManager conversationManager;
    private final ActionExecutor actionExecutor;
    private final PlayerMemoryManager memoryManager;
    private final String agentName;

    public DeathListener(JavaPlugin plugin, OpenAIService openAIService,
                         ConversationManager conversationManager,
                         ActionExecutor actionExecutor,
                         PlayerMemoryManager memoryManager) {
        this.plugin = plugin;
        this.openAIService = openAIService;
        this.conversationManager = conversationManager;
        this.actionExecutor = actionExecutor;
        this.memoryManager = memoryManager;
        this.agentName = plugin.getConfig().getString("agent.name", "Agent");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String deathMessage = event.getDeathMessage();
        if (deathMessage == null || deathMessage.isBlank()) {
            deathMessage = player.getName() + " 死了。";
        }

        // 不为创造模式玩家做吐槽
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        // 在主线程收集所有 Bukkit 数据
        final String fDeathMessage = deathMessage;
        final String playerName = player.getName();
        final Location lastDeathLoc = player.getLastDeathLocation();
        final double health = player.getHealth();
        final String gameMode = player.getGameMode().name();
        final String dimension = player.getWorld().getEnvironment().name();
        final List<MemoryEntry> memories = memoryManager.getMemories(player.getUniqueId());

        // 异步生成吐槽
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> generateComment(player, fDeathMessage, playerName,
                        lastDeathLoc, health, gameMode, dimension, memories)
        );
    }

    private void generateComment(Player player, String deathMessage,
                                  String playerName, Location lastDeathLoc,
                                  double health, String gameMode, String dimension,
                                  List<MemoryEntry> memories) {
        try {
            String deathLocStr = lastDeathLoc != null
                    ? String.format("%d, %d, %d",
                            lastDeathLoc.getBlockX(), lastDeathLoc.getBlockY(), lastDeathLoc.getBlockZ())
                    : "未知";

            String memoryHint = memories.isEmpty()
                    ? "（无）"
                    : memories.stream().map(MemoryEntry::formatShort).collect(java.util.stream.Collectors.joining("；"));

            List<OpenAIService.ChatMessage> messages = List.of(
                    OpenAIService.ChatMessage.system(
                            "你是" + plugin.getConfig().getString("server_name", "Minecraft Server") +
                            "服务器的傲娇猫娘「" + plugin.getConfig().getString("agent.name", "Agent") + "」。\n"
                                    + "现在有玩家死了，请用傲娇的方式吐槽一下，并给出一条实用的生存建议。\n"
                                    + "要求：\n"
                                    + "- 中文，1-2 句短话，用换行分隔，不要写成一整段\n"
                                    + "- 结尾偶尔带「喵」\n"
                                    + "- 不要重复显示死亡消息本身\n"
                                    + "- 傲娇口吻，不是真的恶意\n"
                                    + "- 实际有用的建议（例如：在岩浆上方要小心末影龙、虚空摔死的别跳、岩浆上方铺水等）\n"
                                    + "- 如果下面有该玩家的记忆信息，吐槽时可以适当结合，让回复更个性化"
                    ),
                    OpenAIService.ChatMessage.user(
                            "玩家 " + playerName + " 刚刚死了。\n"
                                    + "死因：" + deathMessage + "\n"
                                    + "地点：" + deathLocStr + "\n"
                                    + "生命值：" + String.format("%.0f", health) + "\n"
                                    + "游戏模式：" + gameMode + "\n"
                                    + "维度：" + dimension + "\n"
                                    + "记忆：" + memoryHint + "\n\n"
                                    + "请吐槽+给建议。"
                    )
            );

            openAIService.chat(messages).thenAccept(response -> {
                // 切回主线程广播 + 执行操作标签
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String processed = actionExecutor.processActions(player, response);
                    String[] lines = processed.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        final String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        final long delay = i * 10L;
                        if (delay == 0) {
                            Bukkit.broadcastMessage("§b[" + agentName + "] §r" + line);
                        } else {
                            Bukkit.getScheduler().runTaskLater(plugin,
                                    () -> Bukkit.broadcastMessage("§b[" + agentName + "] §r" + line), delay);
                        }
                    }
                });
            });
        } catch (Exception e) {
            plugin.getLogger().warning("死亡吐槽生成失败: " + e.getMessage());
        }
    }
}
