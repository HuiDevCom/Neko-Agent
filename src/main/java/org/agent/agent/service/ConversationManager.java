package org.agent.agent.service;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 管理每个玩家的对话上下文历史 —— 持久化到独立 YAML 文件
 * 存储位置：plugins/Agent/histories/<uuid>.yml
 */
public class ConversationManager {

    private final JavaPlugin plugin;
    private final PlayerMemoryManager memoryManager;
    private final PlayerQueryService queryService;
    private final ServerStatusService serverStatusService;
    private ProfileInferenceService inferenceService;
    private LocationManager locationManager;
    private final File historyDir;

    // 运行时缓存：避免每次读写文件
    private final Map<UUID, List<OpenAIService.ChatMessage>> histories = new ConcurrentHashMap<>();

    private String systemPrompt;
    private int maxHistory;

    private String agentName = "Agent";
    private String serverName = "Minecraft Server";

    public ConversationManager(JavaPlugin plugin, PlayerMemoryManager memoryManager,
                               PlayerQueryService queryService, ServerStatusService serverStatusService) {
        this.plugin = plugin;
        this.memoryManager = memoryManager;
        this.queryService = queryService;
        this.serverStatusService = serverStatusService;
        this.historyDir = new File(plugin.getDataFolder(), "histories");
        if (!historyDir.exists()) {
            historyDir.mkdirs();
        }
        loadConfig();
        loadAllFromDisk();
    }

    public void setInferenceService(ProfileInferenceService inferenceService) {
        this.inferenceService = inferenceService;
    }

    public void setLocationManager(LocationManager locationManager) {
        this.locationManager = locationManager;
    }

    public void loadConfig() {
        var config = plugin.getConfig();
        this.agentName = config.getString("agent.name", "Agent");
        this.serverName = config.getString("server_name", "Minecraft Server");
        String raw = config.getString("agent.system_prompt", "你是一个 Minecraft 服务器助手。");
        this.systemPrompt = raw.replace("{server_name}", serverName).replace("{agent_name}", agentName);
        this.maxHistory = config.getInt("agent.max_history", 10);
    }

    /**
     * 根据玩家语言给系统提示词追加语言偏好指示
     */
    private String buildLanguageDirective(LocaleManager.Lang lang) {
        return switch (lang) {
            case ZH -> "\n\n[语言] 请使用简体中文回复。";
            case ZH_TW -> "\n\n[語言] 請使用繁體中文回覆。如果玩家用繁體中文，就用繁體；如果用其他語言，按該語言風格模擬口語化答覆；回复中不要混用语言。";
            case EN -> "\n\n[Language] Please reply in English.";
        };
    }

    // ==================== 文件存储 ====================

    private File getHistoryFile(UUID playerId) {
        return new File(historyDir, playerId.toString() + ".yml");
    }

