package org.agent.agent.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 命名位置管理器：保存/删除/列出玩家的自定义位置
 */
public class LocationManager {

    private final JavaPlugin plugin;
    private final Map<UUID, Map<String, Location>> playerLocations = new ConcurrentHashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;

    public LocationManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadFromDisk();
    }

    private void loadFromDisk() {
        dataFile = new File(plugin.getDataFolder(), "locations.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "无法创建位置文件", e);
                return;
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        playerLocations.clear();
        for (String uuidKey : dataConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidKey);
                ConfigurationSection section = dataConfig.getConfigurationSection(uuidKey);
                if (section != null) {
                    Map<String, Location> locations = new LinkedHashMap<>();
                    for (String name : section.getKeys(false)) {
                        Location loc = section.getLocation(name);
                        if (loc != null) {
                            locations.put(name, loc);
                        }
                    }
                    if (!locations.isEmpty()) {
                        playerLocations.put(uuid, locations);
                    }
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("locations.yml 中存在无效的 UUID: " + uuidKey);
            }
        }
    }

    private void saveToDisk() {
        dataConfig = new YamlConfiguration();
        for (var playerEntry : playerLocations.entrySet()) {
            String uuidKey = playerEntry.getKey().toString();
            for (var locEntry : playerEntry.getValue().entrySet()) {
                dataConfig.set(uuidKey + "." + locEntry.getKey(), locEntry.getValue());
            }
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "无法保存位置文件", e);
        }
    }

    /**
     * 保存位置
     */
    public boolean saveLocation(UUID playerId, String name, Location location) {
        Map<String, Location> locations = playerLocations.computeIfAbsent(playerId, k -> new LinkedHashMap<>());
        locations.put(name, location.clone());
        saveToDisk();
        return true;
    }

    /**
     * 删除指定位置
     */
    public boolean removeLocation(UUID playerId, String name) {
        Map<String, Location> locations = playerLocations.get(playerId);
        if (locations == null) return false;
        boolean removed = locations.remove(name) != null;
        if (removed) {
            if (locations.isEmpty()) {
                playerLocations.remove(playerId);
            }
            saveToDisk();
        }
        return removed;
    }

    /**
     * 删除所有位置
     */
    public void removeAllLocations(UUID playerId) {
        playerLocations.remove(playerId);
        saveToDisk();
    }

    /**
     * 获取位置
     */
    public Location getLocation(UUID playerId, String name) {
        Map<String, Location> locations = playerLocations.get(playerId);
        return locations != null ? locations.get(name) : null;
    }

    /**
     * 获取所有位置名称列表
     */
    public Set<String> getLocationNames(UUID playerId) {
        Map<String, Location> locations = playerLocations.get(playerId);
        return locations != null ? locations.keySet() : Set.of();
    }

    /**
     * 格式化位置信息
     */
    public String formatLocationInfo(UUID playerId, String name) {
        Location loc = getLocation(playerId, name);
        if (loc == null) return null;

        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "未知世界";
        String dimension = "主世界";
        if (loc.getWorld() != null) {
            dimension = switch (loc.getWorld().getEnvironment()) {
                case NETHER -> "下界";
                case THE_END -> "末地";
                default -> "主世界";
            };
        }
        return String.format("%s: %.0f, %.0f, %.0f (%s/%s)",
                name, loc.getX(), loc.getY(), loc.getZ(), dimension, worldName);
    }

    /**
     * 获取所有位置的格式化列表
     */
    public List<String> listLocations(UUID playerId) {
        Map<String, Location> locations = playerLocations.get(playerId);
        if (locations == null || locations.isEmpty()) return List.of();

        List<String> result = new ArrayList<>();
        for (var entry : locations.entrySet()) {
            result.add(formatLocationInfo(playerId, entry.getKey()));
        }
        return result;
    }

    /**
     * 在指定半径内查找附近地标（跨玩家）
     * @param center 中心位置
     * @param radius 搜索半径（格）
     * @return 有限数量的附近地标列表
     */
    public List<String> findNearbyLandmarks(Location center, int radius) {
        List<String> result = new ArrayList<>();
        double radiusSq = radius * radius;
        for (var playerEntry : playerLocations.entrySet()) {
            for (var locEntry : playerEntry.getValue().entrySet()) {
                Location loc = locEntry.getValue();
                if (loc.getWorld() == null || center.getWorld() == null) continue;
                if (!loc.getWorld().equals(center.getWorld())) continue;
                try {
                    double dx = loc.getX() - center.getX();
                    double dz = loc.getZ() - center.getZ();
                    if (dx * dx + dz * dz < radiusSq) {
                        double dist = Math.sqrt(dx * dx + dz * dz);
                        String dim = switch (loc.getWorld().getEnvironment()) {
                            case NETHER -> "下界";
                            case THE_END -> "末地";
                            default -> "主世界";
                        };
                        result.add(locEntry.getKey() + " (" + dim + ", " + String.format("%.0f", dist) + "米)");
                    }
                } catch (Exception ignored) {}
                if (result.size() >= 5) return result; // 最多5个防止上下文膨胀
            }
        }
        return result;
    }
}
