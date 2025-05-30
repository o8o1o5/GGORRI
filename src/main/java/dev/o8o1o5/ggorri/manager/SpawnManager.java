package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
                Location spawnLoc = findSafeSpawnLocation(gameWorld, (int) INITIAL_BORDER_SIZE, 100);
                if (spawnLoc == null) {
                    plugin.getLogger().warning("[GGORRI] 플레이어 " + player.getName() + "를 위한 안전한 스폰 위치를 찾지 못했습니다.");
                    spawnLoc = gameWorld.getSpawnLocation();
                    player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 스폰 위치를 찾지 못해 월드 스폰으로 이동합니다.");
                }

                player.teleport(spawnLoc);
                player.sendMessage(ChatColor.GREEN + "[GGORRI] 텔레포트 완료!");

                plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + "로 스폰되었습니다.");
            } else {
                plugin.getLogger().warning("[GGORRI] 게임 참가 플레이어(" + playerUUID + ")가 오프라인 상태입니다. 스폰되지 않았습니다.");
            }
        }
        plugin.getLogger().info("[GGORRI] 모든 참가 플레이어 스폰 완료");
    }

    /**
     * 지정된 월드의 무작위 안전한 스폰 위치를 찾습니다.
     * 월드의 경계 내에서만 작동합니다.
     *
     * @param world 스폰 위치를 찾을 월드
     * @param range 월드 중심에서 스폰 위치를 찾을 최대 범위 (X, Z 축)
     * @param attempts 시도 횟수
     * @return 안전한 스폰 위치, 찾지 못하면 null
     */
    public Location findSafeSpawnLocation(World world, int range, int attempts) {
        for (int i = 0; i < attempts; i++) {
            int x = random.nextInt(range * 2) - range; // -range ~ +range
            int z = random.nextInt(range * 2) - range; // -range ~ +range

            Location potentialLoc = new Location(world, x, 0, z); // Y 좌표는 나중에 찾음
            Location safeLoc = findSafeY(potentialLoc);
            if (safeLoc != null) {
                return safeLoc;
            }
        }
        plugin.getLogger().warning("[GGORRI] 안전한 스폰 위치를 찾지 못했습니다. 월드: " + world.getName());
        return null;
    }

    /**
     * **[추가된 메서드]** 특정 Location 주변에서 안전한 스폰 위치를 찾습니다.
     *
     * @param centerLocation 스폰 위치를 찾을 중심 Location (팀장 위치)
     * @param maxDistance 중심 위치로부터의 최대 거리 (X, Z 축)
     * @param minDistance 중심 위치로부터의 최소 거리 (X, Z 축, 0이면 무시)
     * @return 안전한 스폰 위치, 찾지 못하면 null
     */
    public Location findSafeSpawnLocation(Location centerLocation, int maxDistance, int minDistance) {
        if (centerLocation == null || centerLocation.getWorld() == null) {
            plugin.getLogger().warning("[GGORRI] findSafeSpawnLocation: 유효하지 않은 중심 Location.");
            return null;
        }

        World world = centerLocation.getWorld();
        int centerX = centerLocation.getBlockX();
        int centerZ = centerLocation.getBlockZ();

        for (int i = 0; i < 100; i++) { // 100번 시도
            int dx = random.nextInt(maxDistance * 2) - maxDistance; // -maxDistance ~ +maxDistance
            int dz = random.nextInt(maxDistance * 2) - maxDistance; // -maxDistance ~ +maxDistance

            // 최소 거리 검사
            if (minDistance > 0) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < minDistance) {
                    continue; // 최소 거리보다 가까우면 다시 시도
                }
            }

            int x = centerX + dx;
            int z = centerZ + dz;

            // 월드 경계 검사 (월드 설정에 따라 필요할 수 있음)
            // 예를 들어, 월드 보더를 사용한다면:
            // WorldBorder border = world.getWorldBorder();
            // if (!border.isInside(new Location(world, x, 0, z))) {
            //     continue;
            // }

            Location potentialLoc = new Location(world, x, 0, z); // Y 좌표는 나중에 찾음
            Location safeLoc = findSafeY(potentialLoc);
            if (safeLoc != null) {
                return safeLoc;
            }
        }
        plugin.getLogger().warning("[GGORRI] 팀장 근처 안전한 스폰 위치를 찾지 못했습니다. 중심: " + centerLocation);
        return null;
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

        Block blockBelow = loc.clone().subtract(0, 1, 0).getBlock();
        Block blockAt = loc.getBlock();
        Block blockAbove = loc.clone().add(0, 1, 0).getBlock();

        if (!blockBelow.getType().isSolid() || blockBelow.getType() == Material.LAVA || blockBelow.getType() == Material.WATER) {
            return false;
        }

        if (blockAt.getType().isSolid() || blockAbove.getType().isSolid()) {
            return false;
        }

        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    Block nearbyBlock = loc.clone().add(xOffset, yOffset, zOffset).getBlock();
                    Material mat = nearbyBlock.getType();
                    if (mat == Material.LAVA || mat == Material.WATER || mat == Material.CACTUS || mat == Material.MAGMA_BLOCK) {
                        return false;
                    }
                }
            }
        }

        boolean foundGroundBelow = false;
        for (int y = -1; y >= -5; y--) {
            if (loc.clone().add(0, y, 0).getBlock().getType().isSolid()) {
                foundGroundBelow = true;
                break;
            }
        }
        if (!foundGroundBelow) {
            return false;
        }

        return true;
    }

    /**
     * 주어진 X, Z 좌표에서 안전한 Y 좌표를 찾아 완전한 Location 객체를 반환합니다.
     *
     * @param loc X, Z 좌표가 설정된 Location 객체
     * @return 안전한 스폰 Location, 찾지 못하면 null
     */
    private Location findSafeY(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;

        // 월드의 최고 높이부터 내려오면서 안전한 블록 찾기
        for (int y = world.getMaxHeight() - 1; y > world.getMinHeight(); y--) {
            Block block = new Location(world, loc.getX(), y, loc.getZ()).getBlock();
            Block belowBlock = block.getRelative(BlockFace.DOWN);
            Block aboveBlock = block.getRelative(BlockFace.UP);

            // 1. 현재 블록이 공기 또는 통과 가능한 블록
            // 2. 아래 블록이 단단한 블록 (발 딛을 곳)
            // 3. 위 블록도 공기 또는 통과 가능한 블록 (플레이어가 서 있을 공간)
            if (!block.getType().isSolid() && !block.isLiquid() &&
                    belowBlock.getType().isSolid() && !belowBlock.isLiquid() &&
                    !aboveBlock.getType().isSolid() && !aboveBlock.isLiquid()) {
                // 안전한 스폰 위치 발견, 플레이어가 서 있을 수 있도록 약간 위로
                return block.getLocation().add(0.5, 1.0, 0.5);
            }
        }
        return null;
    }

    /**
     * 게임 월드 객체를 반환합니다.
     * @return 게임 월드
     */
    public World getGameWorld() {
        return gameWorld;
    }

    public int getWorldBorderSize() {
        return (int) INITIAL_BORDER_SIZE;
    }
}