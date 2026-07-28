package org.agent.agent;

import org.agent.agent.command.AgentCommand;
import org.agent.agent.listener.*;
import org.agent.agent.service.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.List;

public final class Agent extends JavaPlugin {

    private OpenAIService openAIService;
    private KimiSearchService kimiService;
    private ConversationManager conversationManager;
    private PlayerMemoryManager playerMemoryManager;
    private PlayerQueryService playerQueryService;
    private LocationManager locationManager;
    private ActionExecutor actionExecutor;
    private ServerStatusService serverStatusService;
    private LocaleManager localeManager;
    private IdleReminderListener idleReminder;
    private ChatListener chatListener;
    private SensitiveWordFilter sensitiveFilter;
    private PlayerMuteManager muteManager;
    private ProfileInferenceService inferenceService;
    private RateLimiter rateLimiter;
    private LogManager logManager;
    private ServerDiaryService diaryService;
    private int patrolTaskId = -1;
    private int diaryCheckTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ===== 配置校验 =====
        validateConfig();

        openAIService = new OpenAIService(this);
        kimiService = new KimiSearchService(this);
        playerMemoryManager = new PlayerMemoryManager(this);
        playerQueryService = new PlayerQueryService();
        locationManager = new LocationManager(this);
        sensitiveFilter = new SensitiveWordFilter(this);
        muteManager = new PlayerMuteManager(this);
        rateLimiter = new RateLimiter(this);
        logManager = new LogManager(this);
        diaryService = new ServerDiaryService(this);
        diaryService.setOpenAIService(openAIService);
        actionExecutor = new ActionExecutor(kimiService);
        actionExecutor.loadConfig(getConfig().getString("agent.name", "Agent"));
        actionExecutor.setMemoryManager(playerMemoryManager);
        actionExecutor.setLocationManager(locationManager);
        serverStatusService = new ServerStatusService();
        localeManager = new LocaleManager(this);
        conversationManager = new ConversationManager(this, playerMemoryManager, playerQueryService, serverStatusService);
        inferenceService = new ProfileInferenceService(this, playerMemoryManager);
        conversationManager.setInferenceService(inferenceService);
        conversationManager.setLocationManager(locationManager);

        // 聊天监听器
        chatListener = new ChatListener(this, openAIService, conversationManager, actionExecutor, localeManager,
                sensitiveFilter, muteManager, rateLimiter, logManager);
        chatListener.setDiaryService(diaryService);
        getServer().getPluginManager().registerEvents(chatListener, this);

        // 死亡监听器
        getServer().getPluginManager().registerEvents(
                new DeathListener(this, openAIService, conversationManager, actionExecutor, playerMemoryManager), this);

        // 进服欢迎监听器
        getServer().getPluginManager().registerEvents(
                new WelcomeListener(this, openAIService, playerMemoryManager, playerQueryService,
                        serverStatusService, actionExecutor, localeManager), this);

        // 成就监听器
        getServer().getPluginManager().registerEvents(
                new AchievementListener(this, openAIService, playerMemoryManager, actionExecutor, localeManager), this);

        // 空闲提醒
        idleReminder = new IdleReminderListener(this, openAIService, actionExecutor, localeManager);
        getServer().getPluginManager().registerEvents(idleReminder, this);
        idleReminder.start();

