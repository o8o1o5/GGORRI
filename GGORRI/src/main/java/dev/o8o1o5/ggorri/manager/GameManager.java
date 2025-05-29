package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.game.PlayerRole;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType; // PotionEffectType 임포트

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameManager {
    private final GGORRI plugin;
    private GameStatus currentStatus;
    private final Map<UUID, PlayerGameData> playersInGame;

    private final PlayerManager playerManager;

    private final Random random;
    private World gameWorld;

    private static final double INITIAL_BORDER_SIZE = 3200.0; // 초기 월드 보더 크기

    public GameManager(GGORRI plugin) {
        this.plugin = plugin;
        this.currentStatus = GameStatus.WAITING;
        this.playersInGame = new HashMap<>();
        this.random = new Random();

        this.playerManager = new PlayerManager(plugin, playersInGame);

        this.gameWorld = plugin.getServer().getWorld("world");
        if (this.gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드 'world'를 찾을 수 없습니다! 서버에 해당 월드가 존재하는지 확인해주세요.");
        } else {
            setupWorldBorder();
        }
    }

    /**
     * 게임 월드의 WorldBorder를 초기 설정합니다.
     */
    private void setupWorldBorder() {
        if (gameWorld == null) return;

        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(INITIAL_BORDER_SIZE);
        border.setWarningDistance(0); // 경고 거리는 일단 0으로 설정 (나중에 2.4단계에서 사용)
        border.setDamageAmount(0.0); // 데미지는 일단 0으로 설정 (나중에 2.4단계에서 사용)

        plugin.getLogger().info("[GGORRI] 게임 월드(" + gameWorld.getName() + ")의 월드 보더가 " + INITIAL_BORDER_SIZE + "x" + INITIAL_BORDER_SIZE + "으로 설정되었습니다.");
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

        setupPlayerTargets(); // 플레이어 타겟 설정
        spawnPlayers();       // 플레이어 스폰 및 초기화

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
        setupWorldBorder();

        currentStatus = GameStatus.WAITING;
        plugin.getLogger().info("[GGORRI] 게임 종료 및 초기화 완료.");
        return true;
    }

    /**
     * 플레이어들에게 꼬리 고리 타겟을 설정하는 로직
     */
    private void setupPlayerTargets() {
        List<UUID> shuffledPlayers = new ArrayList<>(playersInGame.keySet());
        Collections.shuffle(shuffledPlayers, random); // 플레이어 목록을 무작위로 섞음

        // 첫 번째 플레이어를 팀장으로 설정 (이 부분은 로드맵 1.5와 1.8에 따라 변경 가능)
        // 현재는 모든 플레이어가 LEADER로 시작하며, PvP로 인해 노예가 됨.
        // 첫 번째 플레이어를 '최초' 팀장으로 명시할 필요는 없지만, 게임 시작 시 모든 플레이어는 일단 LEADER 역할을 가짐.
        // 이 로직은 "각 플레이어를 개별 팀으로 초기화"에 가깝습니다.
        for (int i = 0; i < shuffledPlayers.size(); i++) {
            UUID currentPlayerUUID = shuffledPlayers.get(i);
            // 고리 형태를 만듭니다: 마지막 플레이어는 첫 번째 플레이어를 타겟
            UUID nextPlayerUUID = shuffledPlayers.get((i + 1) % shuffledPlayers.size());

            PlayerGameData currentPlayerGameData = playersInGame.get(currentPlayerUUID);
            if (currentPlayerGameData != null) {
                currentPlayerGameData.setDirectTargetUUID(nextPlayerUUID);
                // 모든 플레이어는 초기에 LEADER 역할을 가집니다.
                // 첫 처치에 의해 노예화되는 방식입니다.
                currentPlayerGameData.setRole(PlayerRole.LEADER);
            }
        }
        plugin.getLogger().info("[GGORRI] 플레이어 타겟 설정을 완료했습니다.");
    }

    /**
     * 게임 시작 시 플레이어 스폰 및 초기화 로직
     */
    private void spawnPlayers() {
        if (gameWorld == null) {
            plugin.getLogger().severe("[GGORRI] 게임 월드가 설정되지 않아 플레이어를 스폰할 수 없습니다!");
            playersInGame.keySet().forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(ChatColor.RED + "[GGORRI] 게임 월드가 설정되지 않아 게임 시작에 문제가 발생했습니다. 관리자에게 문의하세요.");
                }
            });
            return;
        }

        WorldBorder border = gameWorld.getWorldBorder();
        if (border == null || border.getSize() <= 0) {
            plugin.getLogger().severe("[GGORRI] 월드 보더가 유효하지 않거나 크기가 0입니다. 스폰 로직을 중단합니다.");
            playersInGame.keySet().forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) p.sendMessage(ChatColor.RED + "[GGORRI] 월드 보더 설정에 문제가 발생했습니다. 관리자에게 문의해주세요.");
            });
            return;
        }

        for (UUID playerUUID : playersInGame.keySet()) {
            Player player = plugin.getServer().getPlayer(playerUUID);
            if (player != null) {
                Location spawnLoc = findSafeSpawnLocation(gameWorld, 100);
                if (spawnLoc == null) {
                    plugin.getLogger().warning("[GGORRI] 플레이어 " + player.getName() + "를 위한 안전한 스폰 위치를 찾지 못했습니다. 월드 스폰으로 이동합니다.");
                    spawnLoc = gameWorld.getSpawnLocation(); // Fallback to world spawn
                    player.sendMessage(ChatColor.RED + "[GGORRI] 안전한 스폰 위치를 찾지 못해 월드 스폰으로 이동합니다.");
                }
                playerManager.resetPlayer(player); // 스폰 전 초기화
                player.teleport(spawnLoc);
                player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임 지역으로 텔레포트 되었습니다!");

                plugin.getLogger().info("[GGORRI] " + player.getName() + "님이 " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + "로 스폰되었습니다.");
            } else {
                plugin.getLogger().warning("[GGORRI] 게임 참가 플레이어(" + playerUUID + ")가 오프라인 상태입니다. 스폰되지 않았습니다.");
            }
        }
        plugin.getLogger().info("[GGORRI] 모든 참가 플레이어 스폰 및 초기화 완료");
    }

    /**
     * 주어진 월드의 WorldBorder 내에서 안전한 스폰 위치를 찾습니다.
     * @param world 스폰 위치를 찾을 월드
     * @param attempts 시도 횟수
     * @return 안전한 Location 객체 또는 null (찾지 못했을 경우)
     */
    private Location findSafeSpawnLocation(World world, int attempts) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double halfSize = border.getSize() / 2.0;

        plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 안전한 스폰 위치 찾기 시작 (최대 " + attempts + "회 시도)");
        plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 월드 보더 중심: (" + center.getX() + ", " + center.getZ() + ") | 크기: " + border.getSize());

        for (int i = 0; i < attempts; i++) {
            // 월드 보더 내에서 랜덤 좌표 생성
            double x = center.getX() + (random.nextDouble() * 2 - 1) * halfSize;
            double z = center.getZ() + (random.nextDouble() * 2 - 1) * halfSize;

            // 해당 x, z 좌표에서 가장 높은 블록의 Y 좌표를 찾음 (지상 기준)
            int y = world.getHighestBlockYAt((int) x, (int) z);
            Location potentialSpawn = new Location(world, x + 0.5, y + 1, z + 0.5); // 플레이어 발 밑 + 1블록

            if (isSafeSpawnLocation(potentialSpawn)) {
                plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 성공적으로 안전한 스폰 위치를 찾았습니다! (" + potentialSpawn.getBlockX() + ", " + potentialSpawn.getBlockY() + ", " + potentialSpawn.getBlockZ() + ")");
                return potentialSpawn;
            }
            if (i < 5 || (i + 1) % 10 == 0 || i == attempts - 1) { // 초반 몇 번, 10회마다, 마지막 시도 로그
                plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 시도 " + (i + 1) + "/" + attempts + ": (" + potentialSpawn.getBlockX() + ", " + potentialSpawn.getBlockY() + ", " + potentialSpawn.getBlockZ() + ") 에서 안전하지 않음.");
            }
        }
        plugin.getLogger().warning("[GGORRI] 안전한 스폰 위치를 찾지 못했습니다. 시도 횟수 소진 (" + attempts + "회)");
        return null;
    }

    /**
     * 주어진 위치가 안전한 스폰 위치인지 검사합니다.
     * @param loc 검사할 위치
     * @return 안전하면 true, 그렇지 않으면 false
     */
    private boolean isSafeSpawnLocation(Location loc) {
        if (!loc.getWorld().getWorldBorder().isInside(loc)) {
            plugin.getLogger().log(Level.FINE, "[GGORRI Debug] WorldBorder 밖: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            return false;
        }

        Block blockBelow = loc.clone().subtract(0, 1, 0).getBlock();
        Block blockAt = loc.getBlock();
        Block blockAbove = loc.clone().add(0, 1, 0).getBlock();

        // 1. 발 아래 블록이 밟을 수 있는 고체 블록인가? (공기, 물, 용암을 제한다.)
        // 또한 베드락은 제외 (갇힐 수 있으므로)
        if (!blockBelow.getType().isSolid() || blockBelow.getType() == Material.LAVA || blockBelow.getType() == Material.WATER || blockBelow.getType() == Material.BEDROCK) {
            plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 발 밑 위험/부적합 블록: " + blockBelow.getType() + " at " + blockBelow.getLocation());
            return false;
        }

        // 2. 플레이어 발 위치와 머리 위 위치가 공기 블록이거나 통과 가능한가?
        // (즉, 플레이어가 서있고 머리 위에 공간이 있는지)
        if (blockAt.getType().isSolid() || blockAbove.getType().isSolid()) {
            plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 플레이어 위치/머리 위 블록이 단단함: " + blockAt.getType() + ", " + blockAbove.getType() + " at " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            return false;
        }

        // 3. 주변에 위험한 블록 (용암, 물, 선인장, 마그마 블록)이 없는지 검사 (3x3x3 영역)
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    Block nearbyBlock = loc.clone().add(xOffset, yOffset, zOffset).getBlock();
                    Material mat = nearbyBlock.getType();
                    if (mat == Material.LAVA || mat == Material.WATER || mat == Material.CACTUS || mat == Material.MAGMA_BLOCK) {
                        plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 주변 위험 블록 발견: " + mat + " at " + nearbyBlock.getLocation());
                        return false;
                    }
                }
            }
        }

        // 4. 아래로 몇 블록까지 블록이 있는지 확인 (낙사 방지)
        // 5블록 이내에 착지할 수 있는 블록이 없으면 위험
        boolean foundGroundBelow = false;
        for (int y = -1; y >= -5; y--) { // 현재 y-1 (발 밑)부터 y-5까지
            if (loc.clone().add(0, y, 0).getBlock().getType().isSolid()) {
                foundGroundBelow = true;
                break;
            }
        }
        if (!foundGroundBelow) {
            plugin.getLogger().log(Level.FINE, "[GGORRI Debug] 낙사 위험: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            return false;
        }

        return true;
    }

    /**
     * 사망한 플레이어를 공격자의 노예로 종속시키고 꼬리 고리 구조를 업데이트합니다.
     * 이 메서드는 '정상 처치'가 발생했을 때 호출됩니다.
     *
     * @param killerUUID 사망한 플레이어를 처치한 공격자 (새로운 팀장)의 UUID
     * @param victimUUID 노예가 될 사망한 플레이어의 UUID
     */
    public void enslavePlayerAndAdjustTargets(UUID killerUUID, UUID victimUUID) {
        PlayerGameData killerData = playersInGame.get(killerUUID);
        PlayerGameData victimData = playersInGame.get(victimUUID);

        if (killerData == null || victimData == null) {
            plugin.getLogger().warning("[GGORRI] 노예화 실패: 유효하지 않은 플레이어 데이터. Killer: " + killerUUID + ", Victim: " + victimUUID);
            return;
        }

        // 1. 사망한 플레이어(victim)를 노예(SLAVE)로 설정
        victimData.setRole(PlayerRole.SLAVE);
        plugin.getLogger().info("[GGORRI] " + plugin.getServer().getPlayer(victimUUID).getName() + "님이 " + plugin.getServer().getPlayer(killerUUID).getName() + "님의 노예가 되었습니다.");

        // 2. 새로운 팀장(killer)의 직계 타겟을 노예(victim)의 이전 직계 타겟으로 변경
        // (이로써 꼬리 고리가 재연결됩니다.)
        UUID oldVictimTargetUUID = victimData.getDirectTargetUUID(); // 노예의 이전 직계 타겟

        killerData.setDirectTargetUUID(oldVictimTargetUUID); // 팀장의 타겟을 노예의 이전 타겟으로 변경
        plugin.getLogger().info("[GGORRI] " + plugin.getServer().getPlayer(killerUUID).getName() + "님의 새로운 타겟: " +
                (oldVictimTargetUUID != null ? plugin.getServer().getPlayer(oldVictimTargetUUID).getName() : "없음 (고리가 끊어짐)"));

        // 3. 노예(victim)는 더 이상 직계 타겟을 가지지 않습니다.
        victimData.setDirectTargetUUID(null); // 노예는 이제 타겟을 쫓지 않음
        plugin.getLogger().info("[GGORRI] " + plugin.getServer().getPlayer(victimUUID).getName() + "님은 이제 직계 타겟이 없습니다.");

        // TODO: (2단계: 승리 조건) 모든 플레이어가 한 팀에 종속되었는지 확인하는 로직 추가
        plugin.getServer().broadcastMessage(ChatColor.AQUA + "[GGORRI] " + plugin.getServer().getPlayer(victimUUID).getName() + "님이 " +
                plugin.getServer().getPlayer(killerUUID).getName() + "팀에 편입되었습니다!");
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

        Location spawnLoc = findSafeSpawnLocation(gameWorld, 100);
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
        setupWorldBorder();

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

    /**
     * Return unmodifiable map of PlayerGameData
     */
    public Map<UUID, PlayerGameData> getAllPlayersGameData() {
        return Collections.unmodifiableMap(playersInGame);
    }

    public World getGameWorld() {
        return gameWorld;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }
}