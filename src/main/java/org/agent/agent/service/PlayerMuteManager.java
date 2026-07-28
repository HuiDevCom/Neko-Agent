package org.agent.agent.service;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 玩家静音管理 —— 玩家可关闭小绘的主动打扰（欢迎/死亡/成就等仍保留）
 */
public class PlayerMuteManager {

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Set<UUID> mutedPlayers = Collections.synchronizedSet(new HashSet<>());

    public PlayerMuteManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "muted.yml");
        load();
    }

    private void load() {
        if (!dataFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : config.getStringList("muted")) {
            try {
                mutedPlayers.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        FileConfiguration config = new YamlConfiguration();
        config.set("muted", mutedPlayers.stream().map(UUID::toString).toList());
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "无法保存静音列表", e);
        }
    }

    public boolean isMuted(UUID playerId) {
        return mutedPlayers.contains(playerId);
    }

    /** 返回切换后的状态：true=已静音 */
    public boolean toggle(UUID playerId) {
        if (mutedPlayers.contains(playerId)) {
            mutedPlayers.remove(playerId);
            save();
            return false;
        } else {
            mutedPlayers.add(playerId);
            save();
            return true;
        }
    }
}
