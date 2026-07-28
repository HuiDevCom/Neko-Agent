package org.agent.agent.command;

import org.agent.agent.listener.ChatListener;
import org.agent.agent.service.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /agent 命令处理器 —— 多语言版
 *
 * 所有面向玩家的提示文本通过 LocaleManager 获取，按玩家客户端语言分发
 */
public class AgentCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final OpenAIService openAIService;
    private final ConversationManager conversationManager;
    private final PlayerMemoryManager playerMemoryManager;
    private final ActionExecutor actionExecutor;
    private final KimiSearchService kimiService;
    private final LocaleManager localeManager;
    private final LocationManager locationManager;
    private final PlayerMuteManager muteManager;
    private final LogManager logManager;
    private final RateLimiter rateLimiter;
    private final SensitiveWordFilter sensitiveFilter;
    private final ChatListener chatListener;
    private final ServerDiaryService diaryService;

    private final String agentName;

    // 命令对话的安全追踪（与 ChatListener 对齐）
    private final Map<UUID, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> failCount = new ConcurrentHashMap<>();

    public AgentCommand(JavaPlugin plugin, OpenAIService openAIService,
                        ConversationManager conversationManager, PlayerMemoryManager playerMemoryManager,
                        ActionExecutor actionExecutor, KimiSearchService kimiService,
                        LocaleManager localeManager, LocationManager locationManager,
                        PlayerMuteManager muteManager, LogManager logManager,
                        ChatListener chatListener, ServerDiaryService diaryService) {
        this.plugin = plugin;
        this.openAIService = openAIService;
        this.conversationManager = conversationManager;
        this.playerMemoryManager = playerMemoryManager;
        this.actionExecutor = actionExecutor;
        this.kimiService = kimiService;
        this.localeManager = localeManager;
        this.locationManager = locationManager;
        this.muteManager = muteManager;
        this.logManager = logManager;
        this.rateLimiter = ((org.agent.agent.Agent) plugin).getRateLimiter();
        this.sensitiveFilter = new SensitiveWordFilter(plugin);
        this.chatListener = chatListener;
        this.diaryService = diaryService;
        this.agentName = plugin.getConfig().getString("agent.name", "Neko-Agent");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        try {
            if (args.length == 0) {
                showHelp(sender);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "clear" -> handleClear(sender);
                case "reload" -> handleReload(sender);
                case "clearall" -> handleClearAll(sender);
                case "remember" -> handleRemember(sender, args);
                case "forget" -> handleForget(sender, args);
                case "memories" -> handleMemories(sender, args);
                case "loc" -> handleLoc(sender, args);
                case "stats" -> handleStats(sender);
                case "profile" -> handleProfile(sender, args);
                case "project" -> handleProject(sender, args);
                case "tell" -> handleTell(sender, args);
                case "mute" -> handleMute(sender);
                case "recipe" -> handleRecipe(sender, args);
                case "logs" -> handleLogs(sender, args);
                case "diary" -> handleDiary(sender, args);
                default -> handleChat(sender, args);
            }
        } catch (Throwable e) {
            sender.sendMessage("§c[" + agentName + "] 命令执行出错: " + e.getMessage());
        }

        return true;
    }

    private void showHelp(CommandSender sender) {
        LocaleManager.Lang lang = localeManager.resolveLang(sender);
        sender.sendMessage(localeManager.format(lang, "prefix") + " " + localeManager.format(lang, "help_title"));
        sender.sendMessage("  " + localeManager.format(lang, "help_chat"));
        sender.sendMessage("  " + localeManager.format(lang, "help_remember"));
        sender.sendMessage("  " + localeManager.format(lang, "help_forget"));
        sender.sendMessage("  " + localeManager.format(lang, "help_memories"));
        sender.sendMessage("  " + localeManager.format(lang, "help_clear"));
        sender.sendMessage("  " + localeManager.format(lang, "help_loc"));
        sender.sendMessage("  " + localeManager.format(lang, "help_stats"));
        sender.sendMessage("  " + localeManager.format(lang, "help_profile"));
        sender.sendMessage("  " + localeManager.format(lang, "help_project"));
        sender.sendMessage("  " + localeManager.format(lang, "help_tell"));
        sender.sendMessage("  " + localeManager.format(lang, "help_mute"));
        sender.sendMessage("  " + localeManager.format(lang, "help_recipe"));
        sender.sendMessage("  " + localeManager.format(lang, "help_logs"));
        sender.sendMessage("  " + localeManager.format(lang, "help_diary"));
        if (sender.hasPermission("agent.admin")) {
            sender.sendMessage("");
            sender.sendMessage("  " + localeManager.format(lang, "admin_help_memories"));
            sender.sendMessage("  " + localeManager.format(lang, "admin_help_reload"));
            sender.sendMessage("  " + localeManager.format(lang, "admin_help_clearall"));
            sender.sendMessage("  " + localeManager.format(lang, "admin_help_logs"));
            sender.sendMessage("  " + localeManager.format(lang, "admin_help_diary"));
        }
    }

    // ==================== 对话 ====================

    private void handleChat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.get(LocaleManager.Lang.EN, "player_only"));
            return;
        }

        String userMessage = String.join(" ", args);
        UUID playerId = player.getUniqueId();

        // 1. 敏感词拦截
        String hit = sensitiveFilter.check(userMessage);
        if (hit != null) {
            plugin.getLogger().warning("敏感词拦截(/agent): " + player.getName() + " → " + userMessage + " (命中: " + hit + ")");
            return;
        }

        // 2. 静音检查
        if (muteManager.isMuted(playerId)) return;

        // 3. 冷却检测
        Long lastTime = lastTriggerTime.get(playerId);
        if (lastTime != null && rateLimiter.isCooldown(playerId, lastTime)) return;
        lastTriggerTime.put(playerId, System.currentTimeMillis());

        // 4. 限流检测
        if (!rateLimiter.allow(playerId)) {
            player.sendMessage("§e" + agentName + "累了，休息一下喵～");
            return;
        }

        sender.sendMessage(localeManager.format(player, "send_to", userMessage));

        var messages = conversationManager.buildMessages(player.getUniqueId(), player, userMessage);

        openAIService.chat(messages).thenAccept(response -> {
            // processActions（含 dispatchCommand）必须切主线程
            String displayName = localeManager.get(localeManager.resolveLang(player), "prefix");
            final String fResponse = response;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (fResponse == null || fResponse.isEmpty()) {
                        Integer fails = failCount.getOrDefault(playerId, 0);
                        failCount.put(playerId, fails + 1);
                        if (fails >= 2) {
                            player.sendMessage("§e" + agentName + "网络不太好，等会儿再找我吧喵...");
                        } else {
                            player.sendMessage("§e唔…我走神了，能再说一遍吗喵？");
                        }
                        return;
                    }
                    failCount.remove(playerId);

                    // 假动作检测 + 自动重试（只重试一次）
                    if (actionExecutor.hasFakeAction(fResponse)) {
                        plugin.getLogger().warning("检测到假动作回复(/agent)，重试: " +
                                player.getName() + " → " + fResponse.substring(0, Math.min(fResponse.length(), 80)));
                        messages.add(actionExecutor.buildRetryFeedback(fResponse));
                        openAIService.chat(messages).thenAccept(retryResponse -> {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                cmdProcessAndBroadcast(player, playerId, userMessage, retryResponse);
                            });
                        });
                        return;
                    }

                    cmdProcessAndBroadcast(player, playerId, userMessage, fResponse);
                } catch (Throwable e) {
                    player.sendMessage("§c" + agentName + "回复时出了点问题喵...");
                    logManager.logError("AgentCommand", "命令回复处理异常", e);
                }
            });
        });
    }

    /** 广播 AI 回复（AgentCommand 和 retry 共用） */
    private void cmdProcessAndBroadcast(Player player, UUID playerId, String userMessage, String response) {
        if (response == null || response.isEmpty()) {
            player.sendMessage("§e唔…" + agentName + "还是没反应过来喵，可能要再说一次试试…");
            return;
        }
        String displayName = localeManager.get(localeManager.resolveLang(player), "prefix");
        String processed = actionExecutor.processActions(player, response);
        conversationManager.addToHistory(playerId, userMessage, processed);
        String[] lines = processed.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (line.isEmpty()) continue;
            final long delay = i * 10L;
            if (delay == 0) {
                Bukkit.broadcastMessage(displayName + " " + line);
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> Bukkit.broadcastMessage(displayName + " " + line), delay);
            }
        }
    }

    private void handleClear(CommandSender sender) {
        if (sender instanceof Player player) {
            conversationManager.clearHistory(player.getUniqueId());
            sender.sendMessage(localeManager.format(player, "prefix")
                    + " " + localeManager.format(player, "clear_done"));
        } else {
            sender.sendMessage(localeManager.get(LocaleManager.Lang.EN, "player_only"));
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("agent.admin")) {
            sender.sendMessage(localeManager.format(sender, "no_permission"));
            return;
        }
        plugin.reloadConfig();
        openAIService.loadConfig();
        kimiService.loadConfig();
        rateLimiter.loadConfig();
        sensitiveFilter.loadConfig();
        logManager.reloadConfig();
        conversationManager.loadConfig();
        chatListener.reloadConfig();
        sender.sendMessage(localeManager.format(sender, "reload_done"));
    }

    private void handleClearAll(CommandSender sender) {
        if (!sender.hasPermission("agent.admin")) {
            sender.sendMessage(localeManager.format(sender, "no_permission"));
            return;
        }
        conversationManager.clearAll();
        sender.sendMessage(localeManager.format(sender, "clearall_done"));
    }

    // ==================== 记忆管理 ====================

    private void handleRemember(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.get(LocaleManager.Lang.EN, "player_only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法：/agent remember <内容>");
            return;
        }
        String memory = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        playerMemoryManager.addMemory(player.getUniqueId(), memory);
        sender.sendMessage(localeManager.format(player, "remember_done", memory));
    }

    private void handleForget(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.get(LocaleManager.Lang.EN, "player_only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法：/agent forget <编号>");
            return;
        }
        try {
            int index = Integer.parseInt(args[1]);
            String removed = playerMemoryManager.removeMemory(player.getUniqueId(), index);
            if (removed == null) {
                sender.sendMessage(localeManager.format(player, "forget_invalid"));
            } else {
                sender.sendMessage(localeManager.format(player, "forget_done", removed));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(localeManager.format(player, "forget_not_number"));
        }
    }

    private void handleMemories(CommandSender sender, String[] args) {
        UUID targetId;
        LocaleManager.Lang lang = localeManager.resolveLang(sender);

        if (args.length >= 2 && sender.hasPermission("agent.admin")) {
            String playerName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage(localeManager.format(lang, "player_not_found", playerName));
                return;
            }
            targetId = target.getUniqueId();
        } else if (sender instanceof Player player) {
            targetId = player.getUniqueId();
        } else {
            sender.sendMessage("§c用法：/agent memories <玩家名>");
            return;
        }

        List<org.agent.agent.service.MemoryEntry> list = playerMemoryManager.getMemories(targetId);
        if (list.isEmpty()) {
            sender.sendMessage(localeManager.format(lang, "prefix") + " "
                    + localeManager.format(lang, "memories_empty"));
            sender.sendMessage(localeManager.format(lang, "memories_empty_hint"));
        } else {
            sender.sendMessage(localeManager.format(lang, "prefix") + " "
                    + localeManager.format(lang, "memories_title"));
            for (int i = 0; i < list.size(); i++) {
                sender.sendMessage("§e" + (i + 1) + ".§r " + list.get(i).format());
            }
        }
    }

    /**
     * 给控制台/未指定语言的 sender 选用语言
     */
    private LocaleManager.Lang resolveLang(CommandSender sender) {
        if (sender instanceof Player p) return localeManager.resolveLang(p);
        return LocaleManager.Lang.EN;
    }

    // ==================== 坐标管理 ====================

    private void handleLoc(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用坐标功能。");
            return;
        }
        UUID id = player.getUniqueId();

        if (args.length < 2) {
            sender.sendMessage("§e用法：/agent loc <名称>  — 保存当前位置");
            sender.sendMessage("§e      /agent loc list    — 列出坐标");
            sender.sendMessage("§e      /agent loc remove <名称> — 删除坐标");
            return;
        }

        String sub = args[1].toLowerCase();
        if ("list".equals(sub)) {
            var names = locationManager.getLocationNames(id);
            if (names.isEmpty()) {
                sender.sendMessage("§e你还没有保存过坐标。使用 /agent loc <名称> 保存当前位置。");
            } else {
                sender.sendMessage("§b[" + agentName + "] §r你保存的坐标：");
                for (String name : names) {
                    String info = locationManager.formatLocationInfo(id, name);
                    sender.sendMessage("  §e" + info);
                }
            }
        } else if ("remove".equals(sub) && args.length >= 3) {
            boolean ok = locationManager.removeLocation(id, args[2]);
            sender.sendMessage(ok ? "§a已删除坐标: " + args[2] : "§c未找到坐标: " + args[2]);
        } else {
            // 保存坐标
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            locationManager.saveLocation(id, name, player.getLocation());
            sender.sendMessage("§a已保存当前位置为: " + name);
        }
    }

    // ==================== 统计 ====================

    private void handleStats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能查看统计。");
            return;
        }
        UUID id = player.getUniqueId();
        int memCount = playerMemoryManager.getMemories(id).size();
        int locCount = locationManager.getLocationNames(id).size();
        sender.sendMessage("§b=== " + agentName + "与你 §b===");
        sender.sendMessage("  §7记忆条数: §f" + memCount);
        sender.sendMessage("  §7保存坐标: §f" + locCount);
    }

    // ==================== 玩家画像 ====================

    private void handleProfile(CommandSender sender, String[] args) {
        UUID targetId;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("set")) {
            // /agent profile <player>
            if (!sender.hasPermission("agent.admin")) {
                sender.sendMessage("§c你没有权限查看其他玩家的画像。");
                return;
            }
            var target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                sender.sendMessage("§c玩家 " + args[1] + " 不存在。");
                return;
            }
            targetId = target.getUniqueId();
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
            // /agent profile set <key> <value>
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c只有玩家才能设置自己的画像。");
                return;
            }
            String key = args[2];
            String value = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            if (value.isEmpty()) {
                sender.sendMessage("§e用法：/agent profile set <标签> <内容>");
                sender.sendMessage("§7例如：/agent profile set 称号 挖矿大师");
                return;
            }
            playerMemoryManager.addMemory(player.getUniqueId(), MemoryEntry.Type.FACT,
                    "自述：" + key + " → " + value, 4);
            sender.sendMessage("§a已记录：§f" + key + " = " + value);
            return;
        } else if (sender instanceof Player p) {
            targetId = p.getUniqueId();
        } else {
            sender.sendMessage("§c用法：/agent profile <玩家名>");
            return;
        }

        String profile = playerMemoryManager.formatProfile(targetId);
        sender.sendMessage("§b=== 玩家画像 ===§r");
        for (String line : profile.split("\n")) {
            sender.sendMessage(line);
        }
    }

    // ==================== 项目追踪 ====================

    private void handleProject(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能管理项目。");
            return;
        }
        UUID id = player.getUniqueId();
        if (args.length < 2) {
            // 列出项目
            var all = playerMemoryManager.getMemories(id);
            var projects = all.stream().filter(e -> e.getType() == MemoryEntry.Type.PROJECT).toList();
            if (projects.isEmpty()) {
                sender.sendMessage("§e你还没有正在进行的项目。");
                sender.sendMessage("§7用法：/agent project <项目名> — 创建新项目");
            } else {
                sender.sendMessage("§b=== 你的项目 ===§r");
                for (int i = 0; i < projects.size(); i++) {
                    var p = projects.get(i);
                    sender.sendMessage("§e" + (i + 1) + ".§r " + p.format());
                }
            }
            return;
        }
        String projectName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        playerMemoryManager.addMemory(id, MemoryEntry.Type.PROJECT, projectName, 3);
        sender.sendMessage("§a已记录项目：§f" + projectName);
        sender.sendMessage("§7" + agentName + "会记住你在忙这个，下次上线会问进展喵～");
    }

    // ==================== 日志查看 ====================

    private void handleLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("agent.admin")) {
            sender.sendMessage("§c你没有权限查看日志。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法：/agent logs <类型>");
            sender.sendMessage("  §7stats     — 今日统计摘要");
            sender.sendMessage("  §7cost      — 今日费用");
            sender.sendMessage("  §7chat      — 最近对话记录");
            sender.sendMessage("  §7errors    — 最近错误");
            sender.sendMessage("  §7player <名> — 指定玩家记录");
            sender.sendMessage("  §7search <词> — 搜索对话");
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "stats" -> {
                sender.sendMessage("§b=== 今日统计 ===§r");
                sender.sendMessage(logManager.getTodayStats());
            }
            case "cost" -> sender.sendMessage(logManager.getTodayCost());
            case "chat" -> {
                sender.sendMessage("§b=== 最近对话 ===§r");
                for (String line : logManager.readRecentChats(20)) {
                    sender.sendMessage(line);
                }
            }
            case "errors" -> {
                sender.sendMessage("§b=== 最近错误 ===§r");
                var errors = logManager.getRecentErrors(10);
                if (errors.isEmpty()) {
                    sender.sendMessage("§a暂无错误记录");
                } else {
                    for (String e : errors) sender.sendMessage("§c" + e);
                }
            }
            case "player" -> {
                if (args.length < 3) {
                    sender.sendMessage("§e用法：/agent logs player <玩家名>");
                    return;
                }
                sender.sendMessage("§b=== " + args[2] + " 的对话 ===§r");
                for (String line : logManager.getPlayerLogs(args[2], 10)) {
                    sender.sendMessage(line);
                }
            }
            case "search" -> {
                if (args.length < 3) {
                    sender.sendMessage("§e用法：/agent logs search <关键词>");
                    return;
                }
                String keyword = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                sender.sendMessage("§b=== 搜索: " + keyword + " ===§r");
                for (String line : logManager.searchChats(keyword, 10)) {
                    sender.sendMessage(line);
                }
            }
            default -> sender.sendMessage("§c未知日志类型: " + sub);
        }
    }

    // ==================== 小绘日记 ====================

    private void handleDiary(CommandSender sender, String[] args) {
        if (diaryService == null) {
            sender.sendMessage("§c日记功能未初始化");
            return;
        }

        if (args.length < 2) {
            // 列出最近日记
            sender.sendMessage("§b=== " + agentName + "日记 ===§r");
            var dates = diaryService.listDiaryDates();
            if (dates.isEmpty()) {
                sender.sendMessage("§e还没有日记，等今天过完" + agentName + "就会写了喵~");
            } else {
                dates.forEach(d -> sender.sendMessage("  §e/agent diary " + d));
                sender.sendMessage("§7使用 /agent diary <日期> 查看特定日记");
            }
            return;
        }

        if ("list".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§b=== 所有日记 ===§r");
            var dates = diaryService.listDiaryDates();
            dates.forEach(d -> sender.sendMessage("  §e" + d));
            return;
        }

        String date = args[1];
        String diary = diaryService.readDiary(date);
        // 按行发送以保留格式
        for (String line : diary.split("\n")) {
            sender.sendMessage("§f" + line);
        }
    }

    // ==================== 玩家传话 ====================

    private void handleTell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用传话功能。");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§e用法：/agent tell <玩家名> <消息>");
            return;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage("§c玩家 " + targetName + " 不在线。");
            return;
        }

        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        String senderName = player.getName();

        // 转述给目标玩家
        target.sendMessage("§7[传话] §b" + senderName + " §7托" + agentName + "带话给你：§f" + message);
        // 给发送者确认
        sender.sendMessage("§7" + agentName + "已经把话带给了 §b" + targetName + "§7。");
    }

    // ==================== 静音模式 ====================

    private void handleMute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能设置静音。");
            return;
        }
        boolean nowMuted = muteManager.toggle(player.getUniqueId());
        if (nowMuted) {
            sender.sendMessage("§e" + agentName + "已进入静音模式，主动提醒已关闭。");
            sender.sendMessage("§7（再次输入 /agent mute 可恢复）");
        } else {
            sender.sendMessage("§a" + agentName + "已恢复活跃，以后会主动找你聊天啦～");
        }
    }

    // ==================== 配方查询 ====================

    private void handleRecipe(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能查询配方。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法：/agent recipe <物品名>");
            return;
        }
        String itemName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        // 用 AI 回复配方
        sender.sendMessage("§7正在查询 " + itemName + " 的配方喵...");
        List<OpenAIService.ChatMessage> messages = List.of(
                OpenAIService.ChatMessage.system(
                        "你是 Minecraft 专家" + agentName + "。玩家想知道一个物品的合成配方。"
                                + "请直接告诉配方（材料+摆法），用简单清晰的方式。"
                                + "只回答 Minecraft 原版内容。"
                                + "要求：1-2 句话，用换行分隔。"
                ),
                OpenAIService.ChatMessage.user("告诉我 " + itemName + " 怎么合成")
        );
        openAIService.chat(messages).thenAccept(response -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                String prefix = localeManager.get(localeManager.resolveLang(player), "prefix");
                String[] lines = response.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    final String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    long delay = i * 10L;
                    if (delay == 0) {
                        player.sendMessage(prefix + " " + line);
                    } else {
                        Bukkit.getScheduler().runTaskLater(plugin,
                                () -> player.sendMessage(prefix + " " + line), delay);
                    }
                }
            });
        });
    }

    // ==================== Tab 补全 ====================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("clear");
            completions.add("remember");
            completions.add("forget");
            completions.add("memories");
            completions.add("loc");
            completions.add("stats");
            completions.add("profile");
            completions.add("project");
            completions.add("tell");
            completions.add("mute");
            completions.add("recipe");
            completions.add("logs");
            completions.add("diary");
            if (sender.hasPermission("agent.admin")) {
                completions.add("reload");
                completions.add("clearall");
            }
            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if ("loc".equals(args[0].toLowerCase()) && args.length == 2) {
            return List.of("list", "remove").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if ("loc".equals(args[0].toLowerCase()) && "remove".equals(args[1].toLowerCase()) && args.length == 3) {
            if (sender instanceof Player p) {
                return locationManager.getLocationNames(p.getUniqueId()).stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .toList();
            }
        }
        if ("tell".equals(args[0].toLowerCase()) && args.length == 2) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if ("logs".equals(args[0].toLowerCase()) && args.length == 2 && sender.hasPermission("agent.admin")) {
            return List.of("stats", "cost", "chat", "errors", "player", "search").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if ("diary".equals(args[0].toLowerCase()) && args.length == 2) {
            List<String> completions = new ArrayList<>();
            completions.add("list");
            if (diaryService != null) {
                completions.addAll(diaryService.listDiaryDates());
            }
            return completions.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
