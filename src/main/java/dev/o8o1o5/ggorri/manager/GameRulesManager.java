package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.game.PlayerRole;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameRulesManager {
    private final GGORRI plugin;
    private final GameManager gameManager;
    private final PlayerManager playerManager;
    private final SpawnManager spawnManager;
    private final ChainManager chainManager;
    private final BorderManager borderManager;
    private final Random random;

    public GameRulesManager(GGORRI plugin, GameManager gameManager, PlayerManager playerManager, SpawnManager spawnManager, ChainManager chainManager, BorderManager borderManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.playerManager = playerManager;
        this.spawnManager = spawnManager;
        this.chainManager = chainManager;
        this.borderManager = borderManager;
        this.random = new Random();
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
            // 이 경우, 플레이어가 스펙테이터 모드로 고정될 수 있으므로,
            // 기본 리스폰 로직으로 되돌리는 등의 예외 처리 필요
            deadPlayer.setGameMode(GameMode.SURVIVAL); // 비정상적인 경우 바로 서바이벌로 되돌림
            deadPlayer.spigot().respawn();
            return;
        }

        plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + " 사망! 원인: " + damageCause.name());

        deadPlayerData.incrementDeathCount();

        // 리스폰 지연 시간 계산
        long baseRespawnDelaySeconds = 60;
        long calculatedRespawnDelaySeconds = (long) (baseRespawnDelaySeconds * Math.pow(1.5, deadPlayerData.getDeathCount() - 1));
        calculatedRespawnDelaySeconds = Math.max(1, calculatedRespawnDelaySeconds);
        calculatedRespawnDelaySeconds = Math.min(600, calculatedRespawnDelaySeconds);

        // 킬러 판정
        UUID actualKillerUUID = null;
        if (killer != null) {
            actualKillerUUID = killer.getUniqueId();
        } else {
            actualKillerUUID = playerManager.getLastAttacker(deadUUID);
            if (actualKillerUUID != null && !playerManager.getAllPlayersGameData().containsKey(actualKillerUUID)) {
                actualKillerUUID = null;
            }
        }

        boolean spawnNearTeamLeader = false;

        // **여기부터 수정된 부분입니다.**
        // 킬 타입 판정 및 처리
        if (actualKillerUUID != null && playerManager.getAllPlayersGameData().containsKey(actualKillerUUID)) {
            PlayerGameData killerPlayerData = playerManager.getPlayerGameData(actualKillerUUID);
            if (killerPlayerData != null) { // 킬러 데이터가 유효한 경우
                // **노예 플레이어가 사망했을 때의 특별 처리**
                if (deadPlayerData.getRole() == PlayerRole.SLAVE) {
                    plugin.getLogger().info("[GGORRI] 노예 " + deadPlayer.getName() + "이(가) " + plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() + "에게 처치됨 (노예). 일반 킬 처리.");
                    // 노예 사망 시 리더 근처 스폰 여부는 게임 규칙에 따라 결정 (여기서는 기존 로직 유지)
                    spawnNearTeamLeader = true;
                } else if (killerPlayerData.getRole() == PlayerRole.LEADER) {
                    if (killerPlayerData.getDirectTargetUUID() != null && killerPlayerData.getDirectTargetUUID().equals(deadUUID)) {
                        plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) " + plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() + "에게 정상 처치됨.");
                        handleNormalKill(deadPlayer, plugin.getServer().getPlayer(actualKillerUUID));
                        spawnNearTeamLeader = true;
                    } else {
                        plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) " + plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() + "에게 잘못된 타겟으로 처치됨.");
                        handleWrongTargetKill(deadPlayer, plugin.getServer().getPlayer(actualKillerUUID));
                        spawnNearTeamLeader = true;
                    }
                } else { // 킬러는 있으나 리더가 아니거나 노예가 아닌 플레이어
                    plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) (비유효한 킬러: " + (killer != null ? killer.getName() : "없음") + " / 트래커 킬러: " + (actualKillerUUID != null ? plugin.getServer().getOfflinePlayer(actualKillerUUID).getName() : "없음") + ")에게 사망. 자연사로 처리.");
                    Bukkit.broadcastMessage(ChatColor.GRAY + deadPlayer.getName() + "님이 사망했습니다. (자연사)");
                }
            } else { // 킬러 playerData가 null (이상한 상황이지만 예외 처리)
                plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) 자연사했습니다. (킬러PlayerData 없음)");
                Bukkit.broadcastMessage(ChatColor.GRAY + deadPlayer.getName() + "님이 사망했습니다. (자연사)");
            }
        } else {
            plugin.getLogger().info("[GGORRI] " + deadPlayer.getName() + "이(가) 자연사했습니다. (킬러 없음 / 유효하지 않은 킬러)");
            Bukkit.broadcastMessage(ChatColor.GRAY + deadPlayer.getName() + "님이 사망했습니다. (자연사)");
        }

        List<ItemStack> playerInventoryItems = new ArrayList<>();
        // 주 인벤토리 아이템 복사
        for (ItemStack item : deadPlayer.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                playerInventoryItems.add(item.clone());
            }
        }
        // 갑옷 아이템 복사
        for (ItemStack item : deadPlayer.getInventory().getArmorContents()) {
            if (item != null && item.getType() != Material.AIR) {
                playerInventoryItems.add(item.clone());
            }
        }
        // 오프핸드 아이템 복사
        ItemStack offHand = deadPlayer.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            playerInventoryItems.add(offHand.clone());
        }

        Collections.shuffle(playerInventoryItems, random); // 아이템 순서를 무작위로 섞음

        // 보존할 아이템 개수 계산: 전체 아이템의 절반
        int preservedItemsCount = playerInventoryItems.size() / 2;
        if (preservedItemsCount == 0 && !playerInventoryItems.isEmpty()) {
            preservedItemsCount = 1; // 아이템이 하나라도 있으면 최소 1개는 보존
        }

        List<ItemStack> preservedItems = new ArrayList<>();
        // 셔플된 리스트에서 계산된 개수만큼의 아이템을 preservedItems에 추가
        for (int i = 0; i < preservedItemsCount; i++) {
            if (i >= playerInventoryItems.size()) break; // 리스트 범위를 벗어나지 않도록
            ItemStack item = playerInventoryItems.get(i);
            preservedItems.add(item);
        }

        // --- 이 부분이 중요합니다. ---
        // 플레이어의 인벤토리를 완전히 비웁니다.
        // 이는 '보존되지 않은 나머지 절반의 아이템'을 사라지게 하는 역할을 합니다.
        deadPlayer.getInventory().clear();
        deadPlayer.getInventory().setArmorContents(null);
        deadPlayer.getInventory().setItemInOffHand(null);
        // --- 아이템 보존 로직 끝 ---

        // schedulePlayerRespawn 호출 시 preservedItems (보존된 절반의 아이템)을 함께 전달합니다.
        schedulePlayerRespawn(deadUUID, calculatedRespawnDelaySeconds * 20L, spawnNearTeamLeader, preservedItems);

        playerManager.clearDamageRecordsForPlayer(deadUUID);
        checkWinCondition();
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
            deadPlayerData.setMasterUUID(killerUUID);
            deadPlayerData.setDirectTargetUUID(null);
            Bukkit.broadcastMessage(ChatColor.YELLOW + "§l" + killer.getName() + "님이 잘못된 타겟인 " + deadPlayer.getName() + "을(를) 처치했습니다!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + deadPlayer.getName() + "님은 이제 " + killer.getName() + "님의 노예가 됩니다.");
        }

        // TODO: 잘못 처치한 플레이어(killer)에게 패널티 부여 로직 추가 (예: 디버프)
    }

    /**
     * 지정된 시간 후 플레이어를 부활시킵니다.
     * @param playerUUID 부활시킬 플레이어의 UUID
     * @param delayTicks 부활까지의 지연 시간 (틱)
     * @param spawnNearTeamLeader 팀장 근처에서 부활할지 여부 (true: 팀장 근처, false: 일반 스폰)
     */
    public void schedulePlayerRespawn(UUID playerUUID, long delayTicks, boolean spawnNearTeamLeader, List<ItemStack> preservedItems) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = plugin.getServer().getPlayer(playerUUID);
                if (player != null && player.isOnline() && playerManager.getAllPlayersGameData().containsKey(playerUUID)) {
                    // respawnPlayer 호출 시 preservedItems 전달
                    respawnPlayer(player, spawnNearTeamLeader, preservedItems); // <-- 변경된 부분

                    // 부활 후 액션바 메시지 등은 respawnPlayer 또는 BorderManager에서 처리
                } else if (player != null && player.isOnline()) {
                    player.spigot().respawn();
                }
            }
        }.runTaskLater(plugin, delayTicks);
    }

    private void respawnPlayer(Player player, boolean spawnNearTeamLeader, List<ItemStack> preservedItems) { // preservedItems 인자 유지
        PlayerGameData playerData = playerManager.getPlayerGameData(player.getUniqueId());
        if (playerData == null) {
            plugin.getLogger().warning("[GGORRI] 부활하려는 플레이어(" + player.getName() + ")의 게임 데이터가 없습니다.");
            player.spigot().respawn(); // 게임 데이터가 없는 경우 기본 리스폰
            return;
        }

        // 플레이어의 게임 모드를 SURVIVAL로 변경 (onPlayerDeath에서 스펙테이터로 설정했으므로 여기서 다시 돌립니다.)
        player.setGameMode(GameMode.SURVIVAL);

        Location spawnLoc = null;

        if (spawnNearTeamLeader) {
            UUID teamLeaderUUID = findTeamLeaderForSlave(player.getUniqueId());
            if (teamLeaderUUID != null) {
                Player teamLeader = plugin.getServer().getPlayer(teamLeaderUUID);
                if (teamLeader != null && teamLeader.isOnline()) {
                    spawnLoc = spawnManager.findSafeSpawnLocation(teamLeader.getLocation(), 100, 10);
                    if (spawnLoc == null) {
                        plugin.getLogger().warning("[GGORRI] " + player.getName() + " (노예)를 위한 팀장 근처 안전 스폰 위치를 찾지 못했습니다. 일반 스폰으로 이동합니다.");
                        player.sendMessage(ChatColor.RED + "[GGORRI] 팀장 근처 부활 위치를 찾지 못해 일반 스폰으로 이동합니다.");
                    } else {
                        player.sendMessage(ChatColor.GREEN + "[GGORRI] 팀장 근처에서 부활했습니다!");
                    }
                } else {
                    plugin.getLogger().warning("[GGORRI] " + player.getName() + " (노예)의 팀장(" + (teamLeaderUUID != null ? plugin.getServer().getOfflinePlayer(teamLeaderUUID).getName() : "없음") + ")이 오프라인이거나 유효하지 않습니다. 일반 스폰으로 이동합니다.");
                    player.sendMessage(ChatColor.YELLOW + "[GGORRI] 팀장이 오프라인이거나 찾을 수 없어 일반 스폰으로 이동합니다.");
                }
            } else {
                plugin.getLogger().warning("[GGORRI] " + player.getName() + " (노예)의 팀 리더 UUID를 찾을 수 없습니다. 일반 스폰으로 이동합니다.");
                player.sendMessage(ChatColor.YELLOW + "[GGORRI] 팀 정보를 찾을 수 없어 일반 스폰으로 이동합니다.");
            }
        }

        if (spawnLoc == null) {
            // 전역 스폰 위치 찾기
            spawnLoc = spawnManager.findSafeSpawnLocation(spawnManager.getGameWorld(), (int)spawnManager.getGameWorld().getWorldBorder().getSize(), 100);
            if (spawnLoc == null) {
                plugin.getLogger().warning("[GGORRI] 플레이어 " + player.getName() + "를 위한 안전한 부활 위치를 찾지 못했습니다. 월드 스폰으로 이동합니다.");
                player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 부활 위치를 찾지 못해 월드 스폰으로 이동합니다.");
                spawnLoc = spawnManager.getGameWorld().getSpawnLocation(); // 최종 fallback
            } else {
                player.sendMessage(ChatColor.GREEN + "[GGORRI] 일반 스폰 지점에서 부활했습니다!");
            }
        }

        // 먼저 플레이어의 모든 상태(인벤토리 포함)를 초기화합니다.
        // resetPlayer 메서드가 인벤토리를 clear() 하므로, 이 시점에서 인벤토리가 비워집니다.
        playerManager.resetPlayer(player);

        // resetPlayer 호출 후, 보존된 아이템을 플레이어 인벤토리에 다시 추가합니다.
        if (!preservedItems.isEmpty()) {
            player.getInventory().addItem(preservedItems.toArray(new ItemStack[0]));
        }

        // 플레이어를 최종 스폰 위치로 텔레포트합니다.
        player.teleport(spawnLoc);
        player.sendMessage("[GGORRI] 부활했습니다! 다시 꼬리를 쫓으세요!");

        // 부활 후 무적 효과 및 화염 저항 효과를 부여합니다.
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 5, 255, false, false)); // 5초 무적
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 5, 0, false, false)); // 5초 화염 저항

        plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + "로 부활했습니다.");
    }

    public UUID findTeamLeaderForSlave(UUID slaveUUID) {
        // !!! 수정: masterUUID 필드를 활용하도록 로직 간소화 !!!
        PlayerGameData slaveData = playerManager.getPlayerGameData(slaveUUID);
        if (slaveData == null) {
            plugin.getLogger().warning("[GGORRI] 노예(" + plugin.getServer().getOfflinePlayer(slaveUUID).getName() + ")의 게임 데이터를 찾을 수 없습니다.");
            return null;
        }

        if (slaveData.getRole() == PlayerRole.LEADER) {
            // 스스로 리더인 경우, 자신의 UUID를 반환 (자기 자신이 팀장)
            return slaveUUID;
        } else if (slaveData.getRole() == PlayerRole.SLAVE) {
            // 노예인 경우, masterUUID를 반환
            if (slaveData.getMasterUUID() != null) {
                return slaveData.getMasterUUID();
            } else {
                plugin.getLogger().warning("[GGORRI] 노예(" + plugin.getServer().getOfflinePlayer(slaveUUID).getName() + ")의 masterUUID가 설정되지 않았습니다. (오류 가능성)");
                // masterUUID가 없으면, 기존의 고리 탐색 로직을 대체할 방안이 필요합니다.
                // 현재는 이 시나리오가 발생하지 않도록 위에서 masterUUID를 확실히 설정했습니다.
                // 따라서 이 부분은 사실상 도달하지 않거나, 초기 설정 오류 시에만 발생합니다.
                return null;
            }
        }
        return null; // 알 수 없는 역할
    }

    /**
     * 현재 게임의 승리 조건을 확인합니다.
     * 모든 플레이어가 하나의 LEADER (팀장) 밑에 SLAVE로 종속되었는지 확인합니다.
     * @return 승리한 팀장의 UUID, 또는 null (아직 승리 조건 미충족 시)
     */
    public UUID checkWinCondition() {
        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            plugin.getLogger().log(Level.FINE, "[GGORRI] checkWinCondition: 게임 중이 아님. 현재 상태: " + gameManager.getCurrentStatus());
            return null;
        }

        Map<UUID, PlayerGameData> activePlayers = playerManager.getAllPlayersGameData().entrySet().stream()
                .filter(entry -> plugin.getServer().getPlayer(entry.getKey()) != null && plugin.getServer().getPlayer(entry.getKey()).isOnline())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        plugin.getLogger().info("[GGORRI] checkWinCondition: 현재 활성 플레이어 수: " + activePlayers.size());
        activePlayers.forEach((uuid, data) -> plugin.getLogger().info(" - " + plugin.getServer().getOfflinePlayer(uuid).getName() + " (역할: " + data.getRole() + ", 마스터: " + (data.getMasterUUID() != null ? plugin.getServer().getOfflinePlayer(data.getMasterUUID()).getName() : "없음") + ")"));


        if (activePlayers.isEmpty()) {
            plugin.getLogger().info("[GGORRI] checkWinCondition: 모든 플레이어가 게임에서 이탈하여 강제 종료됩니다.");
            endGame(null); // 모든 플레이어가 나가면 강제 종료
            return null;
        }

        List<UUID> leaders = activePlayers.entrySet().stream()
                .filter(entry -> entry.getValue().getRole() == PlayerRole.LEADER)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        plugin.getLogger().info("[GGORRI] checkWinCondition: 현재 LEADER 수: " + leaders.size());
        leaders.forEach(uuid -> plugin.getLogger().info(" - 리더: " + plugin.getServer().getOfflinePlayer(uuid).getName()));

        // 승리 조건: 리더가 1명만 남았을 때
        if (leaders.size() == 1) {
            UUID winningLeaderUUID = leaders.get(0);
            plugin.getLogger().info("[GGORRI] checkWinCondition: 리더가 한 명만 남았습니다: " + plugin.getServer().getOfflinePlayer(winningLeaderUUID).getName());

            // 이 리더를 제외한 모든 게임 내 플레이어가 SLAVE인지 확인
            // (즉, 모든 플레이어가 winningLeaderUUID를 마스터로 가지거나, 자기 자신이 winningLeaderUUID인 경우)
            boolean allOthersAreSlaves = activePlayers.values().stream()
                    .allMatch(data -> data.getPlayerUUID().equals(winningLeaderUUID) || // 자기 자신이 리더인 경우
                            (data.getRole() == PlayerRole.SLAVE && Objects.equals(data.getMasterUUID(), winningLeaderUUID))); // 또는 그 리더의 노예인 경우

            plugin.getLogger().info("[GGORRI] checkWinCondition: 모든 다른 플레이어가 노예인지 여부: " + allOthersAreSlaves);

            if (allOthersAreSlaves) {
                plugin.getLogger().info("[GGORRI] 승리 조건 충족! 승리한 팀장: " + plugin.getServer().getOfflinePlayer(winningLeaderUUID).getName());
                endGame(winningLeaderUUID);
                return winningLeaderUUID;
            }
        }

        // 추가: 혼자 남은 경우도 승리
        if (activePlayers.size() == 1 && leaders.size() == 1) {
            UUID soleSurvivorUUID = leaders.get(0);
            plugin.getLogger().info("[GGORRI] checkWinCondition: 마지막 플레이어가 생존하여 승리했습니다: " + plugin.getServer().getOfflinePlayer(soleSurvivorUUID).getName());
            endGame(soleSurvivorUUID);
            return soleSurvivorUUID;
        }

        plugin.getLogger().log(Level.FINE, "[GGORRI] 현재 LEADER 수: " + leaders.size() + ", 게임 내 플레이어 수: " + activePlayers.size() + ". 승리 조건 미충족.");
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