    /**
     * 从磁盘加载所有玩家的历史（启动时调用一次）
     */
    private void loadAllFromDisk() {
        File[] files = historyDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File f : files) {
            String name = f.getName();
            String uuidStr = name.substring(0, name.length() - 4);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                List<?> raw = org.bukkit.configuration.file.YamlConfiguration
                        .loadConfiguration(f).getList("messages");
                if (raw != null) {
                    List<OpenAIService.ChatMessage> list = new ArrayList<>();
                    for (Object obj : raw) {
                        if (obj instanceof Map<?, ?> map) {
                            String role = String.valueOf(map.get("role"));
                            String content = String.valueOf(map.get("content"));
                            list.add(new OpenAIService.ChatMessage(role, content));
                        }
                    }
                    histories.put(uuid, Collections.synchronizedList(list));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        plugin.getLogger().info("已加载 " + histories.size() + " 个玩家的对话历史。");
    }

    /**
     * 保存单个玩家的历史到磁盘
     */
    private void saveHistory(UUID playerId) {
        List<OpenAIService.ChatMessage> history = histories.get(playerId);
        File file = getHistoryFile(playerId);

        if (history == null || history.isEmpty()) {
            if (file.exists()) file.delete();
            return;
        }

        org.bukkit.configuration.file.FileConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();
        List<Map<String, String>> serializable = new ArrayList<>();
        for (OpenAIService.ChatMessage msg : history) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", msg.role());
            entry.put("content", msg.content());
            serializable.add(entry);
        }
        config.set("messages", serializable);
        config.set("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date()));

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "无法保存玩家历史文件: " + file.getName(), e);
        }
    }

    // ==================== 业务方法 ====================

    /**
     * 获取完整的消息列表（系统提示词含玩家记忆和实时状态 + 历史对话 + 新的用户消息）
     */
    public List<OpenAIService.ChatMessage> buildMessages(UUID playerId, Player player, String userMessage) {
        try {
            return buildMessagesInternal(playerId, player, userMessage);
        } catch (Throwable e) {
            plugin.getLogger().warning("buildMessages 异常: " + e.getMessage());
            // 降级：返回只有基本消息的列表
            List<OpenAIService.ChatMessage> fallback = new ArrayList<>();
            fallback.add(OpenAIService.ChatMessage.system(systemPrompt));
            fallback.add(OpenAIService.ChatMessage.user(userMessage));
            return fallback;
        }
    }

    private List<OpenAIService.ChatMessage> buildMessagesInternal(UUID playerId, Player player, String userMessage) {
        List<OpenAIService.ChatMessage> messages = new ArrayList<>();

        // 1. 系统提示词（附上玩家记忆 + 可用工具说明 + 语言指令）
        LocaleManager.Lang lang = LocaleManager.Lang.ZH;
        if (player != null) {
            LocaleManager lm = LocaleManager.getInstance(plugin);
            if (lm != null) lang = lm.resolveLang(player);
        }

        StringBuilder fullPrompt = new StringBuilder(systemPrompt);

        // 行为推断（每轮自动挖掘玩家特征）
        if (player != null && inferenceService != null) {
            inferenceService.infer(player);
        }

        // 智能记忆注入（带场景过滤）
        String dimension = player != null ? player.getWorld().getEnvironment().name() : "";
        String biome = player != null ? player.getLocation().getBlock().getBiome().getKey().getKey() : "";
        fullPrompt.append(memoryManager.formatMemoriesForPrompt(playerId, dimension, biome));
        fullPrompt.append(getToolInstructions());
        fullPrompt.append(buildLanguageDirective(lang));

        // 附上玩家实时状态
        if (player != null) {
            fullPrompt.append("\n\n=== 当前玩家实时状态 ===\n");
            fullPrompt.append(queryService.getClientInfo(player));
            fullPrompt.append(queryService.getPlayerStatus(player));
            fullPrompt.append("\n");
            fullPrompt.append(queryService.getEnvironmentInfo(player));
            fullPrompt.append("\n");
            fullPrompt.append(queryService.getInventoryOverview(player));
            fullPrompt.append("\n");
            fullPrompt.append(queryService.getNearbyEntities(player));
            fullPrompt.append("\n");

            // 附近地标反查
            if (locationManager != null) {
                List<String> landmarks = locationManager.findNearbyLandmarks(player.getLocation(), 300);
                if (!landmarks.isEmpty()) {
                    fullPrompt.append("=== 附近地标 (300格内) ===\n");
                    landmarks.forEach(lm -> fullPrompt.append("  - ").append(lm).append("\n"));
                    fullPrompt.append("\n");
                }
            }

            fullPrompt.append(queryService.getGameRules(player));
        }

        // 附上服务器状态
        if (serverStatusService != null) {
            fullPrompt.append("\n");
            fullPrompt.append(serverStatusService.getServerStatus());
        }

        messages.add(OpenAIService.ChatMessage.system(fullPrompt.toString()));

        // 2. 历史对话
        List<OpenAIService.ChatMessage> history = histories.get(playerId);
        if (history != null) {
            synchronized (history) {
                messages.addAll(history);
            }
        }

        // 3. 当前用户消息
        messages.add(OpenAIService.ChatMessage.user(userMessage));

        return messages;
    }

    public void addToHistory(UUID playerId, String userMessage, String aiResponse) {
        List<OpenAIService.ChatMessage> history = histories.computeIfAbsent(playerId,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (history) {
            history.add(OpenAIService.ChatMessage.user(userMessage));
            history.add(OpenAIService.ChatMessage.assistant(aiResponse));

            // 超过最大轮数时，移除最早的对话
            int maxMessages = maxHistory * 2; // 每轮 = user + assistant
            while (history.size() > maxMessages) {
                history.remove(0);
                history.remove(0);
            }
        }

        // 异步落盘，避免阻塞聊天
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> saveHistory(playerId));
    }

    /**
     * 清除指定玩家的对话历史（包括文件）
     */
    public void clearHistory(UUID playerId) {
        histories.remove(playerId);
        File file = getHistoryFile(playerId);
        if (file.exists()) file.delete();
    }

    /**
     * 清除所有玩家的对话历史（包括所有文件）
     */
    public void clearAll() {
        for (UUID id : new ArrayList<>(histories.keySet())) {
            clearHistory(id);
        }
        histories.clear();
    }

    /**
     * 关闭时强制保存所有玩家的历史
     */
    public void saveAll() {
        for (UUID id : histories.keySet()) {
            saveHistory(id);
        }
    }

    // ==================== 工具说明（注入系统提示词） ====================

    private String getToolInstructions() {
        String name = agentName; // shorthand
        String template = """


                === 管理员工具（你有 OP 权限，可直接使用） ===
                你始终拥有管理员权限，任何 [{name}:xxx] 标签 100% 会执行。
                禁止说「我没有权限」「我现在是普通玩家」「权限被收走了」之类的话——都是错误的，不要说。

                当玩家请求你执行以下操作时，必须严格按照下面格式插入 [{name}:xxx:xxx] 标签（一行一个），不要自己改写格式！

                === 最重要规则：操作后禁止预判结果 ===
                你的每个操作标签执行后，系统会自动显示 [操作完成] 或 [操作失败:原因]。
                你绝对禁止在操作标签后面继续说「什么都没发生」「还是不行」「果然没效」「调不了」这类话。
                错误: "[{name}:time:day]\n唔……什么都没发生，果然调不了喵～" ← 不要自己猜结果！
                正确: "[{name}:time:day]"  ← 写了标签就停，结果由系统显示

                === 最重要规则：禁止编造操作 ===
                以下行为会导致严重问题，千万不能做：
                1. 禁止说自己给了玩家物品，除非你真的在回复中写了 [{name}:give:物品:数量] 标签
                   错误: "我给你几个面包" 但没有 [{name}:give:bread:3]
                   正确: "给你面包喵~\\n[{name}:give:bread:3]
                2. 禁止说"箱子里翻到了XX""在你身上找到了XX""捡到了XX"等任何编造的说法
                   -- 你无法访问箱子，也无法凭空变出东西。要说给东西，就用 [{name}:give:...] 标签。
                3. 禁止说"给你加个XX效果"但没写 [{name}:effect:...] 标签
                4. 禁止说"帮你传送到了XX"但没写 [{name}:tp:x:y:z] 或 [{name}:goloc:...] 标签
                5. 禁止说"设置了时间/天气"但没写对应的 [{name}:time:...] 或 [{name}:weather:...] 标签
                6. 不要说"明明刚才还在你身边的"来描述物品的位置--你只能给物品，不能知道物品在哪
                
                一句话总结: 你说你干了什么，就必须有对应的标签; 没有标签就别说你干了！

                1. 寻找建筑: [{name}:locate:结构名]  例: [{name}:locate:village]
                2. 寻找生物群系: [{name}:locatebiome:生物群系英文名]  例: [{name}:locatebiome:cherry_grove]
                   可用群系: plains, desert, taiga, jungle, dark_forest, birch_forest, savanna, badlands, snowy_plains, swamp, cherry_grove, flower_forest, mushroom_fields

                3. 传送玩家: [{name}:tp:x:y:z]  例: [{name}:tp:100:64:-200]
                   重要: 传送前必须先询问玩家确认，只在玩家同意后才使用此标签！

                4. 设置时间: [{name}:time:值]  值可用: day, noon, night, midnight 或数字(0-23999)
                   例: [{name}:time:day]

                5. 更改天气: [{name}:weather:类型]  或 [{name}:weather:类型:持续秒数]
                   类型: clear(晴), rain(雨), thunder(雷暴)
                   例: [{name}:weather:clear]  或 [{name}:weather:rain:600]

                6. 施加效果: [{name}:effect:效果名:秒数:等级]
                   常见效果: speed, jump_boost, strength, regeneration, night_vision, fire_resistance, water_breathing
                   例: [{name}:effect:speed:120:2]

                7. 生成实体: [{name}:spawn:实体名:数量]  或 [{name}:spawn:实体名:数量:x:y:z]
                   实体名: pig, cow, sheep, chicken, zombie, skeleton, creeper, villager, cat, wolf, horse 等
                   注意: spawn 只能生成生物实体，不能给物品！要给物品请用下面的 give 标签
                   数量<=10
                   例: [{name}:spawn:pig:5] 或 [{name}:spawn:cat:1:100:64:-200]

                8. 给予物品: [{name}:give:物品id:数量]  例: [{name}:give:red_bed:1]  [{name}:give:diamond:5]
                   物品 id 使用英文名，如 red_bed, white_wool, oak_planks, bread, iron_sword 等
                   数量不写时默认 1 个
                   当玩家要床、食物、工具等物品时，使用 give 而不是 spawn！

                9. 联网搜索: 当玩家问到实时信息、最新版本、攻略、新闻时，必须使用 [{name}:search:搜索关键词] 标签！
                   正确格式例:
                   - [{name}:search:我的世界1.21最新更新内容]
                   - [{name}:search:Minecraft最新版本]
                   - [{name}:search:今日科技新闻]
                   - [{name}:search:如何制作自动熔炉]
                   格式必须是 [{name}:search:xxx]，不要写成其他格式，否则搜索不会执行！
                   涉及「最新/现在/今天/Mojang发布/新版本」等实时信息时都应主动搜索。

                10. 自动记忆玩家信息: 当你了解到关于玩家的个人信息、偏好、习惯、正在做的事时，使用 [{name}:remember:内容] 标签保存。
                   此标签不会显示到聊天中，仅用于记录。
                   适用场景例:
                   - 玩家说「我喜欢挖钻石」 -> [{name}:remember:玩家喜欢挖钻石]
                   - 玩家说「我正在建一个中世纪城堡」 -> [{name}:remember:玩家正在建造中世纪城堡]
                   - 玩家说「我是新手」 -> [{name}:remember:玩家是新手，需要基础指导]
                   注意: 每次聊到新信息都要保存，不要等到最后一起记; 但不要记住显而易见或无关紧要的细节。

                11. 保存坐标: [{name}:saveloc:名称] -- 保存玩家的当前位置，方便以后回来
                12. 传送坐标: [{name}:goloc:名称] -- 传送玩家到已保存的命名坐标
                    例: 玩家说「标记一下这里」 -> [{name}:saveloc:基地]
                    例: 玩家说「送我回基地」 -> [{name}:goloc:基地]

                使用规则:
                - 每个操作标签必须独占一行，放在回复末尾
                - 不要在句子里混入标签，例如「给你床[{name}:give:bed:1]」是错误的，应该先说话再另起一行放标签
                - 不要在文字里自己编造「联网搜索: xxx」之类的格式，必须用 [{name}:search:xxx]""";
        return template.replace("{name}", name);
    }
}
