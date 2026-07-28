package org.agent.agent.listener;

import org.agent.agent.service.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天监听器 —— 消息中包含触发词即触发对话
 */
public class ChatListener implements Listener {

    private final JavaPlugin plugin;
    private final OpenAIService openAIService;
    private final ConversationManager conversationManager;
    private final ActionExecutor actionExecutor;
    private final LocaleManager localeManager;
    private final SensitiveWordFilter sensitiveFilter;
    private final PlayerMuteManager muteManager;
    private final RateLimiter rateLimiter;
    private final LogManager logManager;
    private ServerDiaryService diaryService; // set after construct

    private final String agentName;

    // 触发模式：contains / prefix / exact
    private String triggerMode;
    private String triggerPrefix;
    // 玩家最后触发时间（冷却用）
    private final Map<UUID, Long> lastTriggerTime = new ConcurrentHashMap<>();
    // 连续失败计数
    private final Map<UUID, Integer> failCount = new ConcurrentHashMap<>();

    public ChatListener(JavaPlugin plugin, OpenAIService openAIService,
                        ConversationManager conversationManager, ActionExecutor actionExecutor,
                        LocaleManager localeManager,
                        SensitiveWordFilter sensitiveFilter, PlayerMuteManager muteManager,
                        RateLimiter rateLimiter, LogManager logManager) {
        this.plugin = plugin;
        this.openAIService = openAIService;
        this.conversationManager = conversationManager;
        this.actionExecutor = actionExecutor;
        this.localeManager = localeManager;
        this.sensitiveFilter = sensitiveFilter;
        this.muteManager = muteManager;
        this.rateLimiter = rateLimiter;
        this.logManager = logManager;
        this.triggerMode = plugin.getConfig().getString("agent.trigger_mode", "contains");
        this.triggerPrefix = plugin.getConfig().getString("agent.trigger_prefix", "Neko-Agent");
        this.agentName = plugin.getConfig().getString("agent.name", "Neko-Agent");
    }

    /** 重新加载配置 */
    public void reloadConfig() {
        this.triggerMode = plugin.getConfig().getString("agent.trigger_mode", "contains");
        this.triggerPrefix = plugin.getConfig().getString("agent.trigger_prefix", "Neko-Agent");
    }

    public void setDiaryService(ServerDiaryService ds) { this.diaryService = ds; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // 敏感词拦截
        String hit = sensitiveFilter.check(message);
        if (hit != null) {
            plugin.getLogger().warning("敏感词拦截: " + player.getName() + " 说: " + message + " (命中: " + hit + ")");
            return;
        }

        // 触发模式检测
        boolean triggered = switch (triggerMode) {
            case "prefix" -> message.startsWith(triggerPrefix);
            case "exact" -> message.trim().equals(triggerPrefix);
            default -> message.contains(triggerPrefix); // contains
        };
        if (!triggered) return;

        UUID playerId = player.getUniqueId();

        // 静音玩家不触发
        if (muteManager.isMuted(playerId)) return;

        // 冷却检测
        Long lastTime = lastTriggerTime.get(playerId);
        if (lastTime != null && rateLimiter.isCooldown(playerId, lastTime)) {
            return; // 冷却期直接忽略
        }
        lastTriggerTime.put(playerId, System.currentTimeMillis());

        // 限流检测
        if (!rateLimiter.allow(playerId)) {
            player.sendMessage("§e" + agentName + "累了，休息一下喵～");
            return;
        }

        final String userMessage = message.trim();

        // 记录日志
        long startTime = System.currentTimeMillis();
        logManager.debug("触发对话: " + player.getName() + " → " + userMessage);

        if (diaryService != null) diaryService.recordAiCall();

        // 不取消事件！让玩家的消息正常显示
        final Player fPlayer = player;

        Bukkit.getScheduler().runTask(plugin, () -> {
            var messages = conversationManager.buildMessages(fPlayer.getUniqueId(), fPlayer, userMessage);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                openAIService.chat(messages).thenAccept(response -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            if (response == null || response.isEmpty()) {
                                // 失败回退
                                Integer fails = failCount.getOrDefault(playerId, 0);
                                failCount.put(playerId, fails + 1);
                                if (fails >= 2) {
                                    player.sendMessage("§e" + agentName + "网络不太好，等会儿再找我吧喵...");
                                } else {
                                    player.sendMessage("§e唔…我走神了，能再说一遍吗喵？");
                                }
                                return;
                            }
                            failCount.remove(playerId); // 成功后重置失败计数

                            // 假动作检测：AI 写了 [正在传送...] 之类但没有 [小绘:xxx] 标签
                            if (actionExecutor.hasFakeAction(response)) {
                                plugin.getLogger().warning("检测到假动作回复，重试: " +
                                        fPlayer.getName() + " → " + response.substring(0, Math.min(response.length(), 80)));
                                messages.add(actionExecutor.buildRetryFeedback(response));
                                // 只重试一次，不用重复检测（避免无限循环）
                                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                    openAIService.chat(messages).thenAccept(retryResponse -> {
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            processAndBroadcast(fPlayer, playerId, userMessage, retryResponse, startTime);
                                        });
                                    });
                                });
                                return;
                            }

                            processAndBroadcast(fPlayer, playerId, userMessage, response, startTime);

                        } catch (Throwable e) {
                            player.sendMessage("§c" + agentName + "回复时出了点问题喵...");
                            logManager.logError("ChatListener", "回复处理异常", e);
                        }
                    });
                });
            });
        });
    }

    /** 处理 AI 回复 + 广播 + 记日志（ChatListener 和 retry 共用） */
    private void processAndBroadcast(Player player, UUID playerId, String userMessage,
                                     String response, long startTime) {
        if (response == null || response.isEmpty()) {
            player.sendMessage("§e唔…" + agentName + "还是没反应过来喵，可能要再说一次试试…");
            return;
        }
        String processed = actionExecutor.processActions(player, response);
        conversationManager.addToHistory(playerId, userMessage, processed);
        String prefix = localeManager.get(localeManager.resolveLang(player), "prefix");
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

        long latency = System.currentTimeMillis() - startTime;
        Map<String, String> logFields = new java.util.LinkedHashMap<>();
        logFields.put("timestamp", new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()));
        logFields.put("player", player.getName());
        logFields.put("uuid", player.getUniqueId().toString());
        logFields.put("world", player.getWorld().getName());
        logFields.put("dimension", player.getWorld().getEnvironment().name());
        logFields.put("loc", String.format("%.0f,%.0f,%.0f", player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ()));
        logFields.put("trigger", "chat");
        logFields.put("user_msg", userMessage);
        logFields.put("ai_response", processed);
        logFields.put("tokens_in", String.valueOf(LogManager.estimateTokens(userMessage)));
        logFields.put("tokens_out", String.valueOf(LogManager.estimateTokens(processed)));
        logFields.put("model", plugin.getConfig().getString("openai.model", "?"));
        logFields.put("cost_est", "0");
        logFields.put("latency", String.valueOf(latency));
        logFields.put("actions", "0");
        logFields.put("lang", localeManager.resolveLang(player).name());
        logFields.put("success", "true");
        logManager.logChat(logFields);
    }
}
