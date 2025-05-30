package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.game.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameRulesManager {
    private final GGORRI plugin;
    private final GameManager gameManager;
    private final PlayerManager playerManager;
    private final SpawnManager spawnManager;
    private final ChainManager chainManager;

    public GameRulesManager(GGORRI plugin, GameManager gameManager, PlayerManager playerManager, SpawnManager spawnManager, ChainManager chainManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.playerManager = playerManager;
        this.spawnManager = spawnManager;
        this.chainManager = chainManager;
    }

    /**
     * 플레이어 사망 시 호출되어 사망 타입을 판정하고 후속 처리를 진행합니다.
     * @param deadPlayer 죽은 플레이어
     * @param killer 죽인 플레이어 (PvP가 아닌 경우 null)
     * @param damageCause 사망 원인 (DamageCause)
     */
    public void handlePlayerDeath(Player deadPlayer, Player killer, EntityDamageEvent.DamageCause damageCause) {
        UUID deadUUID = deadPlayer.getUniqueId();
        PlayerGameData deadPlayerData = playerManager.getPlayerGameData(deadUUID);

        if (deadPlayerData == null) {
            plugin.getLogger().warning("[GGORRI] " + deadPlayer.getName() + " 이 게임 데이터에서 발견되지 않았습니다.");
            return;
        }

        plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + " 사망! 원인: " + damageCause.name());

        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            playerManager.resetPlayer(deadPlayer);
            if (spawnManager.getGameWorld() != null) {
                deadPlayer.teleport(spawnManager.getGameWorld().getSpawnLocation());
            }
            return;
        }

        if (deadPlayerData.getRole() == PlayerRole.SLAVE) {
            handleNaturalDeath(deadPlayer, deadPlayerData);
            playerManager.clearDamageRecordsForPlayer(deadUUID);
            checkWinCondition();
            return;
        }

        UUID actualKillerUUID = null;
        if (killer != null) {
            actualKillerUUID = killer.getUniqueId();
        } else {
            // Bukkit의 getKiller()가 null인 경우, playerDamageTracker를 통해 마지막 공격자 확인
            actualKillerUUID = playerManager.getLastAttacker(deadUUID);
            if (actualKillerUUID != null && !playerManager.getAllPlayersGameData().containsKey(actualKillerUUID)) {
                actualKillerUUID = null; // 오프라인 또는 게임 참가자가 아닌 플레이어는 유효한 킬러로 간주하지 않음
            }
        }

        if (actualKillerUUID != null && playerManager.getAllPlayersGameData().containsKey(actualKillerUUID)) {
            // 가해자가 게임 내 플레이어인 경우 PvP로 간주
            PlayerGameData killerPlayerData = playerManager.getPlayerGameData(actualKillerUUID);
            if (killerPlayerData != null && killerPlayerData.getRole() == PlayerRole.LEADER) { // 킬러가 팀장인 경우
                if (killerPlayerData.getDirectTargetUUID() != null && killerPlayerData.getDirectTargetUUID().equals(deadUUID)) {
                    // A. 정상 처치 (직계 타겟 처치)
                    plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) " + plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() + "에게 정상 처치됨.");
                    handleNormalKill(deadPlayer, plugin.getServer().getPlayer(actualKillerUUID));
                } else {
                    // B. 잘못된 타겟 처치 (직계 타겟이 아닌 다른 플레이어 PvP 사망)
                    plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) " + plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() + "에게 잘못된 타겟으로 처치됨.");
                    handleWrongTargetKill(deadPlayer, plugin.getServer().getPlayer(actualKillerUUID));
                }
            } else {
                // 킬러가 노예이거나 게임 참여자가 아님 -> 자연사 처리
                plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) (비유효한 킬러: " + (killer != null ? killer.getName() : "없음") + " / 트래커 킬러: " + (actualKillerUUID != null ? plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() : "없음") + ")에게 사망. 자연사로 처리.");
                handleNaturalDeath(deadPlayer, deadPlayerData);
            }
        } else {
            // C. 자연사 (환경 사망 또는 PvP가 아닌 사망)
            plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) 자연사했습니다. (킬러 없음 / 유효하지 않은 킬러)");
            handleNaturalDeath(deadPlayer, deadPlayerData);
        }

        playerManager.clearDamageRecordsForPlayer(deadUUID); // 사망 후 데미지 트래커 정리
        checkWinCondition(); // 게임 종료 조건 체크
    }

    /**
     * 정상 처치 시 로직: 죽은 플레이어를 처치한 플레이어의 노예로 종속시킵니다.
     * @param deadPlayer 죽은 플레이어
     * @param killer 죽인 플레이어 (새로운 팀장)
     */
    private void handleNormalKill(Player deadPlayer, Player killer) {
        chainManager.enslavePlayerAndAdjustTarget(killer.getUniqueId(), deadPlayer.getUniqueId());

        Bukkit.broadcastMessage(ChatColor.GREEN + "§l" + killer.getName() + "님이 " + deadPlayer.getName() + "을(를) 정상 처치하여 자신의 노예로 만들었습니다!");
        killer.sendMessage(ChatColor.AQUA + "새로운 타겟: " + (playerManager.getPlayerGameData(killer.getUniqueId()).getDirectTargetUUID() != null ?
                Bukkit.getOfflinePlayer(playerManager.getPlayerGameData(killer.getUniqueId()).getDirectTargetUUID()).getName() : "없음"));

        // 사망한 플레이어 부활 스케줄링
        schedulePlayerRespawn(deadPlayer.getUniqueId(), 1); // 즉시 리스폰. 노예는 팀장 근처에서 부활 (2.1단계)
    }

    /**
     * 잘못된 타겟 처치 시 로직: 죽은 플레이어를 노예로 만들고, 처치한 플레이어에게 패널티를 부여합니다.
     * @param deadPlayer 죽은 플레이어
     * @param killer 잘못 처치한 플레이어
     */
    private void handleWrongTargetKill(Player deadPlayer, Player killer) {
        UUID deadUUID = deadPlayer.getUniqueId();
        UUID killerUUID = killer.getUniqueId();

        PlayerGameData deadPlayerData = playerManager.getPlayerGameData(deadUUID);
        if (deadPlayerData != null) {
            deadPlayerData.setRole(PlayerRole.SLAVE);
            deadPlayerData.setDirectTargetUUID(null); // 노예는 이제 타겟 없음
            Bukkit.broadcastMessage(ChatColor.YELLOW + "§l" + killer.getName() + "님이 잘못된 타겟인 " + deadPlayer.getName() + "을(를) 처치했습니다!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + deadPlayer.getName() + "님은 이제 " + killer.getName() + "님의 노예가 됩니다.");
        }

        // TODO: 잘못 처치한 플레이어(killer)에게 패널티 부여 로직 추가 (예: 디버프)

        schedulePlayerRespawn(deadPlayer.getUniqueId(), 1); // 즉시 리스폰
    }

    /**
     * 자연사 로직: 사망 횟수 증가, 부활 시간 증가, 노예는 모든 사망을 자연사로 간주.
     * @param deadPlayer 죽은 플레이어
     * @param deadPlayerData 죽은 플레이어의 PlayerGameData
     */
    private void handleNaturalDeath(Player deadPlayer, PlayerGameData deadPlayerData) {
        deadPlayerData.incrementDeathCount();
        long respawnDelaySeconds = deadPlayerData.getDeathCount() * 5; // 사망 횟수 당 5초 증가 (예시)
        Bukkit.broadcastMessage(ChatColor.GRAY + deadPlayer.getName() + "님은 자연사했습니다. 사망 횟수: " + deadPlayerData.getDeathCount() + "회. 부활 대기 시간: " + respawnDelaySeconds + "초.");

        schedulePlayerRespawn(deadPlayer.getUniqueId(), respawnDelaySeconds * 20L); // 틱 단위 변환
    }

    /**
     * 지정된 시간 후 플레이어를 부활시킵니다.
     * @param playerUUID 부활시킬 플레이어의 UUID
     * @param delayTicks 부활까지의 지연 시간 (틱)
     */
    public void schedulePlayerRespawn(UUID playerUUID, long delayTicks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = plugin.getServer().getPlayer(playerUUID);
                if (player != null && playerManager.getAllPlayersGameData().containsKey(playerUUID)) {
                    respawnPlayer(player);
                } else if (player != null) {
                    player.spigot().respawn(); // 게임에서 이탈한 플레이어는 기본 리스폰
                }
            }
        }.runTaskLater(plugin, delayTicks);
    }

    /**
     * 실제 플레이어를 부활시키는 로직.
     * 안전한 스폰 위치로 텔레포트하고, 게임 모드를 변경하며, 상태를 초기화하고 무적 효과를 부여합니다.
     * @param player 부활시킬 플레이어
     */
    private void respawnPlayer(Player player) {
        PlayerGameData playerData = playerManager.getPlayerGameData(player.getUniqueId());
        if (playerData == null) {
            plugin.getLogger().warning("[GGORRI] 부활하려는 플레이어(" + player.getName() + ")의 게임 데이터가 없습니다.");
            player.spigot().respawn();
            return;
        }

        player.spigot().respawn(); // 클라이언트 측면 리스폰

        Location spawnLoc = null;
        // 2.1단계: 노예는 팀장 근처에서 부활
        if (playerData.getRole() == PlayerRole.SLAVE && playerData.getDirectTargetUUID() == null) {
            // 노예인데 타겟이 없다는 것은 종속된 상태 (팀장 죽음 -> 노예화), 팀장 근처 스폰 로직
            UUID teamLeaderUUID = findTeamLeaderForSlave(player.getUniqueId()); // 노예의 팀장을 찾아야 함 (현재 PlayerGameData에 필드 없음, 임시로 찾음)
            if (teamLeaderUUID != null) {
                Player teamLeader = plugin.getServer().getPlayer(teamLeaderUUID);
                if (teamLeader != null && teamLeader.isOnline()) {
                    spawnLoc = spawnManager.findSafeSpawnLocation(teamLeader.getLocation().getWorld(), teamLeader.getLocation().getBlockX(), teamLeader.getLocation().getBlockZ(), 500, 100); // 팀장 근처 500블록 내에서 스폰
                    if (spawnLoc == null) {
                        plugin.getLogger().warning("[GGORRI] " + player.getName() + " (노예)를 위한 팀장 근처 안전 스폰 위치를 찾지 못했습니다. 일반 스폰으로 이동합니다.");
                    }
                }
            }
        }

        if (spawnLoc == null) {
            spawnLoc = spawnManager.findSafeSpawnLocation(spawnManager.getGameWorld(), 100);
            if (spawnLoc == null) {
                plugin.getLogger().warning("[GGORRI] 플레이어 " + player.getName() + "를 위한 안전한 부활 위치를 찾지 못했습니다. 월드 스폰으로 이동합니다.");
                player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 부활 위치를 찾지 못해 월드 스폰으로 이동합니다.");
                spawnLoc = spawnManager.getGameWorld().getSpawnLocation();
            }
        }

        playerManager.resetPlayer(player); // 초기화
        player.teleport(spawnLoc);
        player.sendMessage("[GGORRI] 부활했습니다! 다시 꼬리를 쫓으세요!");

        // 부활 시 무적 효과 (5초)
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 5, 255, false, false)); // Resistance V
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 5, 0, false, false)); // Fire Resistance

        plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + "로 부활했습니다.");
    }

    // TODO: PlayerGameData에 teamLeaderUUID 필드를 추가하면 이 메서드가 훨씬 간단해집니다.
    // 현재는 모든 리더를 역추적하여 이 노예의 팀장이 누구인지 '고리'를 따라 찾아야 합니다.
    // 이는 매우 비효율적이고 복잡하므로, PlayerGameData에 teamLeaderUUID를 추가하는 것을 강력히 권장합니다.
    private UUID findTeamLeaderForSlave(UUID slaveUUID) {
        // 임시 로직: 현재는 PlayerGameData에 팀장 UUID가 없으므로, 모든 리더를 순회하며
        // 해당 리더의 고리 안에 이 슬레이브가 있는지 찾아야 합니다.
        // 이 로직은 매우 비효율적이므로 PlayerGameData에 teamLeaderUUID를 추가하는 것이 필수적입니다.

        for (PlayerGameData leaderData : playerManager.getAllPlayersGameData().values().stream()
                .filter(data -> data.getRole() == PlayerRole.LEADER)
                .collect(Collectors.toList())) {
            UUID current = leaderData.getPlayerUUID();
            UUID startTarget = leaderData.getDirectTargetUUID();
            if (startTarget == null) continue; // 고리가 없는 리더는 무시

            // 최대 N번 순회 (게임 참여자 수만큼)하여 고리 안에 slaveUUID가 있는지 확인
            UUID next = startTarget;
            for (int i = 0; i < playerManager.getAllPlayersGameData().size(); i++) {
                if (next.equals(slaveUUID)) {
                    return leaderData.getPlayerUUID(); // 이 리더가 slaveUUID의 팀장
                }
                PlayerGameData nextData = playerManager.getPlayerGameData(next);
                if (nextData == null || nextData.getDirectTargetUUID() == null || nextData.getRole() == PlayerRole.SLAVE) {
                    break; // 고리가 끊어지거나 노예를 만나면 중단
                }
                next = nextData.getDirectTargetUUID();
                if (next.equals(current)) { // 고리가 순환하여 다시 시작 지점으로 돌아오면
                    break;
                }
            }
        }
        plugin.getLogger().warning("[GGORRI] 노예(" + plugin.getServer().getOfflinePlayer(slaveUUID).getName() + ")의 팀장을 찾을 수 없습니다.");
        return null;
    }

    /**
     * 현재 게임의 승리 조건을 확인합니다.
     * 모든 플레이어가 하나의 LEADER (팀장) 밑에 SLAVE로 종속되었는지 확인합니다.
     * @return 승리한 팀장의 UUID, 또는 null (아직 승리 조건 미충족 시)
     */
    public UUID checkWinCondition() {
        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            return null; // 게임 중이 아니면 승리 조건 확인하지 않음
        }

        Map<UUID, PlayerGameData> activePlayers = playerManager.getAllPlayersGameData().entrySet().stream()
                .filter(entry -> plugin.getServer().getPlayer(entry.getKey()) != null && plugin.getServer().getPlayer(entry.getKey()).isOnline())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (activePlayers.isEmpty()) {
            plugin.getLogger().info("[GGORRI] 모든 플레이어가 게임에서 이탈하여 강제 종료됩니다.");
            endGame(null); // 모든 플레이어가 나가면 강제 종료
            return null;
        }

        List<UUID> leaders = activePlayers.entrySet().stream()
                .filter(entry -> entry.getValue().getRole() == PlayerRole.LEADER)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 승리 조건: 리더가 1명만 남았을 때
        if (leaders.size() == 1) {
            UUID winningLeaderUUID = leaders.get(0);

            // 이 리더를 제외한 모든 게임 내 플레이어가 SLAVE인지 확인
            boolean allOthersAreSlaves = activePlayers.values().stream()
                    .allMatch(data -> data.getRole() == PlayerRole.SLAVE || data.getPlayerUUID().equals(winningLeaderUUID));

            if (allOthersAreSlaves) {
                plugin.getLogger().info("[GGORRI] 승리 조건 충족! 승리한 팀장: " + plugin.getServer().getOfflinePlayer(winningLeaderUUID).getName());
                endGame(winningLeaderUUID);
                return winningLeaderUUID;
            }
        }

        // 추가: 혼자 남은 경우도 승리
        if (activePlayers.size() == 1 && leaders.size() == 1) {
            UUID soleSurvivorUUID = leaders.get(0);
            plugin.getLogger().info("[GGORRI] 마지막 플레이어가 생존하여 승리했습니다: " + plugin.getServer().getOfflinePlayer(soleSurvivorUUID).getName());
            endGame(soleSurvivorUUID);
            return soleSurvivorUUID;
        }

        plugin.getLogger().log(Level.FINE, "[GGORRI] 현재 LEADER 수: " + leaders.size() + ", 게임 내 플레이어 수: " + activePlayers.size());
        return null; // 아직 승리 조건 미충족
    }

    /**
     * 게임을 종료하고 결과를 발표하며 모든 플레이어를 초기화합니다.
     * @param winnerLeaderUUID 승리한 팀장의 UUID (모든 플레이어가 이탈했을 경우 null)
     */
    public void endGame(UUID winnerLeaderUUID) {
        if (gameManager.getCurrentStatus() == GameManager.GameStatus.ENDING || gameManager.getCurrentStatus() == GameManager.GameStatus.WAITING) {
            return; // 이미 종료 중이거나 대기 중이면 무시
        }

        gameManager.currentStatus = GameManager.GameStatus.ENDING; // GameManager의 상태 업데이트
        plugin.getLogger().info("[GGORRI] 게임 종료 중...");

        String winnerMessage;
        if (winnerLeaderUUID != null) {
            Player winner = plugin.getServer().getPlayer(winnerLeaderUUID);
            String winnerName = (winner != null) ? winner.getName() : "알 수 없는 플레이어";
            winnerMessage = ChatColor.GOLD + "§l[GGORRI] 게임 종료! " + winnerName + " 팀이 승리했습니다!";
            plugin.getServer().broadcastMessage(winnerMessage);
            plugin.getServer().broadcastMessage(ChatColor.YELLOW + "모든 플레이어가 " + winnerName + " 팀에 편입되었습니다!");
        } else {
            winnerMessage = ChatColor.RED + "§l[GGORRI] 게임이 종료되었습니다! (승자가 없는 종료)";
            plugin.getServer().broadcastMessage(winnerMessage);
        }

        // 모든 플레이어 초기화 및 게임 데이터에서 제거
        // playerManager를 통해 처리
        // playersInGame 맵을 복사하여 ConcurrentModificationException 방지
        for (UUID uuid : new ArrayList<>(playerManager.getAllPlayersGameData().keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                playerManager.resetPlayer(player); // 인벤토리, 체력, 게임모드 등 초기화
                if (spawnManager.getGameWorld() != null) {
                    player.teleport(spawnManager.getGameWorld().getSpawnLocation());
                    player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임이 종료되어 로비로 이동했습니다.");
                } else {
                    player.sendMessage(ChatColor.RED + "[GGORRI] 월드 스폰 위치를 찾을 수 없어 현재 위치에 유지됩니다.");
                }
            }
            playerManager.removePlayerFromGame(player); // 게임 데이터에서 제거 (맵에서 제거)
        }
        // playerManager의 playersInGame 맵은 이제 비워졌을 것임

        // 월드 보더 초기화
        spawnManager.setupWorldBorder(spawnManager.getGameWorld()); // SpawnManager에게 위임

        gameManager.currentStatus = GameManager.GameStatus.WAITING; // GameManager의 상태를 대기 중으로 변경
        plugin.getLogger().info("[GGORRI] 게임 종료 및 초기화 완료.");
    }
}