package org.agent.agent.service;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器状态查询：TPS / MSPT / 在线玩家 / 已加载世界
 *
 * Paper 专用 API（getTPS / getAverageTickTime）有 try-catch 保护，
 * 在 Spigot/CraftBukkit 上也能正常工作（覆盖降级为默认值）
 */
public class ServerStatusService {

    /**
     * 格式化服务器状态为提示词可用的多行文本
     */
    public String getServerStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 服务器状态 ===\n");

        // Paper API 有 try-catch 保护
        double[] tps = new double[]{20.0, 20.0, 20.0};
        double mspt = 50.0;
        try {
            tps = Bukkit.getServer().getTPS();
            mspt = Bukkit.getServer().getAverageTickTime();
        } catch (Throwable e) {
            sb.append("(服务器版本不兼容，无法获取 TPS)\n");
        }

        if (tps.length >= 3) {
            double tps5s = tps[1];
            String tpsStatus;
            if (tps5s >= 19.5) tpsStatus = "（流畅）";
            else if (tps5s >= 18.0) tpsStatus = "（良好）";
            else if (tps5s >= 15.0) tpsStatus = "（轻微卡顿）";
            else tpsStatus = "（严重卡顿）";

            sb.append("TPS (1s/5s/15s): ").append(formatTps(tps[0]))
                    .append(" / ").append(formatTps(tps[1])).append(tpsStatus)
                    .append(" / ").append(formatTps(tps[2])).append("\n");
        }
        sb.append("MSPT (毫秒/tick): ").append(String.format("%.1f", mspt)).append("\n");

        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        sb.append("在线玩家: ").append(online).append(" / ").append(max).append("\n");

        List<String> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            worlds.add(w.getName() + "（" + w.getPlayers().size() + " 人）");
        }
        sb.append("已加载世界: ").append(String.join(", ", worlds)).append("\n");

        return sb.toString();
    }

    private String formatTps(double tps) {
        return String.format("%.1f", tps);
    }
}
