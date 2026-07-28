package org.agent.agent.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 AI 回复中的操作标签 [小绘:action:params] 并执行
 *
 * 支持的标签：
 *   [小绘:locate:结构]             定位建筑
 *   [小绘:locatebiome:群系]        定位生物群系
 *   [小绘:tp:x:y:z]                传送玩家
 *   [小绘:time:value]              设置时间
 *   [小绘:weather:type[:sec]]      更改天气
 *   [小绘:effect:name:sec:lvl]     施加状态效果
 *   [小绘:spawn:type:n[:x:y:z]]    生成实体
 *   [小绘:give:物品id:数量]          给予玩家物品
 *   [小绘:search:关键词]            联网搜索（Kimi）
 *   [小绘:remember:内容]            记住关于玩家的信息
 *   [小绘:saveloc:名称]             保存当前位置为命名坐标
 *   [小绘:goloc:名称]               传送至已保存的命名坐标
 */
public class ActionExecutor {

    private Pattern actionPattern;
    private Pattern fakeActionPattern;
    private Pattern fabricatedClaimPattern;

    private String agentName = "Agent";

    private final KimiSearchService kimiService;
    private PlayerMemoryManager memoryManager;
    private LocationManager locationManager;

    /** 上一次 processActions 实际执行的操作类型集合，供检测编造声明时使用 */
    private final Set<String> lastExecutedActions = new HashSet<>();

    public ActionExecutor(KimiSearchService kimiService) {
        this.kimiService = kimiService;
        compilePatterns("Agent");
    }

    /**
     * 根据配置中的 Agent 名字重新编译所有正则
     */
    public void loadConfig(String name) {
        this.agentName = name;
        compilePatterns(name);
    }

    private void compilePatterns(String name) {
        // [名字:操作:参数]  — 提取操作和参数的捕获组不变
        this.actionPattern = Pattern.compile("\\[" + Pattern.quote(name) + ":(\\w+):([^\\]]+)\\]");
        // 假括号检测：[正在...] [已完成...] 等
        this.fakeActionPattern = Pattern.compile(
                "\\[(?:正在|已|即将|准备|操作|传送|时间已|天气已|效果已|搜索中|给予|生成|记住|保存|定位|设置|更改|施加)" +
                "[^\\[\\]]*\\]");
        // 自然语言编造检测
        this.fabricatedClaimPattern = Pattern.compile(
                "(?:箱子里|翻到了|找到了|捡到了|拿出了|(?:刚|刚才).{0,4}给了?你的?|" +
                "在你.{0,3}(?:放了|留下了|找到了)|明明刚才|就在你.{0,3}(?:身上|旁边|手边))");
    }

