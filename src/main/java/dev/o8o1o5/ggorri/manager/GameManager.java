package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.game.PlayerRole;
import dev.o8o1o5.ggorri.listeners.GameListener;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Chain;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType; // PotionEffectType 임포트
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameManager {
    private final GGORRI plugin;
    private final PlayerManager playerManager;
    private final SpawnManager spawnManager;
    private final ChainManager chainManager;
    private final GameRulesManager gameRulesManager;
    private final BorderManager borderManager;
    private final ActionBarManager actionBarManager;

    public Map<UUID, PlayerGameData> playersInGame; // 모든 매니저가 공유

    public GameStatus currentStatus;

    private BukkitTask gameStartCountdownTask;
    private BukkitTask winConditionCheckTask; // 추가: 승리 조건 주기적 확인 태스크

    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 10;

    public GameManager(GGORRI plugin) {
        this.plugin = plugin;
        this.playersInGame = new ConcurrentHashMap<>(); // ConcurrentHashMap 사용
        this.playerManager = new PlayerManager(plugin, playersInGame);
        this.spawnManager = new SpawnManager(plugin);
        this.actionBarManager = new ActionBarManager(plugin);
        this.borderManager = new BorderManager(plugin, actionBarManager, playersInGame); // BorderManager에 playersInGame 전달
        this.chainManager = new ChainManager(plugin, playersInGame);
        this.gameRulesManager = new GameRulesManager(plugin, this, playerManager, spawnManager, chainManager, borderManager);

        this.currentStatus = GameStatus.WAITING;

        // 액션바 시스템 시작
        actionBarManager.startDisplaying();
    }

    /**
     * indicating the current state of the game
     */
    public enum GameStatus {
        WAITING, // 게임 대기 중
        COUNTDOWN, // 게임 시작 카운트다운 중
        IN_GAME,   // 게임 진행 중
        ENDING     // 게임 종료 처리 중
    }

    /**
     * 게임 시작 카운트다운을 시작합니다.
     * @param seconds 카운트다운 시간
     */
    public void startCountdown(int seconds) {
        if (currentStatus != GameStatus.WAITING) {
            plugin.getLogger().warning("[GGORRI] 게임이 이미 시작되었거나 진행 중입니다. 카운트다운을 시작할 수 없습니다.");
            return;
        }

        if (playersInGame.size() < 2) { // 최소 2명 이상이어야 게임 시작
            plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] 게임 시작을 위해 최소 2명 이상의 플레이어가 필요합니다!");
            return;
        }

        currentStatus = GameStatus.COUNTDOWN;
        plugin.getServer().broadcastMessage(ChatColor.GREEN + "[GGORRI] 게임이 " + seconds + "초 후에 시작됩니다!");

        gameStartCountdownTask = new BukkitRunnable() {
            int countdown = seconds;

            @Override
            public void run() {
                if (countdown <= 0) {
                    startGame();
                    this.cancel();
                    return;
                }

                if (countdown <= 5 || countdown % 10 == 0) {
                    plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 게임 시작까지 " + countdown + "초 남았습니다!");
                }
                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L); // 1초마다 실행
    }

    /**
     * 진행 중인 게임 시작 카운트다운을 취소합니다.
     */
    public void cancelCountdown() {
        if (gameStartCountdownTask != null) {
            gameStartCountdownTask.cancel();
            gameStartCountdownTask = null;
            plugin.getLogger().info("[GGORRI] 게임 시작 카운트다운이 취소되었습니다.");
            if (currentStatus == GameStatus.COUNTDOWN) {
                currentStatus = GameStatus.WAITING; // 카운트다운 취소 시 대기 상태로 전환
            }
        }
    }


    /**
     * 플레이어를 게임에 참가시킵니다.
     * @param player 게임에 참가할 플레이어
     * @return 성공적으로 참가하면 true, 실패하면 false
     */
    public boolean joinGame(Player player) {
        if (currentStatus != GameStatus.WAITING) {
            player.sendMessage(ChatColor.RED + "[GGORRI] 게임이 이미 시작되었거나 진행 중입니다. 다음 게임을 기다려주세요.");
            return false;
        }

        if (playersInGame.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "[GGORRI] 이미 게임에 참가 중입니다.");
            return false;
        }

        if (playersInGame.size() >= MAX_PLAYERS) {
            player.sendMessage(ChatColor.RED + "[GGORRI] 게임이 가득 찼습니다! (최대 " + MAX_PLAYERS + "명)");
            return false;
        }

        playerManager.addPlayerToGame(player);
        playerManager.resetPlayer(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(spawnManager.getGameWorld().getSpawnLocation());

        player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임에 참가했습니다!"); // 플레이어에게 직접 메시지
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + player.getName() + "님이 게임에 참가했습니다! (" + playersInGame.size() + "/" + MAX_PLAYERS + "명)");
        plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 게임에 참가했습니다. 현재 " + playersInGame.size() + "명.");

        if (playersInGame.size() >= MIN_PLAYERS && currentStatus == GameStatus.WAITING && (gameStartCountdownTask == null || gameStartCountdownTask.isCancelled())) {
            startCountdown(30);
        }

        return true;
    }

    /**
     * 플레이어를 게임에서 퇴장시킵니다.
     * @param player 게임에서 퇴장할 플레이어
     * @return 성공적으로 퇴장하면 true, 실패하면 false
     */
    public boolean leaveGame(Player player) {
        if (!playersInGame.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "[GGORRI] 게임에 참가 중이 아닙니다."); // 플레이어에게 직접 메시지
            return false;
        }

        plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 게임에서 퇴장 시도.");

        if (currentStatus == GameStatus.IN_GAME) {
            // gameRulesManager.handlePlayerExit(player.getUniqueId()); // 현재는 주석 처리된 상태
            player.sendMessage(ChatColor.RED + "[GGORRI] 게임 도중 이탈하여 패널티를 받았습니다."); // 게임 중 이탈 시 메시지
            plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 게임 도중 이탈했습니다.");
        } else {
            plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 대기 중 게임에서 나갔습니다.");
        }

        PlayerGameData leavingPlayerData = playerManager.getPlayerGameData(player.getUniqueId());
        if (leavingPlayerData != null && leavingPlayerData.getRole() == PlayerRole.LEADER) {
            chainManager.handleLeaderExit(player.getUniqueId());
        }

        playerManager.removePlayerFromGame(player);
        player.teleport(spawnManager.getGameWorld().getSpawnLocation());

        player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임에서 성공적으로 퇴장했습니다."); // 플레이어에게 직접 메시지
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + player.getName() + "님이 게임에서 퇴장했습니다! (" + playersInGame.size() + "명 남음)");


        if (playersInGame.size() < MIN_PLAYERS && currentStatus == GameStatus.COUNTDOWN) {
            cancelCountdown();
            plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] 최소 인원 미달로 게임 시작 카운트다운이 취소되었습니다.");
        }

        if (currentStatus == GameStatus.IN_GAME) {
            gameRulesManager.checkWinCondition();
        }

        if (playersInGame.isEmpty() && currentStatus != GameStatus.WAITING && currentStatus != GameStatus.ENDING) {
            plugin.getLogger().info("[GGORRI] 모든 플레이어가 게임에서 이탈하여 강제 종료됩니다.");
            endGame(null);
        }

        return true;
    }

    /**
     * 게임을 시작합니다.
     * @return 게임 시작이 성공하면 true, 실패하면 false
     */
    public boolean startGame() {
        if (currentStatus == GameStatus.IN_GAME) {
            plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] 게임이 이미 진행 중입니다. 새로운 게임을 시작할 수 없습니다."); // 브로드캐스트 메시지
            plugin.getLogger().warning("[GGORRI] 게임이 이미 진행 중입니다.");
            return false;
        }

        if (playersInGame.size() < MIN_PLAYERS) {
            plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] 게임 시작을 위해 최소 " + MIN_PLAYERS + "명 이상의 플레이어가 필요합니다! (현재 " + playersInGame.size() + "명)"); // 브로드캐스트 메시지
            plugin.getLogger().warning("[GGORRI] 게임 시작 실패: 최소 플레이어 수 부족 (" + playersInGame.size() + "/" + MIN_PLAYERS + ")");
            return false;
        }

        if (currentStatus == GameStatus.COUNTDOWN && gameStartCountdownTask != null) {
            gameStartCountdownTask.cancel();
            gameStartCountdownTask = null;
            plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 관리자 명령으로 게임 시작 카운트다운이 중단되었습니다."); // 브로드캐스트 메시지
            plugin.getLogger().info("[GGORRI] 수동 게임 시작으로 인해 카운트다운이 취소되었습니다.");
        }

        currentStatus = GameStatus.IN_GAME;
        plugin.getServer().broadcastMessage(ChatColor.GREEN + "§l[GGORRI] 게임 시작! 꼬리 고리 술래잡기가 시작됩니다!");
        plugin.getLogger().info("[GGORRI] 게임이 성공적으로 시작되었습니다. 참여 플레이어: " + playersInGame.size() + "명");

        spawnManager.setupWorldBorder(spawnManager.getGameWorld());
        chainManager.setupPlayerTargets();
        List<UUID> activePlayerUUIDs = new ArrayList<>(playersInGame.keySet());
        spawnManager.spawnPlayers(activePlayerUUIDs);
        borderManager.startBorderSystem();

        if (winConditionCheckTask != null) {
            winConditionCheckTask.cancel();
        }
        winConditionCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                gameRulesManager.checkWinCondition();
            }
        }.runTaskTimer(plugin, 20 * 5L, 20 * 10L);

        playerManager.clearLastAttackers();

        return true;
    }

    /**
     * 게임을 완전히 중지하고 초기 상태로 되돌립니다.
     * @return 게임 중지가 성공하면 true, 실패하면 false
     */
    public boolean stopGame() {
        if (currentStatus == GameStatus.WAITING || currentStatus == GameStatus.ENDING) {
            plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 게임이 이미 대기 중이거나 종료 중입니다."); // 브로드캐스트 메시지
            plugin.getLogger().warning("[GGORRI] 게임이 이미 대기 중이거나 종료 중입니다.");
            return false;
        }

        plugin.getLogger().info("[GGORRI] 관리자에 의해 게임 강제 종료 요청.");

        if (gameStartCountdownTask != null) {
            gameStartCountdownTask.cancel();
            gameStartCountdownTask = null;
        }
        if (winConditionCheckTask != null) {
            winConditionCheckTask.cancel();
            winConditionCheckTask = null;
        }
        borderManager.stopBorderSystem();
        actionBarManager.stopDisplaying();

        plugin.getServer().broadcastMessage(ChatColor.RED + "§l[GGORRI] 관리자에 의해 게임이 강제 종료되었습니다!");
        endGame(null);
        plugin.getLogger().info("[GGORRI] 게임이 관리자에 의해 강제 중지되었습니다.");
        return true;
    }

    /**
     * 게임을 종료하고 결과를 발표합니다.
     * @param winnerLeaderUUID 승리한 팀장의 UUID (승자가 없는 경우 null)
     */
    public void endGame(UUID winnerLeaderUUID) {
        if (currentStatus == GameStatus.ENDING) {
            plugin.getLogger().warning("[GGORRI] 이미 게임 종료 처리 중입니다. 중복 호출 무시.");
            return;
        }
        if (currentStatus == GameStatus.WAITING) {
            plugin.getLogger().warning("[GGORRI] 게임이 대기 중입니다. 종료할 게임이 없습니다.");
            return;
        }

        currentStatus = GameStatus.ENDING;
        plugin.getLogger().info("[GGORRI] 게임 종료 중...");

        // 모든 스케줄러 중지
        if (gameStartCountdownTask != null) gameStartCountdownTask.cancel();
        if (winConditionCheckTask != null) winConditionCheckTask.cancel();
        borderManager.stopBorderSystem(); // 자기장 시스템 중지

        String winnerMessage;
        if (winnerLeaderUUID != null) {
            Player winner = plugin.getServer().getPlayer(winnerLeaderUUID);
            String winnerName = (winner != null) ? winner.getName() : plugin.getServer().getOfflinePlayer(winnerLeaderUUID).getName();
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
                // 플레이어가 온라인 상태일 때만 텔레포트
                if (player.isOnline()) {
                    if (spawnManager.getGameWorld() != null) {
                        player.teleport(spawnManager.getGameWorld().getSpawnLocation());
                        player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임이 종료되어 로비로 이동했습니다.");
                    } else {
                        player.sendMessage(ChatColor.RED + "[GGORRI] 월드 스폰 위치를 찾을 수 없어 현재 위치에 유지됩니다.");
                    }
                }
            }
            // OfflinePlayer도 처리할 수 있도록 playerManager.removePlayerFromGame 수정 (UUID 기반)
            playerManager.removePlayerFromGame(plugin.getServer().getPlayer(uuid)); // 게임 데이터에서 제거 (맵에서 제거)
        }
        playersInGame.clear(); // 확실하게 비우기 (removePlayerFromGame이 이미 제거하지만, 혹시 모를 상황 대비)


        // 월드 보더 초기화 (SpawnManager에게 위임)
        spawnManager.setupWorldBorder(spawnManager.getGameWorld());

        currentStatus = GameStatus.WAITING;
        plugin.getLogger().info("[GGORRI] 게임 종료 및 초기화 완료.");
        // 액션바 시스템은 GameManager 생성 시 시작되므로, endGame에서는 굳이 다시 시작하지 않습니다.
        // 다음 게임 시작 시 자연스럽게 액션바 메시지가 갱신될 것입니다.
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

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public ChainManager getChainManager() {
        return chainManager;
    }

    public GameRulesManager getGameRulesManager() {
        return gameRulesManager;
    }

    public ActionBarManager getActionBarManager() {
        return actionBarManager;
    }
}