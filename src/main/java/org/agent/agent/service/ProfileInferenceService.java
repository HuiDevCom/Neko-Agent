package org.agent.agent.service;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 行为推断 —— 根据玩家状态数据自动生成画像记忆
 * 每次 buildMessages 时调用 infer()，自动记录到 PlayerMemoryManager
 */
public class ProfileInferenceService {

    private final JavaPlugin plugin;
    private final PlayerMemoryManager memoryManager;

    // 防止每轮都重复推断相同内容
    private final Set<UUID> inferredArmor = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> inferredRedstone = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> inferredNight = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> inferredPvp = Collections.synchronizedSet(new HashSet<>());

    public ProfileInferenceService(JavaPlugin plugin, PlayerMemoryManager memoryManager) {
        this.plugin = plugin;
        this.memoryManager = memoryManager;
    }

    /**
     * 对玩家执行一轮行为推断（每次对话时调用一次即可）
     */
    public void infer(Player player) {
        UUID id = player.getUniqueId();

        // 1. 装备阶段推断
        if (!inferredArmor.contains(id)) {
            ItemStack chest = player.getInventory().getChestplate();
            if (chest != null && chest.getType().name().contains("NETHERITE")) {
                memoryManager.addMemory(id, MemoryEntry.Type.FACT,
                        "玩家已进入装备毕业阶段，拥有下界合金套", 3);
                inferredArmor.add(id);
            } else if (chest != null && chest.getType().name().contains("DIAMOND")) {
                memoryManager.addMemory(id, MemoryEntry.Type.SKILL,
                        "玩家有钻石装备，生存能力较强", 3);
                inferredArmor.add(id);
            }
        }

        // 2. 红石爱好者推断
        if (!inferredRedstone.contains(id)) {
            int redstoneCount = countItems(player, "REDSTONE", "REPEATER", "COMPARATOR", "PISTON", "OBSERVER");
            if (redstoneCount >= 8) {
                memoryManager.addMemory(id, MemoryEntry.Type.PREFERENCE,
                        "红石爱好者，背包里有大量红石元件", 3).tag("redstone");
                inferredRedstone.add(id);
            }
        }

        // 3. 夜猫子推断（根据本地服务器时间）
        if (!inferredNight.contains(id)) {
            long time = player.getWorld().getTime();
            boolean isNight = time >= 13000 && time < 23000;
            if (isNight) {
                memoryManager.addMemory(id, MemoryEntry.Type.HABIT,
                        "夜猫子玩家，常在夜晚活跃", 2).tag("night");
                inferredNight.add(id);
            }
        }

        // 4. PVP 倾向推断
        if (!inferredPvp.contains(id)) {
            int weaponCount = countItems(player, "SWORD", "BOW", "CROSSBOW", "TRIDENT", "AXE");
            if (weaponCount >= 3) {
                memoryManager.addMemory(id, MemoryEntry.Type.PREFERENCE,
                        "PVP 爱好者，携带多种武器", 3).tag("pvp");
                inferredPvp.add(id);
            }
        }
    }

    private int countItems(Player player, String... keywords) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            String name = item.getType().name();
            for (String kw : keywords) {
                if (name.contains(kw)) {
                    count += item.getAmount();
                    break;
                }
            }
        }
        return count;
    }
}
