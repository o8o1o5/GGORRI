package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.game.PlayerRole;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Chain;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType; // PotionEffectType 임포트

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameManager {
    private final GGORRI plugin;
    public GameStatus currentStatus;
    private final Map<UUID, PlayerGameData> playersInGame;

    private final PlayerManager playerManager;
    private final SpawnManager spawnManager;
    private final ChainManager chainManager;
    private final GameRulesManager gameRulesManager;

    private World gameWorld;

    public GameManager(GGORRI plugin) {
        this.plugin = plugin;
        this.currentStatus = GameStatus.WAITING;
        this.playersInGame = new HashMap<>();

        this.playerManager = new PlayerManager(plugin, playersInGame);
        this.spawnManager = new SpawnManager(plugin);
        this.chainManager = new ChainManager(plugin, playersInGame);
        this.gameRulesManager = new GameRulesManager(plugin, this, playerManager, spawnManager, chainManager);

        this.gameWorld = plugin.getServer().getWorld("world");
        if (this.gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드 'world'를 찾을 수 없습니다! 서버에 해당 월드가 존재하는지 확인해주세요.");
        } else {
            spawnManager.setupWorldBorder(gameWorld);
        }
    }

    /**
     * indicating the current state of the game
     */
    public enum GameStatus {
        WAITING,   // 게임 시작 대기 중
        STARTING,  // 게임 시작 카운트다운 중 (옵션, 현재는 IN_GAME 직전 상태)
        IN_GAME,   // 게임 진행 중
        ENDING     // 게임 종료 중
    }

    /**
     * Engage the player in the game
     * @param player class
     * @return Successful participation
     */
    public boolean joinGame(Player player) {
        if (currentStatus != GameStatus.WAITING) {
            // 메시지는 Command Executor에서 처리
            return false;
        }
        if (playersInGame.containsKey(player.getUniqueId())) {
            // 메시지는 Command Executor에서 처리
            return false;
        }

        playersInGame.put(player.getUniqueId(), new PlayerGameData(player.getUniqueId()));
        plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 게임에 참가했습니다. 현재 " + playersInGame.size() + "명.");
        return true;
    }

    public boolean leaveGame(Player player) {
        if (!playersInGame.containsKey(player.getUniqueId())) {
            // 메시지는 Command Executor에서 처리
            return false;
        }

        // 게임 도중 나갈 경우 처리 (나중에 3단계: 플레이어 이탈 처리 로직에서 상세화)
        if (currentStatus == GameStatus.IN_GAME) {
            plugin.getLogger().warning("[GGORRI] " + player.getName() + "님이 게임 도중 이탈했습니다. (TODO: 페널티/고리 재설정)");
            playersInGame.remove(player.getUniqueId());
            playerManager.resetPlayer(player);
            player.teleport(gameWorld.getSpawnLocation()); // 로비 스폰으로 돌려보냄
            plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] " + player.getName() + "님이 게임에서 퇴장했습니다.");

            checkWinCondition();
            return true;
        }

        playersInGame.remove(player.getUniqueId());
        plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 게임에서 퇴장했습니다. 현재 " + playersInGame.size() + "명.");
        return true;
    }

    /**
     * Start the game (must be called by administrator)
     * @return true if game successfully started, false otherwise
     */
    public boolean startGame() {
        if (currentStatus != GameStatus.WAITING) {
            plugin.getLogger().warning("[GGORRI] 게임 시작 실패: 현재 상태가 WAITING이 아닙니다. (" + currentStatus.name() + ")");
            return false;
        }
        if (playersInGame.size() < 2) { // 최소 2명 이상이어야 게임 시작 가능
            plugin.getLogger().warning("[GGORRI] 게임 시작 실패: 최소 2명 이상의 플레이어가 필요합니다. (현재 " + playersInGame.size() + "명)");
            return false;
        }

        currentStatus = GameStatus.STARTING; // 시작 준비 상태
        plugin.getLogger().info("[GGORRI] 게임 시작 준비 중...");
        plugin.getServer().broadcastMessage(ChatColor.GREEN + "[GGORRI] 게임이 곧 시작됩니다! 참가자: " + playersInGame.size() + "명");

        chainManager.setupPlayerTargets(); // 플레이어 타겟 설정
        spawnManager.spawnPlayers(new ArrayList<>(playersInGame.keySet())); // 플레이어 스폰 및 초기화

        currentStatus = GameStatus.IN_GAME;
        plugin.getServer().broadcastMessage(ChatColor.GREEN + "§l[GGORRI] 게임 시작! 꼬리 고리를 따라가세요!");

        // FOR DEBUG: 꼬리 고리 관계 출력 추가
        plugin.getLogger().info("--- GGORRI 게임 시작! 현재 플레이어 및 타겟 관계 ---");
        playersInGame.forEach((uuid, data) -> {
            Player p = plugin.getServer().getPlayer(uuid);
            String playerName = (p != null) ? p.getName() : "Unknown";
            String targetName = "None";
            if (data.getDirectTargetUUID() != null) {
                Player targetPlayer = plugin.getServer().getPlayer(data.getDirectTargetUUID());
                targetName = (targetPlayer != null) ? targetPlayer.getName() : "Unknown";
            }
            plugin.getLogger().info(String.format("플레이어: %s (%s) | 타겟: %s", playerName, data.getRole(), targetName));
        });
        plugin.getLogger().info("--------------------------------------------------");
        return true;
    }

    /**
     * Forcefully shut down the game (must be called by administrator)
     * @return true if game successfully stopped, false otherwise
     */
    public boolean stopGame() {
        if (currentStatus == GameStatus.WAITING || currentStatus == GameStatus.ENDING) {
            plugin.getLogger().warning("[GGORRI] 게임 종료 실패: 게임이 활성화된 상태가 아닙니다. (현재 상태: " + currentStatus.name() + ")");
            return false;
        }

        currentStatus = GameStatus.ENDING;
        plugin.getLogger().info("[GGORRI] 게임 강제 종료 중...");
        plugin.getServer().broadcastMessage(ChatColor.RED + "§l[GGORRI] 게임이 강제로 종료되었습니다!");

        // 모든 플레이어 초기화 및 게임 데이터에서 제거
        for (UUID uuid : new ArrayList<>(playersInGame.keySet())) { // ConcurrentModificationException 방지
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                playerManager.resetPlayer(player); // 인벤토리 초기화, 기본 장비 제거 등
                if (gameWorld != null) {
                    player.teleport(gameWorld.getSpawnLocation()); // 로비 스폰 지점으로 이동
                } else {
                    player.sendMessage(ChatColor.RED + "[GGORRI] 월드 스폰 위치를 찾을 수 없습니다!");
                }
            }
            playersInGame.remove(uuid); // 게임 데이터에서 제거
        }
        playersInGame.clear(); // 혹시 남아있는 데이터가 있다면 모두 제거

        // 월드 보더 초기화
        spawnManager.setupWorldBorder(gameWorld);

        currentStatus = GameStatus.WAITING;
        plugin.getLogger().info("[GGORRI] 게임 종료 및 초기화 완료.");
        return true;
    }

    /**
     * 지정된 시간 후 플레이어를 부활시킵니다.
     * @param playerUUID 부활시킬 플레이어의 UUID
     * @param delayTicks 부활까지의 지연 시간 (틱)
     */
    public void schedulePlayerRespawn(UUID playerUUID, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerUUID);
            if (player != null && playersInGame.containsKey(playerUUID)) {
                respawnPlayer(player);
            } else if (player != null) {
                player.spigot().respawn();
            }
        }, delayTicks);
    }

    /**
     * 실제 플레이어를 부활시키는 로직.
     * 안전한 스폰 위치로 텔레포트하고, 게임 모드를 변경하며, 상태를 초기화합니다.
     * @param player 부활시킬 플레이어
     */
    private void respawnPlayer(Player player) {
        if (gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드가 설정되지 않아 플레이어를 부활시킬 수 없습니다.");
            player.sendMessage(ChatColor.RED + "[GGORRI] 게임 월드 오류로 부활할 수 없습니다.");
            player.spigot().respawn();
            return;
        }

        Location spawnLoc = spawnManager.findSafeSpawnLocation(gameWorld, spawnManager.getWorldBorderSize() ,100);
        if (spawnLoc == null) {
            plugin.getLogger().warning("[GGORRI] 플레이어 " + player.getName() + "를 위한 안전한 부활 위치를 찾지 못했습니다. 월드 스폰으로 이동합니다.");
            player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 부활 위치를 찾지 못해 월드 스폰으로 이동합니다.");
        }

        player.spigot().respawn();
        playerManager.resetPlayer(player);
        player.teleport(spawnLoc);
        player.sendMessage("[GGORRI] 부활했습니다! 다시 꼬리를 쫓으세요!");
    }

    /**
     * 현재 게임의 승리 조건을 확인합니다.
     * 모든 플레이어가 하나의 LEADER (팀장) 밑에 SLAVE로 종속되었는지 확인합니다.
     * @return 승리한 팀장의 UUID, 또는 null (아직 승리 조건 미충족 시)
     */
    public UUID checkWinCondition() {
        if (currentStatus != GameStatus.IN_GAME) {
            return null; // 게임 중이 아니면 승리 조건 확인하지 않음
        }

        // 현재 게임에 참여중인 플레이어들의 역할 목록을 가져옵니다.
        // 오프라인 플레이어는 제외 (isOnline() 사용)
        Map<UUID, PlayerGameData> activePlayers = playersInGame.entrySet().stream()
                .filter(entry -> plugin.getServer().getPlayer(entry.getKey()) != null && plugin.getServer().getPlayer(entry.getKey()).isOnline())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (activePlayers.isEmpty()) {
            plugin.getLogger().info("[GGORRI] 모든 플레이어가 게임에서 이탈하여 강제 종료됩니다.");
            endGame(null); // 모든 플레이어가 나가면 강제 종료
            return null;
        }

        // LEADER 역할을 가진 플레이어를 찾습니다.
        List<UUID> leaders = activePlayers.entrySet().stream()
                .filter(entry -> entry.getValue().getRole() == PlayerRole.LEADER)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 팀장이 한 명만 남았고, 나머지 모든 플레이어가 그 팀장의 노예라면 승리
        if (leaders.size() == 1) {
            UUID winningLeaderUUID = leaders.get(0);
            PlayerGameData winningLeaderData = playersInGame.get(winningLeaderUUID);

            // 해당 팀장의 직계 타겟이 자기 자신이라면 (고리가 끊어지고 하나의 팀만 남음)
            // 또는 모든 플레이어가 해당 팀장의 노예로 연결되어 있다면 (복잡한 연결 확인 필요)
            // 간단하게는, LEADER가 하나만 남고 나머지는 모두 SLAVE인 경우로 판단

            boolean allOthersAreSlaves = activePlayers.values().stream()
                    .allMatch(data -> data.getRole() == PlayerRole.SLAVE || data.getPlayerUUID().equals(winningLeaderUUID));

            if (allOthersAreSlaves) {
                // 승리 조건 충족: 단 하나의 LEADER만 남고, 나머지는 모두 SLAVE
                plugin.getLogger().info("[GGORRI] 승리 조건 충족! 승리한 팀장: " + plugin.getServer().getPlayer(winningLeaderUUID).getName());
                endGame(winningLeaderUUID);
                return winningLeaderUUID;
            }
        }

        // 게임에 플레이어가 한 명만 남았고, 그 플레이어가 LEADER인 경우 (혼자 남음)
        // 이 경우도 승리 조건으로 볼 수 있습니다. (마지막 한 명이 살아남음)
        if (activePlayers.size() == 1 && leaders.size() == 1) {
            UUID soleSurvivorUUID = leaders.get(0);
            plugin.getLogger().info("[GGORRI] 마지막 플레이어가 생존하여 승리했습니다: " + plugin.getServer().getPlayer(soleSurvivorUUID).getName());
            endGame(soleSurvivorUUID);
            return soleSurvivorUUID;
        }

        plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 현재 LEADER 수: " + leaders.size());
        return null; // 아직 승리 조건 미충족
    }

    /**
     * 게임을 종료하고 결과를 발표하며 모든 플레이어를 초기화합니다.
     * @param winnerLeaderUUID 승리한 팀장의 UUID (모든 플레이어가 이탈했을 경우 null)
     */
    public void endGame(UUID winnerLeaderUUID) {
        if (currentStatus == GameStatus.ENDING || currentStatus == GameStatus.WAITING) {
            return; // 이미 종료 중이거나 대기 중이면 무시
        }

        currentStatus = GameStatus.ENDING;
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
        // playersInGame 맵을 복사하여 ConcurrentModificationException 방지
        for (UUID uuid : new ArrayList<>(playersInGame.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                playerManager.resetPlayer(player); // 인벤토리, 체력, 게임모드 등 초기화
                // 게임 종료 후 로비 스폰 위치로 텔레포트
                if (gameWorld != null) {
                    player.teleport(gameWorld.getSpawnLocation());
                    player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임이 종료되어 로비로 이동했습니다.");
                } else {
                    player.sendMessage(ChatColor.RED + "[GGORRI] 월드 스폰 위치를 찾을 수 없어 현재 위치에 유지됩니다.");
                }
            }
            playersInGame.remove(uuid); // 게임 데이터에서 제거
        }
        playersInGame.clear(); // 혹시 남아있는 데이터가 있다면 모두 제거

        // 월드 보더 초기화 (게임 시작 전 상태로)
        spawnManager.setupWorldBorder(gameWorld);

        currentStatus = GameStatus.WAITING; // 게임 상태를 대기 중으로 변경
        plugin.getLogger().info("[GGORRI] 게임 종료 및 초기화 완료.");
    }

    /**
     * Return current status of the game
     */
    public GameStatus getCurrentStatus() {
        return currentStatus;
    }

    /**
     * Return the number of players in game
     */
    public int getPlayersInGameCount() {
        return playersInGame.size();
    }

    /**
     * Return HashSet of player UUIDs
     */
    public Set<UUID> getPlayersInGameUUIDs() {
        return new HashSet<>(playersInGame.keySet());
    }

    public World getGameWorld() {
        return gameWorld;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public ChainManager getChainManager() {
        return chainManager;
    }
}