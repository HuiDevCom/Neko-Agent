package org.agent.agent.service;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * 玩家状态查询服务：状态、背包、环境、规则、实体扫描
 */
public class PlayerQueryService {

    /**
     * 客户端信息：语言、客户端种类、协议版本（推算 MC 版本）
     */
    public String getClientInfo(Player player) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 客户端信息 ===\n");

        // 客户端语言（如 zh_cn / en_us）
        try {
            String locale = player.getLocale();
            sb.append("客户端语言: ").append(formatLocale(locale)).append("\n");
        } catch (Exception e) {
            sb.append("客户端语言: 未知\n");
        }

        // 客户端品牌（vanilla / fabric / forge / quilt / paper / ...）
        try {
            String brand = player.getClientBrandName();
            sb.append("客户端品牌: ").append(brand == null ? "vanilla" : brand).append("\n");
        } catch (Throwable e) {
            sb.append("客户端品牌: unknown\n");
        }

        // 协议版本 → MC 版本（Paper 专用 NoSuchMethodError 保护）
        try {
            int protocol = player.getProtocolVersion();
            sb.append("协议版本: ").append(protocol)
                    .append(" (≈ ").append(protocolToMcVersion(protocol)).append(")\n");
        } catch (Throwable e) {
            sb.append("协议版本: 未知\n");
        }

        // Ping
        try {
            sb.append("延迟(Ping): ").append(player.getPing()).append(" ms\n");
        } catch (Throwable e) {
            // 旧 API 兼容
        }

        // 视距（服务端可能限制过）
        try {
            int viewDistance = player.getViewDistance();
            sb.append("视距设置: ").append(viewDistance).append(" 格\n");
        } catch (Throwable e) {
            // 客户端未提供
        }

