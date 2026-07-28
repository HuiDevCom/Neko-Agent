package org.agent.agent.listener;

import org.agent.agent.service.ActionExecutor;
import org.agent.agent.service.LocaleManager;
import org.agent.agent.service.OpenAIService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空闲提醒 —— 服务器一段时间无人聊天时，小绘主动冒泡
 */
public class IdleReminderListener implements Listener {

    private final JavaPlugin plugin;
    private final OpenAIService openAIService;
    private final ActionExecutor actionExecutor;
    private final LocaleManager localeManager;

    private static final long IDLE_TICKS = 6000L; // 5 分钟聊天空闲
    private static final long AFK_TICKS = 6000L;   // 5 分钟玩家不动也算挂机
    private int taskId = -1;
    private volatile long lastChatTime = System.currentTimeMillis();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();

    public IdleReminderListener(JavaPlugin plugin, OpenAIService openAIService,
                                ActionExecutor actionExecutor, LocaleManager localeManager) {
        this.plugin = plugin;
        this.openAIService = openAIService;
        this.actionExecutor = actionExecutor;
        this.localeManager = localeManager;
    }

    public void start() {
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            if (now - lastChatTime < IDLE_TICKS * 50L) return; // 没到空闲阈值

            // 找在线玩家触发
            Player target = null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                target = p;
                break;
            }
            if (target == null) return;

            final Player fTarget = target;
            String agentName = plugin.getConfig().getString("agent.name", "Neko-Agent");
            String serverName = plugin.getConfig().getString("server_name", "Minecraft Server");
            List<OpenAIService.ChatMessage> messages = List.of(
                    OpenAIService.ChatMessage.system(
                            "你是" + serverName + "服务器的傲娇猫娘「" + agentName + "」。"
                                    + "服务器已经安静好一会儿了，请主动向玩家打个招呼或找话题。"
                                    + "要求：中文，1-2 句短话，用换行分隔。语气自然，像是刚注意到没人说话。"
                    ),
                    OpenAIService.ChatMessage.user("服务器已经安静一段时间了，请主动冒泡。")
            );

            openAIService.chat(messages).thenAccept(response -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String processed = actionExecutor.processActions(fTarget, response);
                    String prefix = localeManager.get(localeManager.resolveLang(fTarget), "prefix");
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

            lastChatTime = now; // 重置计时
        }, IDLE_TICKS, IDLE_TICKS).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    // 玩家聊天时刷新计时
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent event) {
        lastChatTime = System.currentTimeMillis();
        lastLocations.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation());
    }

    // 追踪玩家移动（挂机检测）
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return; // 仅旋转不算移动
        }
        lastLocations.put(event.getPlayer().getUniqueId(), event.getTo());
    }

    // 玩家进出也刷新
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        lastChatTime = System.currentTimeMillis();
        lastLocations.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastLocations.remove(event.getPlayer().getUniqueId());
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            lastChatTime = System.currentTimeMillis();
        }
    }
}
