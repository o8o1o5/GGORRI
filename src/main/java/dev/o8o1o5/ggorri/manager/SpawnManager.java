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
    private final PlayerManager playerManager; // PlayerManager는 타겟 정보 등을 위해 필요할 수 있습니다.
    private World gameWorld;
    private final Random random;

    private static final double INITIAL_BORDER_SIZE = 3200.0;
    private static final int MAX_SPAWN_ATTEMPTS = 200; // 스폰 위치 탐색 최대 시도 횟수

    public SpawnManager(GGORRI plugin, PlayerManager playerManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.random = new Random();
        this.gameWorld = plugin.getServer().getWorld("world"); // 또는 config에서 월드 이름 로드
        if (this.gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드 'world'를 찾을 수 없습니다! 서버 설정 또는 플러그인 로딩 순서를 확인하세요.");
            // 필요한 경우 대체 월드를 설정하거나 서버 종료 고려
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
        this.gameWorld = world; // gameWorld 업데이트

        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(INITIAL_BORDER_SIZE);
        border.setWarningDistance(0); // 경고 테두리 없음
        border.setDamageAmount(0.0); // 경계 밖 데미지 없음 (필요시 설정)

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

        // 월드 보더 중앙에서 INITIAL_BORDER_SIZE/2 반경 안에서 스폰 (월드 보더가 3200이면 -1600 ~ +1600)
        Location centerOfWorld = new Location(gameWorld, 0, 0, 0); // 월드 보더 중심
        int spawnRange = (int) (INITIAL_BORDER_SIZE / 2); // 스폰 탐색 범위

        for (UUID playerUUID : playerUUIDs) {
            Player player = plugin.getServer().getPlayer(playerUUID);
            if (player == null || !player.isOnline()) {
                plugin.getLogger().warning("[GGORRI] 게임 참가 플레이어(" + playerUUID + ")가 오프라인이거나 유효하지 않습니다. 스폰되지 않았습니다.");
                continue; // 다음 플레이어로
            }

            // 안전한 스폰 위치 찾기 (전역 스폰)
            Location spawnLoc = findSafeSpawnLocation(centerOfWorld, spawnRange, 0, MAX_SPAWN_ATTEMPTS); // minDistance 0으로 전역 스폰

            if (spawnLoc == null) {
                plugin.getLogger().warning("[GGORRI] " + player.getName() + "를 위한 안전한 스폰 위치를 찾지 못했습니다. 월드 스폰으로 이동.");
                spawnLoc = gameWorld.getSpawnLocation(); // 최종 fallback
                player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 스폰 위치를 찾지 못해 월드 스폰으로 이동했습니다.");
            }

            player.teleport(spawnLoc);
            player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임 시작! 스폰 완료!");

            // 플레이어 타겟 정보 메시지는 PlayerGameData가 확실히 로드된 후에 보내야 합니다.
            // 여기서는 playerManager를 사용하므로, 플레이어 데이터가 있는 경우에만 시도
            if (playerManager.getPlayerGameData(playerUUID) != null && playerManager.getPlayerGameData(playerUUID).getDirectTargetUUID() != null) {
                UUID targetUUID = playerManager.getPlayerGameData(playerUUID).getDirectTargetUUID();
                OfflinePlayer targetPlayer = plugin.getServer().getOfflinePlayer(targetUUID); // OfflinePlayer 사용
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
     * @param centerLocation 스폰 위치를 찾을 중심 Location (월드의 중심 또는 플레이어 위치)
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
            int dx = random.nextInt(searchRadius * 2) - searchRadius; // -searchRadius ~ +searchRadius
            int dz = random.nextInt(searchRadius * 2) - searchRadius; // -searchRadius ~ +searchRadius

            // 최소 거리 검사
            if (minDistance > 0) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < minDistance) {
                    continue; // 최소 거리보다 가까우면 다시 시도
                }
            }

            int x = centerX + dx;
            int z = centerZ + dz;

            // X, Z 좌표를 바탕으로 안전한 Y 좌표 찾기
            Location potentialSpawnXZ = new Location(world, x, 0, z); // Y는 나중에 찾음
            Location safeYLocation = findGroundY(potentialSpawnXZ);

            if (safeYLocation != null) {
                // Y 좌표까지 찾았으면, 해당 3D 위치가 최종적으로 안전한지 검사
                if (isLocationSafeForSpawn(safeYLocation)) {
                    return safeYLocation; // 안전한 스폰 위치 발견
                } else {
                    plugin.getLogger().log(Level.FINEST, "스폰 위치 불가능: " + safeYLocation.getBlockX() + ", " + safeYLocation.getBlockY() + ", " + safeYLocation.getBlockZ() + " (안전 검사 실패)");
                }
            } else {
                plugin.getLogger().log(Level.FINEST, "스폰 위치 불가능: " + x + ", (Y값 없음), " + z + " (Y 좌표 찾기 실패)");
            }
        }
        plugin.getLogger().warning("[GGORRI] " + centerLocation.getWorld().getName() + " 에서 안전한 스폰 위치를 " + attempts + "번 시도했으나 찾지 못했습니다. 중심: " + centerLocation.getBlockX() + "," + centerLocation.getBlockZ() + ", 반경: " + searchRadius);
        return null;
    }

    /**
     * 주어진 X, Z 좌표에서 플레이어가 스폰될 수 있는 가장 높은 안전한 Y 좌표를 찾습니다.
     * (지상에 스폰되며, 머리 위 공간이 비어있는지 확인)
     *
     * @param loc X, Z 좌표가 설정된 Location 객체 (Y는 무시됨)
     * @return 안전한 스폰 Y 좌표를 포함한 Location, 찾지 못하면 null
     */
    private Location findGroundY(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;

        // 월드의 최고 높이부터 내려오면서 유효한 지표면 블록 찾기
        // getHighestBlockYAt은 가장 높은 불투명 블록의 Y를 반환
        int highestY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());

        // 지표면 위 5칸부터 아래로 탐색 (나무 위, 임시 구조물 등 피하기 위함)
        // 최소 높이까지 내려가면서 안전한 Y를 찾습니다.
        for (int y = Math.min(highestY + 5, world.getMaxHeight() - 2); y >= world.getMinHeight() + 2; y--) {
            Location currentLoc = new Location(world, loc.getX(), y, loc.getZ());
            Block blockAtFeet = currentLoc.getBlock();
            Block blockBelowFeet = blockAtFeet.getRelative(BlockFace.DOWN);
            Block blockAboveHead = blockAtFeet.getRelative(BlockFace.UP);

            // 1. 발 아래 블록이 단단하고 액체가 아님 (땅)
            // 2. 발 위치 블록이 통과 가능하고 액체가 아님 (공기/꽃 등)
            // 3. 머리 위 블록이 통과 가능하고 액체가 아님 (공기/꽃 등)
            if (blockBelowFeet.getType().isSolid() && !blockBelowFeet.isLiquid() &&
                    !blockAtFeet.getType().isSolid() && !blockAtFeet.isLiquid() &&
                    !blockAboveHead.getType().isSolid() && !blockAboveHead.isLiquid()) {
                // 안전한 Y 좌표를 찾았으면 바로 반환 (가장 높은 유효한 Y)
                return currentLoc.add(0.5, 0.0, 0.5); // 블록 중앙에 스폰하도록 조정
            }
        }
        return null; // 안전한 Y 좌표를 찾지 못함
    }

    /**
     * 주어진 위치가 플레이어가 스폰하기에 안전한지 모든 기준을 검사합니다.
     *
     * @param loc 검사할 Location (X, Y, Z 모두 포함)
     * @return 안전하면 true, 그렇지 않으면 false
     */
    private boolean isLocationSafeForSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        World world = loc.getWorld();
        WorldBorder border = world.getWorldBorder();

        // 2-1. 자기장(월드보더) 안쪽일 것
        if (!border.isInside(loc)) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: 월드보더 밖. " + loc.toVector().toString());
            return false;
        }

        // 2-3. 바다에서 스폰되지 않을 것 (지표면 기준)
        // findGroundY에서 이미 지표면 위를 찾도록 했지만, 혹시 모를 액체 블록 위에 스폰될 가능성 차단
        if (loc.getBlock().isLiquid() || loc.clone().subtract(0, 1, 0).getBlock().isLiquid()) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: 액체 블록. " + loc.toVector().toString());
            return false;
        }

        // 2-4. 안전할 것 (데미지를 받지 않는 상태)
        // 스폰 위치 주변 1칸 범위 (3x3x3)에 위험한 블록이 없는지 확인
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    Block nearbyBlock = loc.clone().add(xOffset, yOffset, zOffset).getBlock();
                    Material mat = nearbyBlock.getType();

                    // 선인장, 용암, 마그마 블록, 엔드 포탈 프레임 (위험 블록)
                    if (mat == Material.CACTUS || mat == Material.LAVA || mat == Material.MAGMA_BLOCK ||
                            mat == Material.WITHER_ROSE || mat == Material.SWEET_BERRY_BUSH || mat == Material.FIRE ||
                            mat == Material.CAMPFIRE || mat == Material.SOUL_CAMPFIRE || mat == Material.END_PORTAL_FRAME) { // 엔더 포탈 프레임 위에 서면 데미지 받으므로 추가
                        plugin.getLogger().log(Level.FINEST, "Safety Check: 위험 블록(" + mat.name() + ") 감지. " + nearbyBlock.getLocation().toVector().toString());
                        return false;
                    }
                    // 추가: 플레이어가 질식할 수 있는 블록(벽 속)에 스폰되지 않도록 (findGroundY에서 어느 정도 처리됨)
                    if (yOffset == 0 && (xOffset == 0 || zOffset == 0) && nearbyBlock.getType().isSolid() && nearbyBlock != loc.getBlock()) {
                        // 스폰 위치 자체는 통과 가능해야 하고, 주변 블록이 플레이어를 가둘 수 없어야 합니다.
                        // findGroundY에서 이미 처리된 부분이지만, 재확인
                        // 이 조건은 findGroundY에서 더 엄격하게 처리하는게 좋습니다.
                    }
                }
            }
        }
        // 추가: 땅이 뚫려있지 않은지 (findGroundY에서 처리되지만, 재확인)
        if (!loc.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) {
            plugin.getLogger().log(Level.FINEST, "Safety Check: 발 아래 단단한 블록 없음. " + loc.toVector().toString());
            return false;
        }

        return true; // 모든 검사 통과
    }

    /**
     * 게임 월드 객체를 반환합니다.
     * @return 게임 월드
     */
    public World getGameWorld() {
        return gameWorld;
    }

    /**
     * 초기 월드 보더 크기를 반환합니다.
     * @return 초기 월드 보더 크기
     */
    public int getInitialWorldBorderSize() {
        return (int) INITIAL_BORDER_SIZE;
    }
}