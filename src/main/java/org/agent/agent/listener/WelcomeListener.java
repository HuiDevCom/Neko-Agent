package org.agent.agent.listener;

import org.agent.agent.service.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家进服欢迎监听器 —— 小绘会主动打招呼
 *
 * 设计：
 * - 异步生成欢迎语（不阻塞 PlayerJoinEvent 主线程）
 * - 风格：傲娇猫娘，可能顺带吐槽上线时间/状态
 * - 老玩家：基于记忆定制（记得玩家昵称、爱好、近期成就等）
 * - 新玩家：通用欢迎
 * - 首个进服欢迎一次性缓存，避免重复请求
 */
public class WelcomeListener implements Listener {

    private final JavaPlugin plugin;
    private final OpenAIService openAIService;
    private final PlayerMemoryManager memoryManager;
    private final PlayerQueryService queryService;
    private final ServerStatusService serverStatusService;
    private final ActionExecutor actionExecutor;
    private final LocaleManager localeManager;

    // 防止配置重载时重复触发
    private final Set<UUID> recentlyWelcomed = new HashSet<>();

    public WelcomeListener(JavaPlugin plugin, OpenAIService openAIService,
                           PlayerMemoryManager memoryManager, PlayerQueryService queryService,
                           ServerStatusService serverStatusService, ActionExecutor actionExecutor,
                           LocaleManager localeManager) {
        this.plugin = plugin;
        this.openAIService = openAIService;
        this.memoryManager = memoryManager;
        this.queryService = queryService;
        this.serverStatusService = serverStatusService;
        this.actionExecutor = actionExecutor;
        this.localeManager = localeManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();

        // 防止短时间内因为配置 reload 重复触发
        if (!recentlyWelcomed.add(playerId)) {
            return;
        }
        // 5 秒后清理标记
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> recentlyWelcomed.remove(playerId), 5 * 20L);

        // 延期 1 秒后在主线程收集数据，再异步生成欢迎语
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            String playerName = player.getName();
            String serverStatus = serverStatusService.getServerStatus();
            String sceneInfo = queryService.getEnvironmentInfo(player);
            List<MemoryEntry> memories = memoryManager.getMemories(playerId);
            generateWelcome(playerId, playerName, serverStatus, sceneInfo, memories);
        }, 20L);
    }

    /**
     * 生成欢迎语
     */
    private void generateWelcome(UUID playerId, String playerName, String serverStatus,
                                  String sceneInfo, List<MemoryEntry> memories) {
        // 异步调 AI（不阻塞主线程）
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String memoryHint = memories.isEmpty()
                        ? "（这位玩家没有历史记忆）"
                        : memories.stream().map(MemoryEntry::formatShort).collect(java.util.stream.Collectors.joining("；"));

                String timeHint = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
                String joinDesc = memories.isEmpty() ? "首次" : "再次";

                String serverName = plugin.getConfig().getString("server_name", "Minecraft Server");
                String agentName = plugin.getConfig().getString("agent.name", "Neko-Agent");

                String systemPrompt = ("你是" + serverName + "服务器的傲娇猫娘「" + agentName + "」。" +
                        "现在玩家 %s 进服了，当前现实时间 %s，你要主动打一句招呼给他/全服。" +
                        "要求：" +
                        "- 中文，1-2 句短话，用换行分隔，不要写成一整段" +
                        "- 傲娇口吻（不是真的讨厌，是「才没有特意等你上线」这种）" +
                        "- 「必须」结合下面提供的记忆信息来打招呼，让欢迎语个性化" +
                        "- 没有记忆时整体不超过 30 个字，有记忆时可适当多说" +
                        "- 根据当前时间（%s）适当调整语气"
                        ).formatted(playerName, timeHint, timeHint);

                String userPrompt = String.format(
                        "玩家：%s（%s上线）\n当前时间：%s\n记忆：%s\n\n当前服务器状态：\n%s\n\n当前场景：%s\n生成欢迎语。",
                        playerName, joinDesc, timeHint, memoryHint, serverStatus, sceneInfo
                );

                List<OpenAIService.ChatMessage> messages = List.of(
                        OpenAIService.ChatMessage.system(systemPrompt),
                        OpenAIService.ChatMessage.user(userPrompt)
                );

                openAIService.chat(messages).thenAccept(response -> {
                    org.bukkit.entity.Player onlinePlayer = Bukkit.getPlayer(playerId);
                    LocaleManager.Lang lang = onlinePlayer != null
                            ? localeManager.resolveLang(onlinePlayer)
                            : LocaleManager.Lang.ZH;
                    String prefix = localeManager.get(lang, "prefix");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        String processed = actionExecutor.processActions(null, response);
                        String[] lines = processed.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            final String line = lines[i].trim();
                            if (line.isEmpty()) continue;
                            final long delay = i * 10L;
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
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String prefix = localeManager.get(LocaleManager.Lang.ZH, "prefix");
                    String msg = localeManager.format(LocaleManager.Lang.ZH, "fallback_join", playerName);
                    Bukkit.broadcastMessage(prefix + " " + msg);
                });
            }
        });
    }
}
