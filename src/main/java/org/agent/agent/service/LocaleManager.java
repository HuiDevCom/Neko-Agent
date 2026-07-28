package org.agent.agent.service;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * 本地化管理：根据玩家客户端语言加载对应的语言包
 *
 * 支持的语言包（resources/）：
 *   messages_zh.yml       简体中文
 *   messages_zh_hant.yml  繁體中文
 *   messages_en.yml       英文（默认回退）
 *
 * 语言匹配规则：
 *   zh_cn / zh_cn_xxx -> 简体
 *   zh_tw / zh_hk / zh_mo -> 繁体
 *   其它 -> 英文
 */
public class LocaleManager {

    public enum Lang {
        ZH("zh", "zh"),
        ZH_HANT("zh_hant", "zh_hant"),
        EN("en", "en");

        public final String code;
        public final String fileCode;

        Lang(String code, String fileCode) {
            this.code = code;
            this.fileCode = fileCode;
        }
    }

    private final JavaPlugin plugin;
    private static final Map<JavaPlugin, LocaleManager> INSTANCES = new HashMap<>();

    public static LocaleManager getInstance(JavaPlugin plugin) {
        return INSTANCES.get(plugin);
    }

    private final Map<Lang, FileConfiguration> bundles = new HashMap<>();
    private final Map<Lang, File> runtimeFiles = new HashMap<>();

    public LocaleManager(JavaPlugin plugin) {
        this.plugin = plugin;
        INSTANCES.put(plugin, this);
        for (Lang lang : Lang.values()) {
            try {
                InputStream stream = plugin.getResource("messages_" + lang.fileCode + ".yml");
                FileConfiguration cfg;
                if (stream != null) {
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        cfg = YamlConfiguration.loadConfiguration(reader);
                    }
                } else {
                    File file = new File(plugin.getDataFolder(), "messages_" + lang.fileCode + ".yml");
                    if (!file.exists()) {
                        plugin.saveResource("messages_" + lang.fileCode + ".yml", false);
                    }
                    cfg = YamlConfiguration.loadConfiguration(file);
                    runtimeFiles.put(lang, file);
                }
                bundles.put(lang, cfg);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "加载语言包失败: " + lang, e);
            }
        }
    }

    /**
     * 把 Bukkit locale（zh_cn, zh_tw...）归类为内部三种语言
     */
    public Lang resolveLang(String locale) {
        if (locale == null) return Lang.EN;
        String l = locale.toLowerCase().replace('-', '_');
        if (l.startsWith("zh_cn")) return Lang.ZH;
        if (l.startsWith("zh_tw") || l.startsWith("zh_hk") || l.startsWith("zh_mo")) return Lang.ZH_HANT;
        return Lang.EN;
    }

    public Lang resolveLang(Player player) {
        try {
            return resolveLang(player.getLocale());
        } catch (Exception e) {
            return Lang.EN;
        }
    }

    /**
     * 给 CommandSender 解析语言（控制台默认英文）
     */
    public Lang resolveLang(CommandSender sender) {
        if (sender instanceof Player p) return resolveLang(p);
        return Lang.EN;
    }

    /**
     * 获取本地化文案（无参数版本）
     */
    public String get(Lang lang, String key) {
        FileConfiguration cfg = bundles.get(lang);
        if (cfg == null) cfg = bundles.get(Lang.EN);
        String value = cfg == null ? null : cfg.getString(key);
        if (value == null) {
            // 回退到 en
            if (lang != Lang.EN) {
                FileConfiguration en = bundles.get(Lang.EN);
                value = en == null ? null : en.getString(key);
            }
        }
        return value != null ? value : "[missing:" + key + "]";
    }

    /**
     * 获取本地化文案（带参数替换 + 全局服务器占位符）
     *
     * 可用占位符（在任意语言包中均可使用）：
     *   {server_name}     - 服务器名称（config.server_name）
     *   {agent_name}      - Agent 名字（config.agent.name）
     *   {server_online}   - 当前在线玩家数
     *   {server_max}      - 服务器最大玩家数
     *   {version}         - 插件版本
     */
    public String format(Lang lang, String key, Object... args) {
        String template = get(lang, key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return replaceServerPlaceholders(template);
    }

    /**
     * 替换全部全局服务器占位符
     */
    private String replaceServerPlaceholders(String text) {
        if (!text.contains("{")) return text; // 快速跳过
        try {
            String serverName = plugin.getConfig().getString("server_name", "Minecraft Server");
            String agentName = plugin.getConfig().getString("agent.name", "Neko-Agent");
            int online = org.bukkit.Bukkit.getOnlinePlayers().size();
            int max = org.bukkit.Bukkit.getMaxPlayers();
            String version = plugin.getDescription().getVersion();

            text = text.replace("{server_name}", serverName)
                       .replace("{agent_name}", agentName)
                       .replace("{server_online}", String.valueOf(online))
                       .replace("{server_max}", String.valueOf(max))
                       .replace("{version}", version);
        } catch (Exception e) {
            // 某些占位符获取可能抛异常（Bukkit 未初始化等），静默
        }
        return text;
    }

    public String format(Player player, String key, Object... args) {
        return format(resolveLang(player), key, args);
    }

    /**
     * 给 CommandSender 解析语言后格式化（控制台默认英文）
     */
    public String format(CommandSender sender, String key, Object... args) {
        return format(resolveLang(sender), key, args);
    }
}
