package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class BorderManager {
    private final GGORRI plugin;
    private final Map<UUID, PlayerGameData> playersInGame;
    private final Random random;

    // 자기장 상태 및 진행 관련 변수
    private WorldBorder gameBorder;
    private Location currentBorderCenter;
    private double currentBorderSize;
    private int currentPhase; // 현재 자기장 단계 (0부터 시작)

    private Location nextBorderCenter; // 다음 자기장 목표 중심
    private double nextBorderSize;     // 다음 자기장 목표 크기

    // 스케줄러 인스턴스
    private BukkitTask mainBorderTask; // 메인 자기장 진행 스케줄러
    private BukkitTask borderDamageTask; // 자기장 외부 데미지 및 액션바 경고 스케줄러 (별도 유지)

    // 자기장 페이즈 지속 시간 상수 (틱 단위)
    private final long COOLDOWN_DURATION_TICKS = 8 * 60 * 20L;       // 8분 대기
    private final long ANNOUNCEMENT_DURATION_TICKS = 1 * 60 * 20L;   // 1분 공지/카운트다운
    private final long SHRINK_DURATION_TICKS = 1 * 60 * 20L;        // 1분 수축
    private final long TICK_INTERVAL = 20L; // 1초 (20틱)

    // 자기장 페이즈 정의
    private final int MAX_BORDER_PHASES = 6; // 총 0~6단계 (7단계)
    private Map<Integer, Double> phaseSizes;
    private Map<Integer, Double> phaseDamages;

    // 자기장 상태 Enum
    public enum BorderPhaseState {
        COOLDOWN,       // 다음 공지까지 대기
        ANNOUNCEMENT,   // 수축 공지 및 카운트다운
        SHRINKING       // 실제 자기장 수축 중
    }
    private BorderPhaseState currentState; // 현재 자기장 시스템의 상태
    private long currentStateElapsedTimeTicks; // 현재 상태에서 경과한 시간 (틱)


    public BorderManager(GGORRI plugin, Map<UUID, PlayerGameData> playersInGame) {
        this.plugin = plugin;
        this.playersInGame = playersInGame;
        this.random = new Random();

        // 자기장 크기 및 데미지 단계 정의
        phaseSizes = new HashMap<>();
        phaseSizes.put(0, 3200.0);
        phaseSizes.put(1, 1900.0);
        phaseSizes.put(2, 1000.0);
        phaseSizes.put(3, 580.0);
        phaseSizes.put(4, 330.0);
        phaseSizes.put(5, 180.0);
        phaseSizes.put(6, 100.0);

        phaseDamages = new HashMap<>();
        phaseDamages.put(0, 0.5);
        phaseDamages.put(1, 1.0);
        phaseDamages.put(2, 2.0);
        phaseDamages.put(3, 4.0);
        phaseDamages.put(4, 8.0);
        phaseDamages.put(5, 15.0);
        phaseDamages.put(6, 25.0);

        // 초기 상태 설정
        this.currentState = BorderPhaseState.COOLDOWN; // 초기 상태는 대기
        this.currentStateElapsedTimeTicks = 0;
        this.currentPhase = 0; // 시작 페이즈 (phaseSizes 맵의 0단계)
    }

    /**
     * 초기 자기장 설정을 수행합니다. 게임 시작 시 호출됩니다.
     */
    public void setupInitialBorder() {
        World world = plugin.getServer().getWorld("world"); // TODO: 월드 이름을 설정으로
        if (world == null) {
            plugin.getLogger().severe("[GGORRI] 월드 'world'를 찾을 수 없습니다! 자기장 시스템을 초기화할 수 없습니다.");
            return;
        }
        gameBorder = world.getWorldBorder();
        gameBorder.reset(); // 항상 초기화 시 리셋
        currentBorderCenter = world.getSpawnLocation(); // 월드 스폰을 초기 중심으로 설정
        currentBorderSize = phaseSizes.get(0); // 0단계 크기

        gameBorder.setCenter(currentBorderCenter);
        gameBorder.setSize(currentBorderSize);
        plugin.getLogger().info("[GGORRI] 초기 자기장 생성 완료: " +
                "중심(" + currentBorderCenter.getBlockX() + "," + currentBorderCenter.getBlockZ() + ")," +
                "크기(" + currentBorderSize + ")");
    }

    /**
     * 자기장 시스템의 모든 스케줄러를 시작합니다.
     */
    public void startBorderSystem() {
        plugin.getLogger().info("[GGORRI] startBorderSystem() 호출됨. 자기장 시스템 시작 시도.");
        stopBorderSystem(); // 기존 스케줄러 중지

        // 자기장 초기 설정 (반드시 start 전에 호출되어야 함)
        setupInitialBorder(); // 혹시 호출 안된 경우를 위해 여기서 다시 호출

        // 1. 자기장 외부 플레이어 데미지 및 액션바 스케줄러 (매 초)
        borderDamageTask = new BukkitRunnable() {
            @Override
            public void run() {
                applyBorderDamageAndWarnings();
            }
        }.runTaskTimer(plugin, 0L, TICK_INTERVAL); // 0틱 지연, 1초마다 반복

        // 2. 메인 자기장 진행 스케줄러 (매 초)
        mainBorderTask = new BukkitRunnable() {
            @Override
            public void run() {
                currentStateElapsedTimeTicks += TICK_INTERVAL; // 현재 상태에서 경과한 시간 업데이트

                switch (currentState) {
                    case COOLDOWN:
                        handleCooldownState();
                        break;
                    case ANNOUNCEMENT:
                        handleAnnouncementState();
                        break;
                    case SHRINKING:
                        handleShrinkingState();
                        break;
                }
            }
        }.runTaskTimer(plugin, 0L, TICK_INTERVAL); // 0틱 지연, 1초마다 반복

        plugin.getLogger().info("[GGORRI] 자기장 시스템이 시작되었습니다. 초기 COOLDOWN 상태(" + (COOLDOWN_DURATION_TICKS / TICK_INTERVAL) + "초 대기).");
    }

    /**
     * 자기장 시스템의 모든 스케줄러를 중지합니다.
     */
    public void stopBorderSystem() {
        if (mainBorderTask != null) {
            mainBorderTask.cancel();
            mainBorderTask = null;
        }
        if (borderDamageTask != null) {
            borderDamageTask.cancel();
            borderDamageTask = null;
        }
        // 게임 종료 시 월드 보더를 초기화할 필요가 있다면 여기에 추가 (선택 사항)
        // if (gameBorder != null) {
        //     gameBorder.reset();
        // }
        plugin.getLogger().info("[GGORRI] 자기장 시스템이 중지되었습니다.");
    }

    /**
     * COOLDOWN 상태를 처리합니다. 다음 공지까지 대기합니다.
     */
    private void handleCooldownState() {
        // COOLDOWN 상태에서는 특별한 공지나 액션바 없음.
        // `applyBorderDamageAndWarnings`가 외부 경고를 담당합니다.

        if (currentStateElapsedTimeTicks >= COOLDOWN_DURATION_TICKS) {
            // COOLDOWN 시간 완료, 다음 상태로 전환
            currentPhase++; // 다음 자기장 단계로 이동 (예: 0 -> 1)
            if (currentPhase > MAX_BORDER_PHASES) {
                // 모든 자기장 페이즈 완료
                plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[GGORRI] 더 이상 자기장이 줄어들지 않습니다! 최종 자기장 단계에 도달했습니다.");
                stopBorderSystem(); // 시스템 중지
                return;
            }

            // ANNOUNCEMENT 상태로 진입 준비
            prepareNextBorderPhaseData(); // 다음 자기장 크기/중심 계산
            currentState = BorderPhaseState.ANNOUNCEMENT;
            currentStateElapsedTimeTicks = 0; // 현재 상태 시간 초기화

            // ANNOUNCEMENT 상태 시작 공지
            sendInitialAnnouncement();
        }
    }

    /**
     * ANNOUNCEMENT 상태를 처리합니다. 수축 공지 및 카운트다운을 수행합니다.
     */
    private void handleAnnouncementState() {
        long remainingSeconds = (ANNOUNCEMENT_DURATION_TICKS - currentStateElapsedTimeTicks) / TICK_INTERVAL;

        // 특정 시점에 채팅 공지 (액션바 없음)
        if (remainingSeconds == 30 || remainingSeconds == 10 || (remainingSeconds <= 5 && remainingSeconds > 0)) {
            plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장 수축 시작까지 " + remainingSeconds + "초!");
        }

        if (currentStateElapsedTimeTicks >= ANNOUNCEMENT_DURATION_TICKS) {
            // ANNOUNCEMENT 시간 완료, 다음 상태로 전환
            currentState = BorderPhaseState.SHRINKING;
            currentStateElapsedTimeTicks = 0; // 현재 상태 시간 초기화

            // SHRINKING 상태 시작
            startActualBorderShrink(); // 실제 월드 보더 수축 시작
            plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] 자기장이 수축하기 시작합니다!");
            plugin.getLogger().info("[GGORRI] 자기장 " + currentPhase + "단계 수축 시작. 중심: " +
                    nextBorderCenter.getBlockX() + ", " + nextBorderCenter.getBlockZ() +
                    ", 크기: " + nextBorderSize);
        }
    }

    /**
     * SHRINKING 상태를 처리합니다. 실제 자기장 수축을 관리합니다.
     */
    private void handleShrinkingState() {
        // 이 상태에서는 `gameBorder.setSize()`가 자동으로 수축을 처리하며,
        // `applyBorderDamageAndWarnings`가 데미지/다음 자기장 외부 경고를 담당합니다.
        // 필요하다면 수축 완료까지 남은 시간을 채팅 등으로 표시할 수 있습니다.
        long remainingSeconds = (SHRINK_DURATION_TICKS - currentStateElapsedTimeTicks) / TICK_INTERVAL;
        if (remainingSeconds == 30 || remainingSeconds == 10 || (remainingSeconds <= 5 && remainingSeconds > 0)) {
            // plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[GGORRI] 자기장 수축 완료까지 " + remainingSeconds + "초!");
        }


        if (currentStateElapsedTimeTicks >= SHRINK_DURATION_TICKS) {
            // SHRINKING 시간 완료, 다음 상태로 전환
            currentState = BorderPhaseState.COOLDOWN;
            currentStateElapsedTimeTicks = 0; // 현재 상태 시간 초기화

            // 수축 완료 공지
            plugin.getServer().broadcastMessage(ChatColor.AQUA + "[GGORRI] 자기장 수축이 완료되었습니다!");
            plugin.getLogger().info("[GGORRI] 자기장 " + currentPhase + "단계 수축 완료. 최종 크기: " + currentBorderSize);
        }
    }

    /**
     * 다음 자기장 단계의 목표 크기와 중심을 미리 계산합니다.
     */
    private void prepareNextBorderPhaseData() {
        // currentPhase는 이미 다음 단계로 증가된 상태이므로, 이 값을 그대로 사용
        // phaseSizes.get(currentPhase)는 다음 단계의 크기를 가져옴
        Double nextSizeFromMap = phaseSizes.get(currentPhase);
        if (nextSizeFromMap == null) {
            plugin.getLogger().severe("[GGORRI] phaseSizes 맵에 borderPhase " + currentPhase + "에 대한 크기 데이터가 없습니다! 치명적 오류. 시스템 중지.");
            stopBorderSystem();
            return;
        }
        nextBorderSize = nextSizeFromMap;

        // 현재 자기장과 다음 자기장 크기를 이용해 이동 가능한 최대 반경 계산
        // (다음 자기장이 현재 자기장 안에 완전히 포함되면서 이동할 수 있는 반경)
        double maxMovableRadius = (currentBorderSize / 2.0) - (nextBorderSize / 2.0);

        // 만약 다음 자기장이 현재 자기장보다 크거나 같아서 이동할 수 없다면
        if (maxMovableRadius < 0) {
            // 이 경고는 현재 phaseSizes 맵 설정 상 나올 일이 없어야 하지만, 방어적으로 남겨둠
            plugin.getLogger().warning("[GGORRI] 자기장 크기 설정 오류! 다음 페이즈의 자기장이 현재(" + currentBorderSize + ")보다 큽니다(" + nextBorderSize + "). 중심 이동이 제한됩니다.");
            maxMovableRadius = 0;
        }

        // 새로운 중심 좌표 무작위 생성
        double newCenterX = currentBorderCenter.getX() + (random.nextDouble() * 2 - 1) * maxMovableRadius;
        double newCenterZ = currentBorderCenter.getZ() + (random.nextDouble() * 2 - 1) * maxMovableRadius;
        nextBorderCenter = new Location(currentBorderCenter.getWorld(), newCenterX, 0, newCenterZ);

        plugin.getLogger().info("[GGORRI] 다음 자기장 데이터 준비 완료. 목표 크기: " + nextBorderSize + ", 목표 중심: " + (int)nextBorderCenter.getX() + "," + (int)nextBorderCenter.getZ());
    }

    /**
     * 자기장 수축 시작 전 초기 공지 메시지를 전송합니다.
     */
    private void sendInitialAnnouncement() {
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "------------------------------------------");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장 수축 시작까지 " + (ANNOUNCEMENT_DURATION_TICKS / TICK_INTERVAL) + "초 남았습니다!");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 다음 자기장 중심: " + ChatColor.AQUA + "X: " + (int)nextBorderCenter.getX() + ", Z: " + (int)nextBorderCenter.getZ());
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 다음 자기장 크기: " + ChatColor.AQUA + (int)nextBorderSize + " 블록");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장이 " + ChatColor.AQUA + getDirectionString(currentBorderCenter, nextBorderCenter) + " 방향으로 이동합니다!");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "------------------------------------------");
    }

    /**
     * 실제 월드 보더의 중심과 크기를 업데이트하여 수축을 시작합니다.
     */
    private void startActualBorderShrink() {
        gameBorder.setCenter(nextBorderCenter);
        gameBorder.setSize(nextBorderSize, SHRINK_DURATION_TICKS / TICK_INTERVAL); // Minecraft API는 초 단위

        // 현재 자기장 정보 업데이트 (실제 수축이 시작될 때)
        currentBorderCenter = nextBorderCenter;
        currentBorderSize = nextBorderSize;
    }

    /**
     * 자기장 외부에 있는 플레이어에게 데미지를 주고 경고 액션바를 보냅니다.
     * 이 메서드는 `mainBorderTask`와 별개로 독립적으로 매 초 실행됩니다.
     */
    public void applyBorderDamageAndWarnings() {
        if (gameBorder == null) return;

        // 현재 단계의 자기장 데미지 값 가져오기
        // currentPhase는 0부터 시작하고, phaseDamages 맵도 0부터 정의되어 있으므로 그대로 사용
        double currentDamage = phaseDamages.getOrDefault(currentPhase, 1.0); // 단계 없으면 기본 1.0 데미지

        for (Player p : playersInGame.values().stream()
                .map(data -> plugin.getServer().getPlayer(data.getPlayerUUID()))
                .filter(Objects::nonNull) // 온라인 플레이어만 필터링
                .collect(Collectors.toList())) {

            // 1. 플레이어가 현재 자기장 밖에 있는지 확인 (데미지 + 액션바 경고)
            if (!gameBorder.isInside(p.getLocation())) {
                p.damage(currentDamage);
                p.sendActionBar(ChatColor.RED + "⚠ 자기장 외부입니다! (" + String.format("%.1f", currentDamage) + " 피해) ⚠");
            } else {
                // 2. 플레이어가 현재 자기장 안에 있지만, '다음' 자기장 밖에 있는지 확인 (액션바 경고만)
                // 이 경고는 ANNOUNCEMENT 단계가 아니더라도, 항상 다음 자기장 구역을 알려줌
                if (currentState == BorderPhaseState.ANNOUNCEMENT || currentState == BorderPhaseState.COOLDOWN) {
                    // COOLDOWN 상태에서도 다음 자기장 구역이 미리 정해져 있다면 경고를 보낼 수 있습니다.
                    // ANNOUNCEMENT 상태에서 다음 자기장 경고를 확실히 보냅니다.
                    if (nextBorderCenter != null && nextBorderSize > 0) {
                        if (isOutsideNextBorder(p.getLocation())) {
                            p.sendActionBar(ChatColor.LIGHT_PURPLE + "⚠️ 다음 자기장 외부입니다! 안전 지대로 이동하세요! ⚠️");
                        }
                    }
                }
            }
        }
    }

    /**
     * 플레이어 위치가 다음 자기장 범위 밖에 있는지 확인하는 헬퍼 메서드.
     */
    private boolean isOutsideNextBorder(Location loc) {
        // 다음 자기장 정보가 아직 계산되지 않았거나 유효하지 않으면 false 반환 (안전 구역으로 간주)
        if (nextBorderCenter == null || nextBorderSize <= 0) {
            return false;
        }

        // 월드 보더의 isInside 대신 직접 계산 (원형 보더가 아니므로 X, Z 각각 확인)
        double halfNextSize = nextBorderSize / 2.0;
        double minX = nextBorderCenter.getX() - halfNextSize;
        double maxX = nextBorderCenter.getX() + halfNextSize;
        double minZ = nextBorderCenter.getZ() - halfNextSize;
        double maxZ = nextBorderCenter.getZ() + halfNextSize;

        return loc.getX() < minX || loc.getX() > maxX || loc.getZ() < minZ || loc.getZ() > maxZ;
    }

    /**
     * 두 위치 간의 상대적인 방향 문자열을 반환합니다.
     */
    private String getDirectionString(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        String direction = "";
        final double SIGNIFICANT_MOVEMENT_THRESHOLD = 5.0; // 5블록 이상 차이나야 유의미한 방향으로 간주

        if (dz < -SIGNIFICANT_MOVEMENT_THRESHOLD) direction += "북";
        else if (dz > SIGNIFICANT_MOVEMENT_THRESHOLD) direction += "남";
        if (dx > SIGNIFICANT_MOVEMENT_THRESHOLD) direction += "동";
        else if (dx < -SIGNIFICANT_MOVEMENT_THRESHOLD) direction += "서";

        if (direction.isEmpty()) {
            return "제자리 주변";
        }
        return direction;
    }

    // 게터 메서드
    public Location getCurrentBorderCenter() {
        return gameBorder != null ? gameBorder.getCenter() : null;
    }

    public double getCurrentBorderSize() {
        return gameBorder != null ? gameBorder.getSize() : 0.0;
    }
}