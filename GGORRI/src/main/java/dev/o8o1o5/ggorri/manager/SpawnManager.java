package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

public class SpawnManager {
    private final GGORRI plugin;
    private World gameWorld;
    private final Random random;

    private static final double INITIAL_BORDER_SIZE = 3200.0;

    public SpawnManager(GGORRI plugin) {
        this.plugin = plugin;
        this.random = new Random();
        this.gameWorld = plugin.getServer().getWorld("world");
        if (this.gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드를 찾을 수 없습니다!");
        }
    }

    /**
     * 게임 월드의 WorldBorder를 초기 설정합니다.
     * @param world 설정할 월드
     */
    public void setupWorldBorder(World world) {
        this.gameWorld = world;
        if (gameWorld == null) return;

        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(INITIAL_BORDER_SIZE);
        border.setWarningDistance(0); // 2.4
        border.setDamageAmount(0.0); // 2.4

        plugin.getLogger().info("[GGORRI] 게임 월드보더가 설정되었습니다.");
    }

    /**
     * 게임 시작 시 플레이어 스폰 및 초기화 로직
     * @param playerUUIDs 스폰시킬 플레이어들의 UUID 목록
     */
    public void spawnPlayers(List<UUID> playerUUIDs) {
        if (gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드가 설정되지 않아 플레이어를 스폰할 수 없습니다!");
            playerUUIDs.forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(ChatColor.RED + "[GGORRI] 게임 월드가 설정되지 않아 게임 시작에 문제가 발생했습니다.");
                }
            });
            return;
        }

        WorldBorder border = gameWorld.getWorldBorder();
        if (border == null || border.getSize() <= 0) {
            plugin.getLogger().severe("[GGORRI] 월드 보더가 유효하지 않거나 크기가 0입니다.");
            playerUUIDs.forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) p.sendMessage(ChatColor.RED + "[GGORRI] 월드 보더 설정에 문제가 발생했습니다.");
            });
            return;
        }

        for (UUID playerUUID : playerUUIDs) {
            Player player = plugin.getServer().getPlayer(playerUUID);
            if (player != null) {
                Location spawnLoc = findSafeSpawnLocation()
            }
        }
    }

    /**
     * 주어진 월드의 WorldBorder 내에서 안전한 스폰 위치를 찾습니다.
     * @param world 스폰 위치를 찾을 월드
     * @param attempts 시도 횟수
     * @return 안전한 Location 객체 또는 null (찾지 못했을 경우)
     */
    public Location findSafeSpawnLocation(World world, int attempts) {
        if (world == null) {
            plugin.getLogger().severe("[GGORRI] findSafeSpawnLocation: 월드가 null 입니다.");
            return null;
        }
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double halfSize = border.getSize() / 2.0;

        plugin.getLogger().log(Level.FINE, "[GGORRI] 안전한 스폰 위치 찾기 시작 (" + attempts + "회)");
        plugin.getLogger().log(Level.FINE, "[GGORRI] 월드 보더 중심: (" + center.getX() + ", " + center.getZ() + ") | 크기: " + border.getSize());

        for (int i = 0; i < attempts; i++) {
            double x = center.getX() + (random.nextDouble() * 2 - 1) * halfSize;
            double z = center.getZ() + (random.nextDouble() * 2 - 1) * halfSize;

            int y = world.getHighestBlockYAt((int) x, (int) z);
            Location potentialSpawn = new Location(world, x + 0.5, y + 1, z + 0.5);

            if (isSafeLocation())
        }
    }

    /**
     * 주어진 위치가 안전한 스폰 위치인지 검사합니다.
     * @param loc 검사할 위치
     * @return 안전하면 true, 그렇지 않으면 false
     */
    private boolean isSafeSpawnLocation(Location loc) {
        if (!loc.getWorld().getWorldBorder().isInside(loc)) {
            plugin.getLogger().log(Level.FINE, "[GGORRI] WorldBorder 밖: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            return false;
        }


    }
}