        return sb.toString();
    }

    private static String formatLocale(String locale) {
        if (locale == null) return "unknown";
        return switch (locale.toLowerCase()) {
            case "zh_cn", "zhcn" -> "简体中文 (zh_cn)";
            case "zh_tw", "zhtw" -> "繁體中文 (zh_tw)";
            case "zh_hk" -> "香港中文 (zh_hk)";
            case "en_us", "enus" -> "English (US)";
            case "en_gb", "engb" -> "English (UK)";
            case "ja_jp", "jajp" -> "日本語 (ja_jp)";
            case "ko_kr", "kokr" -> "한국어 (ko_kr)";
            case "fr_fr", "frfr" -> "Français (fr_fr)";
            case "de_de", "dede" -> "Deutsch (de_de)";
            case "ru_ru", "ruru" -> "Русский (ru_ru)";
            default -> locale;
        };
    }

    /**
     * 协议版本号 → Minecraft 版本号
     */
    private static String protocolToMcVersion(int protocol) {
        // 协议版本对应表（持续更新）
        return switch (protocol) {
            case 770 -> "1.21.5";
            case 769 -> "1.21.4";
            case 768 -> "1.21.3";
            case 767 -> "1.21.2";
            case 766 -> "1.21.1";
            case 765 -> "1.21 (1.21.0)";
            case 764 -> "1.20.5/1.20.6";
            case 763 -> "1.20.4";
            case 762 -> "1.20.3";
            case 761 -> "1.20.2";
            case 760 -> "1.20.1";
            case 759 -> "1.20";
            case 758 -> "1.18/1.18.2";
            case 757 -> "1.18/1.18.1";
            case 756 -> "1.17.1";
            case 755 -> "1.17";
            case 754 -> "1.16.4/1.16.5";
            case 753 -> "1.16.3";
            case 751 -> "1.16.2";
            case 736 -> "1.16.1";
            case 735 -> "1.16";
            case 580 -> "1.13.2";
            default -> "未知";
        };
    }

    /**
     * 玩家状态信息
     */
    public String getPlayerStatus(Player player) {
        Location loc = player.getLocation();
        Location lastDeath = player.getLastDeathLocation();

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(player.getName()).append(" 的状态 ===\n");
        sb.append("位置: ").append(formatLocation(loc)).append("\n");
        sb.append("维度: ").append(formatWorld(loc.getWorld())).append("\n");
        sb.append("生命值: ").append(String.format("%.0f/%.0f", player.getHealth(), player.getMaxHealth())).append("\n");
        sb.append("饥饿值: ").append(player.getFoodLevel()).append("/20\n");
        sb.append("经验等级: ").append(player.getLevel()).append(" (").append(player.getExp()).append(")\n");
        sb.append("游戏模式: ").append(formatGameMode(player.getGameMode())).append("\n");
        sb.append("飞行状态: ").append(player.isFlying() ? "飞行中" : "未飞行").append("\n");
        sb.append("是否OP: ").append(player.isOp() ? "是" : "否").append("\n");

        if (lastDeath != null) {
            sb.append("最后死亡地点: ").append(formatLocation(lastDeath)).append("\n");
        } else {
            sb.append("最后死亡地点: 无记录\n");
        }

        return sb.toString();
    }

    /**
     * 背包概览
     */
    public String getInventoryOverview(Player player) {
        PlayerInventory inv = player.getInventory();
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(player.getName()).append(" 的背包 ===\n");

        // 主手
        ItemStack mainHand = inv.getItemInMainHand();
        sb.append("主手: ").append(formatItem(mainHand)).append("\n");

        // 副手
        ItemStack offHand = inv.getItemInOffHand();
        sb.append("副手: ").append(formatItem(offHand)).append("\n");

        // 护甲
        sb.append("护甲:\n");
        sb.append("  头盔: ").append(formatItem(inv.getHelmet())).append("\n");
        sb.append("  胸甲: ").append(formatItem(inv.getChestplate())).append("\n");
        sb.append("  护腿: ").append(formatItem(inv.getLeggings())).append("\n");
        sb.append("  靴子: ").append(formatItem(inv.getBoots())).append("\n");

        // 背包内容统计
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) {
                emptySlots++;
            } else {
                String name = item.getType().name();
                itemCounts.merge(name, item.getAmount(), Integer::sum);
            }
        }

        sb.append("背包物品 (").append(36 - emptySlots).append("/36 已占用):\n");
        if (itemCounts.isEmpty()) {
            sb.append("  (空)\n");
        } else {
            for (var entry : itemCounts.entrySet()) {
                sb.append("  - ").append(formatMaterialName(entry.getKey()))
                        .append(" x").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 环境监测（含 P0 动态感知：脚下方块、头顶方块、Y层语义、光照来源、药水效果）
     */
    public String getEnvironmentInfo(Player player) {
        World world = player.getWorld();
        Location loc = player.getLocation();
        Block footBlock = loc.getBlock();
        Block headBlock = loc.clone().add(0, 1, 0).getBlock();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 环境信息 ===\n");
        sb.append("维度: ").append(formatWorld(world)).append("\n");
        sb.append("生物群系: ").append(formatBiome(footBlock.getBiome())).append("\n");
        sb.append("时间: ").append(formatTime(world.getTime())).append("\n");
        sb.append("天气: ").append(world.isThundering() ? "雷暴" :
                world.hasStorm() ? "下雨" : "晴天").append("\n");
        sb.append("难度: ").append(formatDifficulty(world.getDifficulty())).append("\n");
        sb.append("朝向: ").append(formatFacing(loc.getYaw())).append("\n");
        sb.append("坐标: ").append(formatLocation(loc)).append(" (Y层: ").append(getYLevelDescription(loc.getBlockY())).append(")\n");

        // 光照来源
        int skyLight = footBlock.getLightFromSky();
        int blockLight = footBlock.getLightFromBlocks();
        String lightSrc = skyLight > blockLight ? "露天/自然光" : (blockLight > 4 ? "火把/人工光源" : "较暗");
        sb.append("光照: ").append(footBlock.getLightLevel()).append(" (").append(lightSrc).append(", 天光").append(skyLight).append(", 块光").append(blockLight).append(")\n");

        // 脚下 + 头顶方块
        String footDesc = describeBlock(footBlock);
        String headDesc = describeBlock(headBlock);
        sb.append("脚下方块: ").append(footDesc);
        if (footBlock.getType().isSolid()) {
            sb.append(" (硬度 ").append(footBlock.getType().getHardness()).append(")");
        }
        sb.append("\n");
        sb.append("头顶方块: ").append(headDesc).append("\n");

        // 环境判断
        boolean isUnderwater = player.isInWater();
        boolean isInLava = footBlock.getType() == Material.LAVA;
        boolean isHighUp = loc.getBlockY() > 100 && footBlock.getLightFromSky() > 10;
        boolean isUnderground = loc.getBlockY() < 50 && skyLight == 0;
        boolean isCave = isUnderground && blockLight < 8;
        if (isUnderwater) sb.append("⚠ 玩家在水中\n");
        if (isInLava) sb.append("⚠ 玩家站在岩浆中！危险！\n");
        if (isHighUp) sb.append("⚠ 玩家在高空 (").append(loc.getBlockY()).append("层)，失足即死\n");
        if (isCave) sb.append("⚠ 洞穴环境，亮度不足，小心刷怪\n");
        else if (isUnderground) sb.append("⛏ 玩家在地下层\n");

        // 药水效果
        var effects = player.getActivePotionEffects();
        if (!effects.isEmpty()) {
            sb.append("状态效果:\n");
            for (PotionEffect eff : effects) {
                sb.append("  ").append(formatPotionEffect(eff.getType()))
                        .append(" Lv").append(eff.getAmplifier() + 1)
                        .append(" (").append(eff.getDuration() / 20).append("秒)")
                        .append(eff.getAmplifier() < 0 ? " ← 负面" : "").append("\n");
            }
        }

        // 经验
        sb.append("剩余经验: ").append(player.getExpToLevel()).append(" 格到下一级");

        return sb.toString();
    }

    /**
     * 服务器游戏规则
     */
    public String getGameRules(Player player) {
        World world = player.getWorld();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 游戏规则 (").append(world.getName()).append(") ===\n");

        String[] importantRules = {
                "keepInventory", "doDaylightCycle", "doWeatherCycle",
                "doMobSpawning", "doFireTick", "mobGriefing",
                "commandBlockOutput", "sendCommandFeedback", "showDeathMessages"
        };

        for (String ruleName : importantRules) {
            try {
                @SuppressWarnings({"deprecation", "removal"})
                GameRule<?> rule = GameRule.getByName(ruleName);
                if (rule != null) {
                    Object value = world.getGameRuleValue(rule);
                    sb.append(ruleName).append(": ").append(value).append("\n");
                } else {
                    sb.append(ruleName).append(": (null)\n");
                }
            } catch (Exception e) {
                sb.append(ruleName).append(": (error: ").append(e.getMessage()).append(")\n");
            }
        }
        return sb.toString();
    }

    /**
     * 附近全部实体扫描（100格内）
     * 含威胁等级分类 + 最近危险实体距离
     */
    public String getNearbyEntities(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        StringBuilder sb = new StringBuilder();

        var nearby = world.getNearbyEntities(loc, 100, 100, 100);
        sb.append("=== 附近实体 (100格内，共 ").append(nearby.size()).append(" 个) ===\n");

        Map<String, String> players = new LinkedHashMap<>();
        Map<String, Integer> highThreat = new LinkedHashMap<>();   // 高危
        Map<String, Integer> midThreat = new LinkedHashMap<>();    // 中危
        Map<String, Integer> lowThreat = new LinkedHashMap<>();    // 低危
        Map<String, Integer> animals = new LinkedHashMap<>();
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Integer> projectiles = new LinkedHashMap<>();
        Map<String, Integer> vehicles = new LinkedHashMap<>();
        Map<String, Integer> specials = new LinkedHashMap<>();      // 特殊实体
        Map<String, Integer> misc = new LinkedHashMap<>();

        // 最近危险追踪
        String nearestThreat = null;
        double nearestThreatDist = Double.MAX_VALUE;

        for (Entity entity : nearby) {
            if (entity == player) continue;

            if (entity instanceof Player other) {
                double dist = loc.distance(other.getLocation());
                players.put(other.getName(), String.format("%.0f", dist));
                continue;
            }

            String name = formatEntityType(entity.getType());

            if (isMonster(entity)) {
                int threat = getThreatLevel(entity);
                double dist = loc.distance(entity.getLocation());
                if (dist < nearestThreatDist) {
                    nearestThreatDist = dist;
                    nearestThreat = name + " (距离 " + String.format("%.0f", dist) + " 米)";
                }

                String labeledName = switch (threat) {
                    case 3 -> name + " @高危";
                    case 2 -> name + " @中危";
                    default -> name + " @低危";
                };

                switch (threat) {
                    case 3 -> highThreat.merge(labeledName, 1, Integer::sum);
                    case 2 -> midThreat.merge(labeledName, 1, Integer::sum);
                    default -> lowThreat.merge(labeledName, 1, Integer::sum);
                }
            } else if (isSpecialEntity(entity)) {
                specials.merge(name, 1, Integer::sum);
            } else if (isAnimal(entity)) {
                animals.merge(name, 1, Integer::sum);
            } else if (isItem(entity)) {
                items.merge(name, 1, Integer::sum);
            } else if (isProjectile(entity)) {
                projectiles.merge(name, 1, Integer::sum);
            } else if (isVehicle(entity)) {
                vehicles.merge(name, 1, Integer::sum);
            } else {
                misc.merge(name, 1, Integer::sum);
            }
        }

        // 输出玩家
        if (!players.isEmpty()) {
            sb.append("[玩家]\n");
            players.forEach((name, dist) ->
                    sb.append("  ").append(name).append(" (距离 ").append(dist).append(" 米)\n"));
        }

        // 输出怪物（按威胁等级分组）
        if (!highThreat.isEmpty()) {
            sb.append("[怪物 @高危]\n");
            sortedByCount(highThreat).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }
        if (!midThreat.isEmpty()) {
            sb.append("[怪物 @中危]\n");
            sortedByCount(midThreat).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }
        if (!lowThreat.isEmpty()) {
            sb.append("[怪物 @低危]\n");
            sortedByCount(lowThreat).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        // 最近威胁
        if (nearestThreat != null) {
            sb.append("[最近威胁] ").append(nearestThreat).append("\n");
        }

        // 特殊实体
        if (!specials.isEmpty()) {
            sb.append("[特殊实体]\n");
            sortedByCount(specials).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        // 动物
        if (!animals.isEmpty()) {
            sb.append("[动物]\n");
            sortedByCount(animals).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        // 掉落物
        if (!items.isEmpty()) {
            int total = items.values().stream().mapToInt(Integer::intValue).sum();
            sb.append("[掉落物，共 ").append(total).append(" 个]\n");
            sortedByCount(items).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        if (!projectiles.isEmpty()) {
            sb.append("[投射物]\n");
            sortedByCount(projectiles).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        if (!vehicles.isEmpty()) {
            sb.append("[载具]\n");
            sortedByCount(vehicles).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        if (!misc.isEmpty()) {
            sb.append("[其他]\n");
            sortedByCount(misc).forEach(e -> sb.append("  ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }

        if (nearby.size() <= 1) {
            sb.append("（附近没有任何其他实体）\n");
        }

        return sb.toString();
    }

    private List<Map.Entry<String, Integer>> sortedByCount(Map<String, Integer> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .toList();
    }

    /**
     * 是否是怪物
     */
    private boolean isMonster(Entity entity) {
        return entity instanceof org.bukkit.entity.Monster
                || entity instanceof org.bukkit.entity.Enemy
                || entity instanceof org.bukkit.entity.Boss
                || entity instanceof org.bukkit.entity.Slime;
    }

    /**
     * 返回威胁等级：3=高危, 2=中危, 1=低危
     */
    private int getThreatLevel(Entity entity) {
        EntityType t = entity.getType();
        return switch (t) {
            case CREEPER, WARDEN, WITHER, ENDER_DRAGON -> 3;
            case SKELETON, DROWNED, BLAZE, GHAST, SHULKER, GUARDIAN,
                 ELDER_GUARDIAN, PILLAGER, VINDICATOR, EVOKER, RAVAGER,
                 BREEZE, BOGGED -> 2;
            default -> 1;
        };
    }

    /**
     * 特殊实体：守护型/交易机会/机制提醒
     */
    private boolean isSpecialEntity(Entity entity) {
        EntityType t = entity.getType();
        return t == EntityType.IRON_GOLEM
                || t == EntityType.SNOW_GOLEM
                || t == EntityType.WANDERING_TRADER
                || t == EntityType.PHANTOM;
    }

    /**
     * 是否是动物
     */
    private boolean isAnimal(Entity entity) {
        return entity instanceof org.bukkit.entity.Animals
                || entity instanceof org.bukkit.entity.Villager
                || entity instanceof org.bukkit.entity.IronGolem
                || entity instanceof org.bukkit.entity.Snowman
                || entity instanceof org.bukkit.entity.Dolphin
                || entity instanceof org.bukkit.entity.Squid
                || entity instanceof org.bukkit.entity.Turtle
                || entity instanceof org.bukkit.entity.Allay
                || entity instanceof org.bukkit.entity.Bat
                || entity instanceof org.bukkit.entity.Fish
                || entity instanceof org.bukkit.entity.Golem;
    }

    /**
     * 是否是物品/掉落物/经验球/展示类实体
     */
    private boolean isItem(Entity entity) {
        return entity instanceof org.bukkit.entity.Item
                || entity instanceof org.bukkit.entity.ExperienceOrb
                || entity instanceof org.bukkit.entity.ItemFrame
                || entity instanceof org.bukkit.entity.Painting
                || entity instanceof org.bukkit.entity.ArmorStand;
    }

    /**
     * 是否是投射物
     */
    private boolean isProjectile(Entity entity) {
        return entity instanceof org.bukkit.entity.Projectile;
    }

    /**
     * 是否是载具
     */
    private boolean isVehicle(Entity entity) {
        return entity instanceof org.bukkit.entity.Vehicle;
    }

    // ==================== P0/P1 新增辅助方法 ====================

    /** Y 坐标 → 可读地层描述 */
    public static String getYLevelDescription(int y) {
        if (y < -60) return "虚空边缘";
        if (y < -5) return "深板岩层";
        if (y < 16) return "矿脉密集层 (钻石Y层)";
        if (y < 50) return "浅层洞穴";
        if (y < 62) return "地表下层";
        if (y < 80) return "地面层";
        if (y < 120) return "丘陵";
        if (y < 200) return "山地";
        if (y < 256) return "高山";
        if (y <= 320) return "山顶";
        return "极高处";
    }

    /** 方块可读描述 */
    private String describeBlock(Block block) {
        Material m = block.getType();
        if (m.isAir()) return "空气";
        String name = formatMaterialName(m.name());
        // 特殊判断
        if (m == Material.WATER) return "水";
        if (m == Material.LAVA) return "岩浆";
        if (name.contains("log") || name.contains("wood")) return name + " (木质)";
        if (name.contains("ore")) return name + " (矿石)";
        if (name.contains("leaves")) return name + " (树叶)";
        return name;
    }

    /** 药水效果 → 中文名 */
    private String formatPotionEffect(PotionEffectType type) {
        var key = type.getKey().getKey();
        return switch (key) {
            case "speed" -> "速度";
            case "slowness" -> "缓慢";
            case "haste" -> "急迫";
            case "mining_fatigue" -> "挖掘疲劳";
            case "strength" -> "力量";
            case "instant_health" -> "瞬间治疗";
            case "instant_damage" -> "瞬间伤害";
            case "jump_boost" -> "跳跃提升";
            case "nausea" -> "反胃";
            case "regeneration" -> "生命恢复";
            case "resistance" -> "抗性提升";
            case "fire_resistance" -> "防火";
            case "water_breathing" -> "水下呼吸";
            case "invisibility" -> "隐身";
            case "blindness" -> "失明";
            case "night_vision" -> "夜视";
            case "hunger" -> "饥饿";
            case "weakness" -> "虚弱";
            case "poison" -> "中毒";
            case "wither" -> "凋零";
            case "health_boost" -> "生命提升";
            case "absorption" -> "伤害吸收";
            case "saturation" -> "饱和";
            case "glowing" -> "发光";
            case "levitation" -> "飘浮";
            case "luck" -> "幸运";
            case "unluck" -> "霉运";
            case "slow_falling" -> "缓降";
            case "conduit_power" -> "潮涌能量";
            case "dolphins_grace" -> "海豚的恩惠";
            case "bad_omen" -> "不祥之兆";
            case "hero_of_the_village" -> "村庄英雄";
            case "darkness" -> "黑暗";
            default -> key.replace("_", " ");
        };
    }

    // ==================== 格式化工具方法 ====================

    private String formatLocation(Location loc) {
        return String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());
    }

    private String formatWorld(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "下界";
            case THE_END -> "末地";
            default -> "主世界";
        };
    }

    private String formatGameMode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> "生存";
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
        };
    }

    private String formatDifficulty(Difficulty diff) {
        return switch (diff) {
            case PEACEFUL -> "和平";
            case EASY -> "简单";
            case NORMAL -> "普通";
            case HARD -> "困难";
        };
    }

    private String formatTime(long time) {
        long hours = (time / 1000 + 6) % 24;
        long minutes = (time % 1000) * 60 / 1000;
        String period;
        if (time < 13000) period = "白天";
        else if (time < 13800) period = "日落";
        else if (time < 22200) period = "夜晚";
        else period = "日出";
        return String.format("%02d:%02d (%s)", hours, minutes, period);
    }

    private String formatBiome(Biome biome) {
        @SuppressWarnings({"deprecation", "removal"})
        String name = biome.name();
        return name.toLowerCase().replace("_", " ");
    }

    private String formatFacing(float yaw) {
        double rotation = (yaw + 360) % 360;
        if (rotation < 45) return "南";
        if (rotation < 135) return "西";
        if (rotation < 225) return "北";
        if (rotation < 315) return "东";
        return "南";
    }

    private String formatItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return "(空)";
        String name = formatMaterialName(item.getType().name());
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            name = item.getItemMeta().getDisplayName();
        }
        return name + " x" + item.getAmount();
    }

    private String formatEntityType(EntityType type) {
        return switch (type) {
            case ZOMBIE -> "僵尸";
            case SKELETON -> "骷髅";
            case CREEPER -> "苦力怕";
            case SPIDER -> "蜘蛛";
            case ENDERMAN -> "末影人";
            case WITCH -> "女巫";
            case SLIME -> "史莱姆";
            case PIG -> "猪";
            case COW -> "牛";
            case SHEEP -> "羊";
            case CHICKEN -> "鸡";
            case HORSE -> "马";
            case VILLAGER -> "村民";
            case IRON_GOLEM -> "铁傀儡";
            case BAT -> "蝙蝠";
            case BEE -> "蜜蜂";
            case FOX -> "狐狸";
            case WOLF -> "狼";
            case CAT -> "猫";
            case AXOLOTL -> "美西螈";
            case DROWNED -> "溺尸";
            case PHANTOM -> "幻翼";
            case PILLAGER -> "掠夺者";
            case BLAZE -> "烈焰人";
            case GHAST -> "恶魂";
            case MAGMA_CUBE -> "岩浆怪";
            case WITHER_SKELETON -> "凋零骷髅";
            case PIGLIN -> "猪灵";
            case HOGLIN -> "疣猪兽";
            case SHULKER -> "潜影贝";
            case GUARDIAN -> "守卫者";
            default -> type.name().toLowerCase();
        };
    }

    private String formatMaterialName(String name) {
        return name.toLowerCase().replace("_", " ");
    }
}
