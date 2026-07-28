package org.agent.agent.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务器日记：追踪每日统计 + 跨日自动生成 AI 日记
 */
public class ServerDiaryService {

    private final JavaPlugin plugin;
    private final File diaryDir;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
    private final String agentName;

    // 当日统计
    private volatile String currentDate = dateFmt.format(new Date());
    private final AtomicInteger joins = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicInteger chats = new AtomicInteger();
    private final AtomicInteger aiCalls = new AtomicInteger();
    private volatile int peakPlayers = 0;
    private final Set<String> playerNames = ConcurrentHashMap.newKeySet();
    private final Map<String, String> notableEvents = new ConcurrentHashMap<>(); // HH:mm → 事件

    // 日记生成相关
    private OpenAIService openAIService;
    private ActionExecutor actionExecutor;
    private boolean diaryEnabled = true;

    public ServerDiaryService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.diaryDir = new File(plugin.getDataFolder(), "diaries");
        if (!diaryDir.exists()) diaryDir.mkdirs();
        this.agentName = plugin.getConfig().getString("agent.name", "Agent");
    }

    public void setOpenAIService(OpenAIService openAIService) { this.openAIService = openAIService; }
    public void setActionExecutor(ActionExecutor ae) { this.actionExecutor = ae; }

    // ==================== 统计记录 ====================

    public void recordJoin(String playerName) {
        playerNames.add(playerName);
        joins.incrementAndGet();
        int online = Bukkit.getOnlinePlayers().size();
        if (online > peakPlayers) peakPlayers = online;
    }

    public void recordDeath(String playerName, String cause) {
        deaths.incrementAndGet();
        String time = new SimpleDateFormat("HH:mm").format(new Date());
        notableEvents.put(time, "💀 " + playerName + " — " + cause);
    }

    public void recordChat() { chats.incrementAndGet(); }

    public void recordAiCall() { aiCalls.incrementAndGet(); }

    /**
     * 记录任意重要事件（如玩家完成成就、挖到钻石等）
     */
    public void recordEvent(String event) {
        String time = new SimpleDateFormat("HH:mm").format(new Date());
        notableEvents.put(time, event);
    }

    // ==================== 日记生成 ====================

    /**
     * 每 5 分钟检查一次日期是否变更。
     * 如果跨日，异步生成昨天的日记并重置统计。
     */
    public void checkDayRollover() {
        String today = dateFmt.format(new Date());
        if (currentDate.equals(today)) return; // 未跨日

        // 拍照当前日期的统计快照
        final String yesterdayDate = currentDate;
        final int finalJoins = joins.getAndSet(0);
        final int finalDeaths = deaths.getAndSet(0);
        final int finalChats = chats.getAndSet(0);
        final int finalAiCalls = aiCalls.getAndSet(0);
        final int finalPeak = peakPlayers;
        final Set<String> finalPlayers = new HashSet<>(playerNames);
        final Map<String, String> finalEvents = new LinkedHashMap<>(notableEvents);

        peakPlayers = 0;
        playerNames.clear();
        notableEvents.clear();
        currentDate = today;

        // 异步生成日记
        generateDiary(yesterdayDate, finalJoins, finalDeaths, finalChats, finalAiCalls,
                finalPlayers, finalEvents, finalPeak);
    }

    private void generateDiary(String date, int joins, int deaths, int chats, int aiCalls,
                                Set<String> players, Map<String, String> events, int peak) {
        if (!diaryEnabled || openAIService == null) {
            // 没有 AI 可用时写入纯统计
            savePlainDiary(date, joins, deaths, chats, aiCalls, players, events, peak);
            return;
        }

        StringBuilder stats = new StringBuilder();
        stats.append("日期: ").append(date).append("\n");
        stats.append("总登录次数: ").append(joins).append("\n");
        stats.append("死亡次数: ").append(deaths).append("\n");
        stats.append("聊天消息: ").append(chats).append("\n");
        stats.append("AI调用: ").append(aiCalls).append("\n");
        stats.append("独立玩家: ").append(players.size()).append(" 人\n");
        stats.append("最高在线: ").append(peak).append(" 人\n");
        stats.append("上线玩家: ").append(String.join(", ", players)).append("\n");
        if (!events.isEmpty()) {
            stats.append("重要事件:\n");
            events.forEach((t, e) -> stats.append("  ").append(t).append(" ").append(e).append("\n"));
        }

        List<OpenAIService.ChatMessage> messages = List.of(
                OpenAIService.ChatMessage.system(
                        "你是" + plugin.getConfig().getString("server_name", "Minecraft Server") +
                        "服务器的傲娇猫娘「" + plugin.getConfig().getString("agent.name", "Agent") + "」。服务器运行了一整天，" +
                        "下面是今天的统计数据。请用" + agentName + "的语气写一篇短日记（中文，3-6句），" +
                        "像真的在写日记一样自然。可以吐槽死亡太多、夸夸最活跃的玩家、" +
                        "记一下有趣的事件。语气要亲切、有傲娇感，不要报流水账。" +
                        "禁止在日记里提及分组、类型等元数据。"
                ),
                OpenAIService.ChatMessage.user("今天的统计数据：\n" + stats.toString())
        );

        openAIService.chat(messages).thenAccept(diary -> {
            String entry;
            if (diary == null || diary.isEmpty()) {
                entry = buildPlainDiary(date, joins, deaths, chats, aiCalls, players, events, peak);
            } else {
                entry = "══════ " + date + " " + agentName + "日记 ══════\n\n" + diary.trim() + "\n";
            }
            saveDiaryFile(date, entry);
        });
    }

    private String buildPlainDiary(String date, int joins, int deaths, int chats, int aiCalls,
                                    Set<String> players, Map<String, String> events, int peak) {
        StringBuilder sb = new StringBuilder();
        sb.append("══════ ").append(date).append(" " + agentName + "日记 ══════\n\n");
        sb.append("今天服务器有 ").append(peak).append(" 位冒险者同时在线，一共登录了 ").append(joins).append(" 次喵~\n");
        if (deaths > 0) sb.append("发生了 ").append(deaths).append(" 次死亡，大家要注意安全啊！\n");
        sb.append("玩家们一共聊了 ").append(chats).append(" 句话，叫了我 ").append(aiCalls).append(" 次喵~\n");
        if (!events.isEmpty()) {
            sb.append("\n今天发生的特别事件：\n");
            events.forEach((t, e) -> sb.append("  ").append(t).append(" ").append(e).append("\n"));
        }
        sb.append("\n（AI 不可用时由系统自动生成）\n");
        return sb.toString();
    }

    private void savePlainDiary(String date, int joins, int deaths, int chats, int aiCalls,
                                 Set<String> players, Map<String, String> events, int peak) {
        String entry = buildPlainDiary(date, joins, deaths, chats, aiCalls, players, events, peak);
        saveDiaryFile(date, entry);
    }

    private void saveDiaryFile(String date, String entry) {
        File file = new File(diaryDir, date + ".txt");
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8)) {
            fw.write(entry);
            plugin.getLogger().info("日记已保存: " + date);
        } catch (IOException e) {
            plugin.getLogger().warning("日记写入失败: " + e.getMessage());
        }
    }

    // ==================== 日记查询 ====================

    public String readDiary(String date) {
        File file = new File(diaryDir, date + ".txt");
        if (!file.exists()) {
            // 尝试带扩展名
            file = new File(diaryDir, date);
            if (!file.exists()) return "§e那天" + agentName + "没有写日记哦喵~";
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "§c读取日记失败: " + e.getMessage();
        }
    }

    public List<String> listDiaryDates() {
        File[] files = diaryDir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null) return List.of();
        return Arrays.stream(files)
                .map(f -> f.getName().replace(".txt", ""))
                .sorted(Comparator.reverseOrder())
                .limit(30)
                .toList();
    }

    // ==================== getter 供 patrol 使用 ====================

    public String getCurrentDate() { return currentDate; }
    public int getTodayJoins() { return joins.get(); }
    public int getTodayDeaths() { return deaths.get(); }
    public int getTodayChats() { return chats.get(); }
    public int getTodayAiCalls() { return aiCalls.get(); }
}