        // 记忆衰减定时器（每 30 分钟执行一次）
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            playerMemoryManager.decayAll();
        }, 36000L, 36000L); // 30 分钟 = 36000 ticks

        // 日记统计事件监听
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onJoin(PlayerJoinEvent e) { diaryService.recordJoin(e.getPlayer().getName()); }
            @EventHandler(priority = EventPriority.MONITOR)
            public void onChat(AsyncPlayerChatEvent e) { diaryService.recordChat(); }
            @EventHandler(priority = EventPriority.MONITOR)
            public void onDeath(PlayerDeathEvent e) {
                String msg = e.getDeathMessage() != null ? e.getDeathMessage() : "未知原因";
                diaryService.recordDeath(e.getEntity().getName(), msg);
            }
            @EventHandler(priority = EventPriority.MONITOR)
            public void onAdvancement(PlayerAdvancementDoneEvent e) {
                String key = e.getAdvancement().getKey().getKey();
                // 只记录非配方类的真正成就
                if (!key.startsWith("recipes/")) {
                    diaryService.recordEvent("⭐ " + e.getPlayer().getName() + " 达成 " + key);
                }
            }
        }, this);

        // 服务器异常巡检（每 10 分钟）
        patrolTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> doPatrol(), 12000L, 12000L).getTaskId();

        // 跨日日记检查（每 5 分钟）
        diaryCheckTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            diaryService.checkDayRollover();
        }, 6000L, 6000L).getTaskId();

        // /agent 命令
        AgentCommand agentCommand = new AgentCommand(this, openAIService, conversationManager,
                playerMemoryManager, actionExecutor, kimiService, localeManager, locationManager, muteManager,
                logManager, chatListener, diaryService);
        Objects.requireNonNull(getCommand("agent")).setExecutor(agentCommand);
        Objects.requireNonNull(getCommand("agent")).setTabCompleter(agentCommand);

        getLogger().info((getConfig().getString("agent.name", "Agent")) + " Agent 已启动！使用 /agent 或聊天前缀与" + getConfig().getString("agent.name", "Agent") + "对话吧~");
    }

    public RateLimiter getRateLimiter() { return rateLimiter; }
    public LogManager getLogManager() { return logManager; }
    public ServerDiaryService getDiaryService() { return diaryService; }

    /** 服务器异常巡检：每 10 分钟扫描一次 */
    private void doPatrol() {
        if (openAIService == null) return;
        int playerCount = Bukkit.getOnlinePlayers().size();
        if (playerCount == 0) return; // 没人在线不浪费 token

        StringBuilder alert = new StringBuilder();

        // 1. 检查 TPS（仅 Paper 支持）
        try {
            double[] tps = Bukkit.getTPS();
            if (tps.length > 0 && tps[0] < 15.0) {
                alert.append("⚠ TPS 低至 ").append(String.format("%.1f", tps[0])).append("！服务器可能出现卡顿\n");
            }
        } catch (NoSuchMethodError ignored) {}

        // 2. 扫描在线玩家附近的危险实体
        for (Player p : Bukkit.getOnlinePlayers()) {
            var nearby = p.getWorld().getNearbyEntities(p.getLocation(), 20, 20, 20);
            int threatCount = 0;
            String nearestName = null;
            double nearestDist = 999;
            for (Entity e : nearby) {
                if (e instanceof Monster) {
                    threatCount++;
                    double d = p.getLocation().distance(e.getLocation());
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearestName = e.getType().name().toLowerCase();
                    }
                }
            }
            if (threatCount >= 5 && nearestDist < 10) {
                alert.append("⚠ ").append(p.getName()).append(" 附近有 ").append(threatCount)
                        .append(" 只怪物（最近 ").append(nearestName).append(" ").append(String.format("%.0f", nearestDist)).append("米）\n");
            }
        }

        // 3. 检查高频区块实体数
        for (Player p : Bukkit.getOnlinePlayers()) {
            var chunk = p.getLocation().getChunk();
            int entityCount = chunk.getEntities().length;
            if (entityCount > 50) {
                alert.append("⚠ ").append(p.getWorld().getName()).append(" 区块(")
                        .append(chunk.getX()).append(",").append(chunk.getZ())
                        .append(") 实体数 ").append(entityCount).append("，可能导致卡顿\n");
            }
        }

        if (alert.length() == 0) return; // 一切正常

        // 异步让 AI 生成警告
        final String alertText = alert.toString();
        String agentName = getConfig().getString("agent.name", "Agent");
        String serverName = getConfig().getString("server_name", "Minecraft Server");
        List<OpenAIService.ChatMessage> msgs = List.of(
            OpenAIService.ChatMessage.system(
                "你是" + serverName + "服务器的傲娇猫娘「" + agentName + "」。你发现了服务器的一些异常情况，" +
                "请用你的语气向全服玩家发出简洁警告（中文，1-2句）。语气要带点紧张感但不恐怖。"
            ),
            OpenAIService.ChatMessage.user("发现以下异常：\n" + alertText + "\n请发出警告。")
        );
        openAIService.chat(msgs).thenAccept(resp -> {
            if (resp == null || resp.isEmpty()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                String prefix = localeManager.get(LocaleManager.Lang.ZH, "prefix");
                String[] lines = resp.split("\n");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        Bukkit.broadcastMessage(prefix + " " + line.trim());
                    }
                }
            });
        });
    }

    /** 启动时校验关键配置 */
    private void validateConfig() {
        var config = getConfig();
        String apiKey = config.getString("openai.api_key", "");
        String baseUrl = config.getString("openai.base_url", "");

        if (apiKey.isEmpty() || apiKey.contains("your-") || apiKey.equals("sk-your-deepseek-key-here")) {
            getLogger().warning("============================================");
            getLogger().warning("【警告】openai.api_key 未配置或使用了占位符！");
            getLogger().warning("请在 config.yml 中填入你的 DeepSeek API Key");
            getLogger().warning("否则" + getConfig().getString("agent.name", "Agent") + "将无法调用 AI 回复");
            getLogger().warning("============================================");
        }

        if (baseUrl.isEmpty()) {
            getLogger().warning("openai.base_url 未配置，使用默认值 https://api.deepseek.com");
        }

        boolean kimiEnabled = config.getBoolean("kimi.enabled", false);
        if (kimiEnabled) {
            String kimiKey = config.getString("kimi.api_key", "");
            if (kimiKey.isEmpty() || kimiKey.contains("your-")) {
                getLogger().warning("kimi.enabled = true 但 kimi.api_key 未配置，联网搜索不会工作");
            }
        }

        // 输出限流配置状态
        int globalRate = config.getInt("rate_limit.global_max_per_minute", 30);
        int playerRate = config.getInt("rate_limit.player_max_per_minute", 10);
        getLogger().info("限流: 全局 " + globalRate + " 次/分钟, 每人 " + playerRate + " 次/分钟");
    }

    @Override
    public void onDisable() {
        if (idleReminder != null) {
            idleReminder.stop();
        }
        if (patrolTaskId != -1) Bukkit.getScheduler().cancelTask(patrolTaskId);
        if (diaryCheckTaskId != -1) Bukkit.getScheduler().cancelTask(diaryCheckTaskId);
        if (logManager != null) {
            logManager.logAudit("server", "shutdown", "插件关闭");
        }
        if (conversationManager != null) {
            conversationManager.saveAll();
        }
        getLogger().info((getConfig().getString("agent.name", "Agent")) + " Agent 已关闭，再见~");
    }
}