    public void setMemoryManager(PlayerMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    public void setLocationManager(LocationManager locationManager) {
        this.locationManager = locationManager;
    }

    /**
     * 处理 AI 回复，提取并执行所有 [小绘:xxx:xxx] 操作标签
     * 搜索标签会被异步执行，其他标签同步替换后返回
     */
    public String processActions(Player player, String aiResponse) {
        lastExecutedActions.clear();
        Matcher matcher = actionPattern.matcher(aiResponse);
        StringBuilder result = new StringBuilder();
        List<String> searchQueries = new ArrayList<>();
        while (matcher.find()) {
            String action = matcher.group(1);
            String params = matcher.group(2);
            if ("search".equals(action)) {
                searchQueries.add(params.trim());
                matcher.appendReplacement(result, ""); // 搜索标签不显示任何内容
            } else {
                String replacement = executeAction(player, action, params);
                // 记录实际执行的操作类型（排除失败了的情况）
                if (!replacement.contains("失败") && !replacement.contains("未就绪")) {
                    lastExecutedActions.add(action);
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(result);

        // 裁剪与 [操作完成] 矛盾的文字行
        String finalText = stripContradictoryLines(result.toString());

        // 搜索异步执行，结果以小绘的语气自然发出
        if (!searchQueries.isEmpty() && Bukkit.getPluginManager().getPlugin("Agent") != null) {
            var plugin = Bukkit.getPluginManager().getPlugin("Agent");
            for (String query : searchQueries) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String searchResult = kimiService.search(query);
                    if (searchResult.startsWith("[")) {
                        return; // 搜索失败或未启用，不显示系统提示
                    }
                    // 去掉 markdown 格式
                    String clean = stripMarkdown(searchResult);
                    String[] lines = clean.split("\n");
                    Bukkit.getScheduler().runTask(plugin, () -> {
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
            }
        }

        return finalText;
    }

    /** 操作执行成功后的矛盾短语 */
    private static final Pattern CONTRADICTION_PATTERN = Pattern.compile(
            "(?:什么都没发生|还是不行|果然没效|果然设不了|调不了|没有权限|权限被收走了|" +
            "真正?的?普通玩家|什么都没反应|没有任何变化|没有反应|还是没反应)");

    /**
     * 如果文本中含 [操作完成]（说明操作成功），删除后续所有与它矛盾的行。
     * AI 在同一个回复中预判失败的常见 bug 用此修复。
     */
    private String stripContradictoryLines(String text) {
        if (!text.contains("[操作完成]")) return text;

        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            // 跳过矛盾行
            if (CONTRADICTION_PATTERN.matcher(line).find()) {
                continue;
            }
            sb.append(line).append("\n");
        }
        // 去掉末尾多余换行
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String executeAction(Player player, String action, String params) {
        // welcome 等场景下 player 可能为 null
        if (player == null && !isPlayerlessAction(action)) {
            return "[操作失败: 非玩家场景无法执行此操作]";
        }
        return switch (action) {
            case "locate" -> doLocate(params, false);
            case "locatebiome" -> doLocate(params, true);
            case "tp" -> doTeleport(player, params);
            case "time" -> doTimeSet(params);
            case "weather" -> doWeather(params);
            case "effect" -> doEffect(player, params);
            case "spawn" -> doSpawn(player, params);
            case "give" -> doGive(player, params);
            case "search" -> doSearch(params.trim());
            case "remember" -> doRemember(player, params);
            case "saveloc" -> doSaveLoc(player, params);
            case "goloc" -> doGoLoc(player, params);
            default -> "[未知操作: " + action + "]";
        };
    }

    /** 不需要 player 也能执行的操作 */
    private boolean isPlayerlessAction(String action) {
        return action.equals("locate") || action.equals("locatebiome")
                || action.equals("time") || action.equals("weather")
                || action.equals("search") || action.equals("remember");
    }

    // ==================== 寻址 ====================

    private String doLocate(String structureName, boolean isBiome) {
        String type = isBiome ? "biome" : "structure";
        String cmd = "minecraft:locate " + type + " " + structureName;
        return executeCommand(cmd, "定位");
    }

    // ==================== 传送 ====================

    private String doTeleport(Player player, String params) {
        // 支持 "x:y:z"、"x y z"、"x, y, z" 等多种分隔
        String[] parts = params.split("[:,;\\s]+");
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            player.teleport(new Location(player.getWorld(), x, y, z));
            return "[已传送到 " + (int) x + ", " + (int) y + ", " + (int) z + "]";
        } catch (Exception e) {
            return "[传送失败: 坐标格式错误]";
        }
    }

    // ==================== 时间 ====================

    private String doTimeSet(String timeValue) {
        String cmd = "minecraft:time set " + timeValue.trim();
        return executeCommand(cmd, "设置时间");
    }

    // ==================== 天气 ====================

    private String doWeather(String params) {
        String[] parts = params.split(":");
        String weatherType = parts[0].trim();
        String cmd = "minecraft:weather " + weatherType;
        if (parts.length > 1) {
            cmd += " " + parts[1].trim();
        }
        return executeCommand(cmd, "更改天气");
    }

    // ==================== 状态效果 ====================

    private String doEffect(Player player, String params) {
        String[] parts = params.split(":");
        try {
            String effectName = parts[0].trim();
            int seconds = Integer.parseInt(parts[1].trim());
            int level = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 1;
            String cmd = "minecraft:effect give " + player.getName() + " " + effectName + " " + seconds + " " + level + " true";
            return executeCommand(cmd, "施加效果");
        } catch (Exception e) {
            return "[施加效果失败: 参数格式错误]";
        }
    }

    // ==================== 生成实体 ====================

    private String doSpawn(Player player, String params) {
        // 支持 "entity:count:x:y:z"、"entity count" 等多种格式
        String[] parts = params.split("[:,;\\s]+");
        try {
            String entityType = parts[0].trim();
            int count = parts.length > 1 ? Math.min(Integer.parseInt(parts[1].trim()), 10) : 1;
            Location loc;

            if (parts.length >= 5) {
                double x = Double.parseDouble(parts[2].trim());
                double y = Double.parseDouble(parts[3].trim());
                double z = Double.parseDouble(parts[4].trim());
                loc = new Location(player.getWorld(), x, y, z);
            } else {
                loc = player.getLocation();
            }

            for (int i = 0; i < count; i++) {
                String cmd = "minecraft:summon " + entityType + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            return "[已生成 " + count + " 只 " + entityType + "]";
        } catch (Exception e) {
            return "[生成实体失败: 参数格式错误]";
        }
    }

    // ==================== 给予物品 ====================

    private String doGive(Player player, String params) {
        // 支持 "物品id:数量" 或 "物品id"（默认 1 个）
        String[] parts = params.split("[:,;\\s]+");
        try {
            String itemId = parts[0].trim();
            // 补上 minecraft: 前缀
            if (!itemId.startsWith("minecraft:")) {
                itemId = "minecraft:" + itemId;
            }
            int count = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1].trim())) : 1;
            String cmd = "minecraft:give " + player.getName() + " " + itemId + " " + count;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            return "[已给予 " + count + " 个 " + itemId.replace("minecraft:", "") + "]";
        } catch (Exception e) {
            return "[给予物品失败: " + params + "]";
        }
    }

    // ==================== 联网搜索（Kimi） ====================

    private String doSearch(String query) {
        try {
            String result = kimiService.search(query);
            return "[联网搜索结果]: " + result;
        } catch (Exception e) {
            return "[搜索失败: " + e.getMessage() + "]";
        }
    }

    // ==================== 自动记忆 ====================

    private String doRemember(Player player, String content) {
        if (memoryManager == null) return "记忆模块未就绪";
        String memory = content.trim();
        if (memory.isEmpty()) return "";
        // 去掉引号
        if ((memory.startsWith("\"") && memory.endsWith("\""))
                || (memory.startsWith("「") && memory.endsWith("」"))
                || (memory.startsWith("'") && memory.endsWith("'"))) {
            memory = memory.substring(1, memory.length() - 1);
        }
        memoryManager.addMemory(player.getUniqueId(), memory);
        return ""; // 不显示任何内容到聊天
    }

    // ==================== 坐标管理 ====================

    private String doSaveLoc(Player player, String name) {
        if (locationManager == null) return "[坐标功能未就绪]";
        String locName = name.trim();
        if (locName.isEmpty()) return "";
        locationManager.saveLocation(player.getUniqueId(), locName, player.getLocation());
        return ""; // 不显示
    }

    private String doGoLoc(Player player, String name) {
        if (locationManager == null) return "[坐标功能未就绪]";
        String locName = name.trim();
        if (locName.isEmpty()) return "[坐标名称为空]";
        var loc = locationManager.getLocation(player.getUniqueId(), locName);
        if (loc == null) return "[未找到坐标: " + locName + "]";
        player.teleport(loc);
        return "[已传送到 " + locName + "]";
    }

    // ==================== 通用命令执行 ====================

    /** 去掉 markdown 符号，保留纯文本 */
    private String stripMarkdown(String text) {
        return text
                .replaceAll("(?m)^#{1,6}\\s+", "")   // # 标题
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1") // **粗体**
                .replaceAll("\\*(.+?)\\*", "$1")       // *斜体*
                .replaceAll("`{1,3}[^`]*`{1,3}", "")   // 行内代码
                .replaceAll("(?m)^[-*+]\\s+", "• ")    // 无序列表
                .replaceAll("(?m)^\\d+\\.\\s+", "• ")  // 有序列表
                .replaceAll("\\n{2,}", "\n")           // 多余空行
                .trim();
    }

    private String executeCommand(String command, String description) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return "[操作完成]";
        } catch (Exception e) {
            return "[" + description + "失败: " + e.getMessage() + "]";
        }
    }

