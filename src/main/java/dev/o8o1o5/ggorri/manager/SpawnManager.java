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
    private final PlayerManager playerManager;
    private World gameWorld;
    private final Random random;

    private static final double INITIAL_BORDER_SIZE = 3200.0;
    private static final int MAX_SPAWN_ATTEMPTS = 200; // 스폰 위치 탐색 최대 시도 횟수
    private static final int MIN_SPAWN_Y = 63; // 최소 스폰 Y 좌표 (바다 수면보다 위)

    public SpawnManager(GGORRI plugin, PlayerManager playerManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.random = new Random();
        this.gameWorld = plugin.getServer().getWorld("world"); // 또는 config에서 월드 이름 로드
        if (this.gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드 'world'를 찾을 수 없습니다! 서버 설정 또는 플러그인 로딩 순서를 확인하세요.");
        }
    }

    /**
     * 게임 월드의 WorldBorder를 초기 설정합니다.
     * 이 메서드는 게임 시작 시 또는 서버 시작 시 호출되어야 합니다.
     * @param world 설정할 월드 (일반적으로 GGORRI 게임이 진행될 월드)
     */
    public void setupWorldBorder(World world) {
        if (world == null) {
            plugin.getLogger().severe("[GGORRI] WorldBorder를 설정할 월드가 null입니다.");
            return;
        }
        this.gameWorld = world;

        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(INITIAL_BORDER_SIZE);
        border.setWarningDistance(0);
        border.setDamageAmount(0.0);

        plugin.getLogger().info("[GGORRI] 게임 월드보더가 " + world.getName() + " 에 설정되었습니다. 크기: " + INITIAL_BORDER_SIZE);
    }

    /**
     * 게임 시작 시 플레이어들을 안전한 위치에 스폰시키고 초기화합니다.
     * @param playerUUIDs 스폰시킬 플레이어들의 UUID 목록
     */
    public void spawnPlayers(List<UUID> playerUUIDs) {
        if (gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드가 설정되지 않아 플레이어를 스폰할 수 없습니다.");
            playerUUIDs.forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) p.sendMessage(ChatColor.RED + "[GGORRI] 게임 월드 설정 문제로 스폰할 수 없습니다.");
            });
            return;
        }

        WorldBorder border = gameWorld.getWorldBorder();
        if (border == null || border.getSize() <= 0) {
            plugin.getLogger().severe("[GGORRI] 월드 보더가 유효하지 않거나 크기가 0입니다. 스폰 불가.");
            playerUUIDs.forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) p.sendMessage(ChatColor.RED + "[GGORRI] 월드 보더 설정 문제로 스폰할 수 없습니다.");
            });
            return;
        }

        // 월드 보더 중앙에서 INITIAL_BORDER_SIZE/2 반경 안에서 스폰
        Location centerOfWorld = new Location(gameWorld, 0, 0, 0);
        int spawnRange = (int) (INITIAL_BORDER_SIZE / 2);

        for (UUID playerUUID : playerUUIDs) {
            Player player = plugin.getServer().getPlayer(playerUUID);
            if (player == null || !player.isOnline()) {
                plugin.getLogger().warning("[GGORRI] 게임 참가 플레이어(" + playerUUID + ")가 오프라인이거나 유효하지 않습니다. 스폰되지 않았습니다.");
                continue;
            }

            // 안전한 스폰 위치 찾기
            Location spawnLoc = findSafeSpawnLocation(centerOfWorld, spawnRange, 0, MAX_SPAWN_ATTEMPTS);

            if (spawnLoc == null) {
                plugin.getLogger().warning("[GGORRI] " + player.getName() + "를 위한 안전한 스폰 위치를 찾지 못했습니다. 월드 스폰으로 이동.");
                spawnLoc = gameWorld.getSpawnLocation(); // 최종 fallback
                player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 스폰 위치를 찾지 못해 월드 스폰으로 이동했습니다.");
            }

            player.teleport(spawnLoc);
            player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임 시작! 스폰 완료!");

            if (playerManager.getPlayerGameData(playerUUID) != null && playerManager.getPlayerGameData(playerUUID).getDirectTargetUUID() != null) {
                UUID targetUUID = playerManager.getPlayerGameData(playerUUID).getDirectTargetUUID();
                OfflinePlayer targetPlayer = plugin.getServer().getOfflinePlayer(targetUUID);
                player.sendMessage(ChatColor.GREEN + "[GGORRI] 당신의 타겟은 " + targetPlayer.getName() + " 입니다!");
            } else {
                player.sendMessage(ChatColor.YELLOW + "[GGORRI] 아직 타겟이 할당되지 않았거나 정보를 불러올 수 없습니다.");
            }

            plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 " +
                    String.format("%.1f", spawnLoc.getX()) + ", " +
                    String.format("%.1f", spawnLoc.getY()) + ", " +
                    String.format("%.1f", spawnLoc.getZ()) + " 로 스폰되었습니다.");
        }
        plugin.getLogger().info("[GGORRI] 모든 참가 플레이어 스폰 완료.");
    }

    /**
     * 지정된 중심 위치와 반경 내에서 안전한 스폰 위치를 찾습니다.
     *
     * @param centerLocation 스폰 위치를 찾을 중심 Location
     * @param searchRadius   중심 위치로부터의 최대 탐색 반경 (X, Z 축)
     * @param minDistance    중심 위치로부터의 최소 거리 (0이면 무시)
     * @param attempts       스폰 위치 탐색 시도 횟수
     * @return 안전한 스폰 위치, 찾지 못하면 null
     */
    public Location findSafeSpawnLocation(Location centerLocation, int searchRadius, int minDistance, int attempts) {
        if (centerLocation == null || centerLocation.getWorld() == null) {
            plugin.getLogger().warning("[GGORRI] findSafeSpawnLocation: 유효하지 않은 중심 Location. 스폰 위치 탐색 불가.");
            return null;
        }

        World world = centerLocation.getWorld();
        int centerX = centerLocation.getBlockX();
        int centerZ = centerLocation.getBlockZ();

        for (int i = 0; i < attempts; i++) {
            int dx = random.nextInt(searchRadius * 2 + 1) - searchRadius;
            int dz = random.nextInt(searchRadius * 2 + 1) - searchRadius;

            if (minDistance > 0) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < minDistance) {
                    continue;
                }
            }

            int x = centerX + dx;
            int z = centerZ + dz;

            // X, Z 좌표를 바탕으로 안전한 Y 좌표 찾기
            Location potentialSpawnXZ = new Location(world, x, 0, z);
            Location safeYLocation = findGroundY(potentialSpawnXZ);

            if (safeYLocation != null) {
                // Y 좌표까지 찾았으면, 해당 3D 위치가 최종적으로 안전한지 검사
                if (isLocationSafeForSpawn(safeYLocation)) {
                    return safeYLocation;
                } else {
                    plugin.getLogger().log(Level.FINEST, "스폰 위치 불가능: " + safeYLocation.getBlockX() + ", " + safeYLocation.getBlockY() + ", " + safeYLocation.getBlockZ() + " (최종 안전 검사 실패)");
                }
            } else {
                plugin.getLogger().log(Level.FINEST, "스폰 위치 불가능: X:" + x + ", Z:" + z + " (Y 좌표 찾기 실패 - 지표면 아님/안전 Y 없음)");
            }
        }
        plugin.getLogger().warning("[GGORRI] " + centerLocation.getWorld().getName() + " 에서 안전한 스폰 위치를 " + attempts + "번 시도했으나 찾지 못했습니다. 중심: " + centerLocation.getBlockX() + "," + centerLocation.getBlockZ() + ", 반경: " + searchRadius);
        return null;
    }

    /**
     * 주어진 X, Z 좌표에서 플레이어가 스폰될 수 있는 가장 높은 안전한 Y 좌표를 찾습니다.
     * (지상에 스폰되며, 머리 위 공간이 비어있는지 확인)
     * **지표면만 허용하며, 액체 블록이나 동굴 내부 스폰을 방지합니다.**
     *
     * @param loc X, Z 좌표가 설정된 Location 객체 (Y는 무시됨)
     * @return 안전한 스폰 Y 좌표를 포함한 Location, 찾지 못하면 null
     */
    private Location findGroundY(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        // 지표면은 '가장 높은 불투명 블록 바로 위'로 정의.
        // 그리고 그 블록 아래는 단단해야 하고, 위 두 칸은 비어있어야 하며, 물이 아니어야 함.
        // MIN_SPAWN_Y부터 월드 최고 높이까지 역순으로 탐색
        for (int y = world.getMaxHeight() - 1; y >= MIN_SPAWN_Y; y--) {
            Block blockAtFeet = world.getBlockAt(x, y, z);
            Block blockBelowFeet = world.getBlockAt(x, y - 1, z);
            Block blockAboveHead = world.getBlockAt(x, y + 1, z); // 플레이어 머리 위 공간

            // 1. 발 아래 블록이 단단해야 하고 액체가 아니어야 함
            if (!blockBelowFeet.getType().isSolid() || blockBelowFeet.isLiquid()) {
                plugin.getLogger().log(Level.FINEST, "findGroundY: 발 아래 블록이 단단하지 않거나 액체임. " + x + "," + (y-1) + "," + z + " (" + blockBelowFeet.getType().name() + ")");
                continue; // 이 Y는 지표면이 아님
            }

            // 2. 발 위치 블록과 머리 위 블록은 통과 가능해야 하고 액체가 아니어야 함
            if (blockAtFeet.getType().isSolid() || blockAtFeet.isLiquid() ||
                    blockAboveHead.getType().isSolid() || blockAboveHead.isLiquid()) {
                plugin.getLogger().log(Level.FINEST, "findGroundY: 발 위치 또는 머리 위 블록이 통과 불가 또는 액체. " + x + "," + y + "," + z + " (" + blockAtFeet.getType().name() + "/" + blockAboveHead.getType().name() + ")");
                continue; // 이 Y는 스폰 공간이 아님
            }

            // 3. Y-2 블록도 단단해야 함 (동굴 스폰 방지)
            Block blockBelowBelowFeet = world.getBlockAt(x, y - 2, z);
            if (!blockBelowBelowFeet.getType().isSolid() && !blockBelowBelowFeet.isLiquid()) {
                plugin.getLogger().log(Level.FINEST, "findGroundY: Y-2 블록이 단단하지 않거나 액체임 (동굴 의심). " + x + "," + y + "," + z);
                continue; // 이 Y는 지표면이 아닐 수 있으니 다음 Y를 탐색
            }

            // 4. 주변에 위험 블록이 없는지 확인
            // 여기서는 플레이어가 직접 서는 위치 (y)와 그 위 (y+1), 그리고 발 아래 (y-1)에 대해
            // isHarmfulBlockType을 사용하여 해로운 블록인지 확인합니다.
            // (isLocationSafeForSpawn에서 3x3x3 최종 검사가 다시 이루어지므로, 여기서는 핵심적인 부분만)
            if (isHarmfulBlockType(blockAtFeet.getType()) ||
                    isHarmfulBlockType(blockAboveHead.getType()) ||
                    isHarmfulBlockType(blockBelowFeet.getType())) {
                plugin.getLogger().log(Level.FINEST, "findGroundY: 플레이어 위치 주변에 위험 블록 타입 감지. " + x + "," + y + "," + z);
                continue; // 이 Y는 위험한 블록이 있어 스폰 불가
            }

            // 모든 조건을 만족하면 안전한 Y 좌표
            return new Location(world, x + 0.5, y, z + 0.5); // 블록 중앙에 스폰하도록 조정
        }
        return null; // 안전한 Y 좌표를 찾지 못함
    }

    /**
     * 특정 블록 타입이 플레이어가 스폰될 위치에 존재하면 **안 되는** 해로운 블록인지 확인합니다.
     * 이 블록들은 플레이어에게 피해를 주거나 움직임을 방해하거나 갇히게 만들 수 있습니다.
     * (SOLID, LIQUID, FLAMMABLE 여부는 이 메서드 외부에서 판단합니다.)
     */
    private boolean isHarmfulBlockType(Material material) {
        if (material == null) return true; // Null 블록은 위험

        return material == Material.CACTUS ||
                material == Material.LAVA ||
                material == Material.MAGMA_BLOCK ||
                material == Material.WITHER_ROSE ||
                material == Material.SWEET_BERRY_BUSH ||
                material == Material.FIRE ||
                material == Material.SOUL_FIRE ||
                material == Material.CAMPFIRE ||
                material == Material.SOUL_CAMPFIRE ||
                material == Material.END_PORTAL_FRAME ||
                material == Material.POINTED_DRIPSTONE ||
                material == Material.ANVIL || // 떨어진다면 위험, 스폰 시 위험
                material == Material.COBWEB || // 움직임 방해
                material.name().contains("TRAPDOOR") || // 갇힐 위험
                material.name().contains("GATE") || // 갇힐 위험
                material.name().contains("FENCE") || // 울타리 (플레이어 통과 불가)
                material.name().contains("WALL") || // 벽 (플레이어 통과 불가)
                material.name().contains("DOOR"); // 문 (갇히거나 막힐 위험)
    }

    /**
     * 주어진 위치가 플레이어가 스폰하기에 안전한지 모든 기준을 검사합니다.
     * 이 메서드는 findGroundY에서 반환된 Y 좌표의 최종 유효성을 확인합니다.
     *
     * @param loc 검사할 Location (X, Y, Z 모두 포함)
     * @return 안전하면 true, 그렇지 않으면 false
     */
    private boolean isLocationSafeForSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        World world = loc.getWorld();
        WorldBorder border = world.getWorldBorder();

        // 1. 자기장(월드보더) 안쪽일 것
        if (!border.isInside(loc)) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: 월드보더 밖. " + loc.toVector().toString());
            return false;
        }

        // 2. Y 좌표가 유효한 범위 내에 있는지 (최소 Y보다 낮지 않은지)
        if (loc.getY() < MIN_SPAWN_Y) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: Y 좌표가 너무 낮음 (" + loc.getY() + "). " + loc.toVector().toString());
            return false;
        }

        // 3. 스폰 지점 및 그 위/아래 블록의 최종 상태 확인 (findGroundY에서 이미 처리된 부분이지만, 최종적으로 한 번 더 검사)
        Block blockAtFeet = loc.getBlock();
        Block blockBelowFeet = blockAtFeet.getRelative(BlockFace.DOWN);
        Block blockAboveHead = blockAtFeet.getRelative(BlockFace.UP);
        Block blockBelowBelowFeet = blockAtFeet.getRelative(BlockFace.DOWN, 2); // Y-2 블록

        // 발 아래 블록이 단단하고 액체가 아님
        if (!blockBelowFeet.getType().isSolid() || blockBelowFeet.isLiquid()) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: 발 아래 블록이 단단하지 않거나 액체임. " + blockBelowFeet.getType().name() + " at " + blockBelowFeet.getLocation().toVector().toString());
            return false;
        }
        // 발 위치 블록과 머리 위 블록이 통과 가능하고 액체가 아님
        if (blockAtFeet.getType().isSolid() || blockAtFeet.isLiquid() ||
                blockAboveHead.getType().isSolid() || blockAboveHead.isLiquid()) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: 발 위치 또는 머리 위 블록이 통과 불가 또는 액체. " + blockAtFeet.getType().name() + ", " + blockAboveHead.getType().name() + " at " + loc.toVector().toString());
            return false;
        }
        // Y-2 블록도 단단하거나 액체가 아니어야 함 (동굴 방지)
        if (!blockBelowBelowFeet.getType().isSolid() && !blockBelowBelowFeet.isLiquid()) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: Y-2 블록이 단단하지 않거나 액체임 (동굴). " + blockBelowBelowFeet.getLocation().toVector().toString());
            return false;
        }

        // 4. 스폰 위치 주변 3x3x3 공간에 **해로운 블록**이 없는지 최종 검사
        // 이 검사는 플레이어가 서는 1x2 블록 공간뿐만 아니라,
        // 플레이어 주변에 즉시 피해를 주거나 갇히게 만들 수 있는 블록이 없는지 확인합니다.
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    Block nearbyBlock = loc.clone().add(xOffset, yOffset, zOffset).getBlock();
                    if (isHarmfulBlockType(nearbyBlock.getType())) { // isHarmfulBlockType 사용
                        plugin.getLogger().log(Level.FINEST, "Safety Check: 주변 위험 블록(" + nearbyBlock.getType().name() + ") 감지. " + nearbyBlock.getLocation().toVector().toString());
                        return false;
                    }
                }
            }
        }
        return true; // 모든 검사 통과
    }

    // 게터 메서드
    public World getGameWorld() {
        return gameWorld;
    }

    public int getInitialWorldBorderSize() {
        return (int) INITIAL_BORDER_SIZE;
    }
}