    // ==================== 假动作检测与自动重试 ====================

    /**
     * 检测 AI 回复中是否包含自己编造的假动作描述。
     * 两层检测：
     *   1. 假括号 [正在xx] vs 真标签 [小绘:xxx]
     *   2. 自然语言编造（说给了/找了东西但没有对应的 give/effect/spawn 标签）
     */
    public boolean hasFakeAction(String aiResponse) {
        if (aiResponse == null) return false;

        // 从当前回复中提取所有真实操作标签
        Set<String> actionsInResponse = new HashSet<>();
        Matcher tagMatcher = actionPattern.matcher(aiResponse);
        while (tagMatcher.find()) {
            actionsInResponse.add(tagMatcher.group(1));
        }

        boolean hasRealTag = !actionsInResponse.isEmpty();
        boolean hasFakeText = fakeActionPattern.matcher(aiResponse).find();

        // 层1：有假括号 [正在xx] 但没有真标签 [小绘:xxx]
        if (!hasRealTag && hasFakeText) return true;

        // 层2：自然语言编造（说了给了/找了/拿了东西但没写对应标签）
        if (fabricatedClaimPattern.matcher(aiResponse).find()) {
            if (!actionsInResponse.contains("give")
                    && !actionsInResponse.contains("spawn")
                    && !actionsInResponse.contains("effect")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 构造重试反馈消息
     */
    public OpenAIService.ChatMessage buildRetryFeedback(String originalResponse) {
        String suspicious = extractSuspiciousParts(originalResponse);
        return OpenAIService.ChatMessage.system(
            "你上一轮回复被拦截了，因为你编造了没有实际执行的操作。\n\n" +
            "检测到的问题：\n" +
            suspicious + "\n" +
            "请严格遵守以下规则重新生成回复：\n" +
            "1. 你说你给了物品 → 必须写 [" + agentName + ":give:物品id:数量]\n" +
            "2. 你说你设置了时间/天气 → 必须写 [" + agentName + ":time:值] 或 [" + agentName + ":weather:类型]\n" +
            "3. 你说你加了效果 → 必须写 [" + agentName + ":effect:名称:秒数:等级]\n" +
            "4. 不要说「箱子里翻到了」「明明刚才给你了」「就在你身上」这些编造的内容\n" +
            "5. 只有写标签执行成功后系统才会显示结果。不要自己编造操作结果！\n\n" +
            "一句话：做了才说，没做别说。现在请重新生成正确的回复。"
        );
    }

    private String extractSuspiciousParts(String response) {
        StringBuilder sb = new StringBuilder();
        Matcher m1 = fakeActionPattern.matcher(response);
        while (m1.find()) sb.append("  → ").append(m1.group()).append("\n");
        Matcher m2 = fabricatedClaimPattern.matcher(response);
        while (m2.find()) sb.append("  → 编造声明: ").append(m2.group()).append("\n");
        return sb.length() > 0 ? sb.toString() : "(自动检测)";
    }
}
