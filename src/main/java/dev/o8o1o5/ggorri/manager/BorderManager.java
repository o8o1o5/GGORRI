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

    private WorldBorder gameBorder;
    private Location currentBorderCenter;
    private double currentBorderSize;
    private int borderPhase;
    private BukkitTask borderAnnouncementTask; // 자기장 공지 및 다음 페이즈 준비 스케줄러
    private BukkitTask borderShrinkEffectTask; // 자기장 카운트다운 및 실제 수축 스케줄러
    private BukkitTask borderDamageTask;       // 자기장 외부 데미지 및 액션바 경고 스케줄러

    private Map<Integer, Double> phaseSizes;
    private Map<Integer, Double> phaseDamages;

    private final int MAX_BORDER_PHASES = 6;
    // 이 상수는 이제 "수축 완료 후 다음 공지까지 대기하는 시간"이 됩니다.
    // 당신의 요구사항: 공지-수축시작-수축완료(동시공지)의 주기는 10분.
    // 1분 카운트다운, 1분 수축이므로 2분이 소요됩니다.
    // 10분 주기 = 2분(카운트+수축) + 8분(대기)
    private final long PHASE_COOLDOWN_TICKS = 8 * 60 * 20L; // 8분 대기
    private final long ANNOUNCEMENT_COUNTDOWN_TICKS = 1 * 60 * 20L; // 1분 카운트다운
    private final long SHRINK_DURATION_TICKS = 1 * 60 * 20L; // 1분 수축 시간
    private final long DAMAGE_INTERVAL_TICKS = 20L; // 1초마다 데미지/액션바

    private int countdownSeconds;
    private Location nextBorderCenter;
    private double nextBorderSize;

    public BorderManager(GGORRI plugin, Map<UUID, PlayerGameData> playersInGame) {
        this.plugin = plugin;
        this.playersInGame = playersInGame;
        this.random = new Random();

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
    }

    // 1-1. 초기 자기장 설정
    public void setupInitialBorder() {
        World world = plugin.getServer().getWorld("world"); // TODO: 월드 이름을 설정으로
        if (world == null) {
            plugin.getLogger().severe("[GGORRI] 월드 'world'를 찾을 수 없습니다! 자기장 시스템을 초기화할 수 없습니다.");
            return;
        }
        gameBorder = world.getWorldBorder();
        gameBorder.reset(); // 초기화 시 항상 리셋
        currentBorderCenter = world.getSpawnLocation();
        currentBorderSize = phaseSizes.get(0);

        gameBorder.setCenter(currentBorderCenter);
        gameBorder.setSize(currentBorderSize);
        borderPhase = 0;
        plugin.getLogger().info("[GGORRI] 초기 자기장 생성 완료: " +
                "중심(" + currentBorderCenter.getBlockX() + "," + currentBorderCenter.getBlockZ() + ")," +
                "크기(" + currentBorderSize + ")");
    }

    // 1-2. 자기장 시스템 전체 시작
    public void startBorderSystem() {
        plugin.getLogger().info("[GGORRI] startBorderSystem() 호출됨. 자기장 시스템 시작 시도.");
        stopBorderSystem(); // 기존 태스크 중지

        // 자기장 외부 플레이어 데미지 및 액션바 스케줄러 (매 초)
        borderDamageTask = new BukkitRunnable() {
            @Override
            public void run() {
                applyBorderDamageAndWarnings();
            }
        }.runTaskTimer(plugin, 0L, DAMAGE_INTERVAL_TICKS);

        // 첫 자기장 공지 및 이후 주기적인 공지를 담당하는 스케줄러
        // initial delay: 10분 = 10 * 60 * 20 틱
        // period: 10분 = 10 * 60 * 20 틱
        borderAnnouncementTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[GGORRI] borderAnnouncementTask 실행됨. prepareNextBorderPhase() 호출 시도.");
                prepareNextBorderPhase();
            }
        }.runTaskTimer(plugin, 10 * 60 * 20L, 10 * 60 * 20L);

        plugin.getLogger().info("[GGORRI] 자기장 시스템이 시작되었습니다. 첫 자기장 경고는 10분 뒤에 나옵니다.");
    }

    // 1-3. 자기장 시스템 전체 중지
    public void stopBorderSystem() {
        if (borderAnnouncementTask != null) {
            borderAnnouncementTask.cancel();
            borderAnnouncementTask = null;
        }
        if (borderShrinkEffectTask != null) {
            borderShrinkEffectTask.cancel();
            borderShrinkEffectTask = null;
        }
        if (borderDamageTask != null) {
            borderDamageTask.cancel();
            borderDamageTask = null;
        }

        if (gameBorder != null) {
            gameBorder.reset();
        }
        plugin.getLogger().info("[GGORRI] 자기장 시스템이 중지되었습니다.");
    }

    // 1-4. 다음 자기장 페이즈를 준비하고 공지 (1분 카운트다운 시작)
    private void prepareNextBorderPhase() {
        if (borderPhase >= MAX_BORDER_PHASES) {
            stopBorderSystem();
            plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[GGORRI] 더 이상 자기장이 줄어들지 않습니다! 최종 자기장 단계에 도달했습니다.");
            return;
        }

        // 다음 자기장 단계의 크기
        nextBorderSize = phaseSizes.get(borderPhase + 1);

        // 새로운 중심이 이동할 수 있는 최대 반경 (이전 자기장 내에 다음 자기장이 완전히 포함되도록)
        double maxMovableRadius = (currentBorderSize / 2.0) - (nextBorderSize / 2.0);

        if (maxMovableRadius < 0) {
            maxMovableRadius = 0;
            plugin.getLogger().warning("[GGORRI] 자기장 크기 설정 오류! 다음 페이즈의 자기장이 현재보다 큽니다. 중심 이동이 제한됩니다.");
        }

        double newCenterX = currentBorderCenter.getX() + (random.nextDouble() * 2 - 1) * maxMovableRadius;
        double newCenterZ = currentBorderCenter.getZ() + (random.nextDouble() * 2 - 1) * maxMovableRadius;

        nextBorderCenter = new Location(currentBorderCenter.getWorld(), newCenterX, 0, newCenterZ);

        countdownSeconds = 60; // 1분 (60초) 카운트다운 시작
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "------------------------------------------");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장 수축 시작까지 " + countdownSeconds + "초 남았습니다!");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 다음 자기장 중심: " + ChatColor.AQUA + "X: " + (int)nextBorderCenter.getX() + ", Z: " + (int)nextBorderCenter.getZ());
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 다음 자기장 크기: " + ChatColor.AQUA + (int)nextBorderSize + " 블록");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장이 " + ChatColor.AQUA + getDirectionString(currentBorderCenter, nextBorderCenter) + " 방향으로 이동합니다!");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "------------------------------------------");

        startCountdownAndShrink(); // 카운트다운 스케줄러 시작 (1분 후 수축 시작)
    }

    private void startCountdownAndShrink() {
        if (borderShrinkEffectTask != null) {
            borderShrinkEffectTask.cancel();
            borderShrinkEffectTask = null;
        }

        // --- 카운트다운 스케줄러 (1분간) ---
        borderShrinkEffectTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 카운트다운 종료 시 (1분 경고 시간이 끝났을 때)
                if (countdownSeconds <= 0) {
                    this.cancel(); // 현재 카운트다운 스케줄러 중지

                    // 실제 월드 보더 수축 시작 (1분간)
                    gameBorder.setCenter(nextBorderCenter);
                    gameBorder.setSize(nextBorderSize, SHRINK_DURATION_TICKS / 20); // Minecraft API는 초 단위
                    plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] 자기장이 수축하기 시작합니다!");
                    plugin.getLogger().info("[GGORRI] 자기장 " + (borderPhase + 1) + "단계 수축 시작. 중심: " + nextBorderCenter.getBlockX() + ", " + nextBorderCenter.getBlockZ() + ", 크기: " + nextBorderSize);

                    // 현재 자기장 정보 업데이트 (실제 수축 시작 시 업데이트)
                    currentBorderCenter = nextBorderCenter;
                    currentBorderSize = nextBorderSize;
                    borderPhase++; // 다음 페이즈로 전환

                    // --- 핵심 변경: 수축 완료 메시지 및 이후에는 'borderAnnouncementTask'가 다음 공지를 처리하도록 함 ---
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            plugin.getLogger().info("[GGORRI] 자기장 " + borderPhase + "단계 수축이 완료되었습니다.");
                            plugin.getServer().broadcastMessage(ChatColor.AQUA + "[GGORRI] 자기장 수축이 완료되었습니다!");
                        }
                    }.runTaskLater(plugin, SHRINK_DURATION_TICKS); // 수축이 완료되는 1분 후 실행

                    return; // 카운트다운 스케줄러는 종료
                }

                // 액션바 카운트다운 메시지 표시 (다음 자기장 안에 있는 경우 제외)
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (isOutsideNextBorder(p.getLocation())) {
                        p.sendActionBar(ChatColor.YELLOW + "자기장 수축까지 " + countdownSeconds + "초 남았습니다!");
                    }
                }

                // 특정 시점에 채팅 공지 (카운트다운이 길어졌으므로, 공지 시점도 재고)
                if (countdownSeconds == 300 || countdownSeconds == 180 || countdownSeconds == 60 || countdownSeconds == 30 || countdownSeconds == 10 || (countdownSeconds <= 5 && countdownSeconds > 0)) {
                    plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장 수축까지 " + countdownSeconds + "초!");
                }

                countdownSeconds--;
            }
        }.runTaskTimer(plugin, 0L, 20L); // 0초 지연, 1초마다 반복
    }

    // 1-4. 다음 자기장 페이즈를 준비하고 공지 (1분 카운트다운 시작)
    // 이 메서드에서는 countdownSeconds를 9분으로 설정해야 합니다.
    private void prepareNextBorderPhaseOldLogic() { // 이름 변경: 구 로직과의 비교를 위해
        if (borderPhase >= MAX_BORDER_PHASES) {
            stopBorderSystem();
            plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[GGORRI] 더 이상 자기장이 줄어들지 않습니다! 최종 자기장 단계에 도달했습니다.");
            return;
        }

        nextBorderSize = phaseSizes.get(borderPhase + 1);

        double maxMovableRadius = (currentBorderSize / 2.0) - (nextBorderSize / 2.0);
        if (maxMovableRadius < 0) { maxMovableRadius = 0; }
        double newCenterX = currentBorderCenter.getX() + (random.nextDouble() * 2 - 1) * maxMovableRadius;
        double newCenterZ = currentBorderCenter.getZ() + (random.nextDouble() * 2 - 1) * maxMovableRadius;
        nextBorderCenter = new Location(currentBorderCenter.getWorld(), newCenterX, 0, newCenterZ);

        // 당신의 요구사항에 맞춰 카운트다운을 9분으로 설정합니다.
        countdownSeconds = 9 * 60; // 9분 (540초) 카운트다운 시작

        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "------------------------------------------");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장 수축 시작까지 " + countdownSeconds + "초 남았습니다!");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 다음 자기장 중심: " + ChatColor.AQUA + "X: " + (int)nextBorderCenter.getX() + ", Z: " + (int)nextBorderCenter.getZ());
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 다음 자기장 크기: " + ChatColor.AQUA + (int)nextBorderSize + " 블록");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장이 " + ChatColor.AQUA + getDirectionString(currentBorderCenter, nextBorderCenter) + " 방향으로 이동합니다!");
        plugin.getServer().broadcastMessage(ChatColor.YELLOW + "------------------------------------------");

        startCountdownAndShrink(); // 카운트다운 스케줄러 시작
    }


    public void applyBorderDamageAndWarnings() {
        if (gameBorder == null) return;

        double currentDamage = phaseDamages.getOrDefault(borderPhase, 1.0);

        for (Player p : playersInGame.values().stream()
                .map(data -> plugin.getServer().getPlayer(data.getPlayerUUID()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList())) {

            // 1. 플레이어가 현재 자기장 밖에 있는지 확인 (데미지 + 경고)
            if (!gameBorder.isInside(p.getLocation())) {
                p.damage(currentDamage);
                p.sendActionBar(ChatColor.RED + "⚠ 자기장 외부입니다! (" + String.format("%.1f", currentDamage) + " 피해) ⚠");
            } else {
                // 2. 현재 자기장 안에 있지만, 다음 자기장 밖에 있는지 확인 (경고만)
                // 다음 자기장 중심과 크기가 유효할 때만 다음 자기장 검사를 수행
                if (nextBorderCenter != null && nextBorderSize > 0) {
                    if (isOutsideNextBorder(p.getLocation())) {
                        p.sendActionBar(ChatColor.LIGHT_PURPLE + "⚠️ 다음 자기장 외부입니다! 안전 지대로 이동하세요! ⚠️");
                    }
                    // else: 다음 자기장 내부에 있는 경우 액션바를 보내지 않음 (요구사항 반영)
                }
                // else: nextBorderCenter가 아직 설정되지 않았을 때 (게임 초기 등), 현재 자기장 안에 있으므로 액션바를 보내지 않음 (요구사항 반영)
            }
        }
    }

    // 다음 자기장 밖에 있는지 확인하는 헬퍼 메서드
    private boolean isOutsideNextBorder(Location loc) {
        if (nextBorderCenter == null || nextBorderSize <= 0) {
            return false; // 다음 자기장 정보가 없으면, 밖에 있다고 판단하지 않음 (안전 구역으로 간주)
        }

        double nextBorderRadius = nextBorderSize / 2.0;
        double distanceX = Math.abs(loc.getX() - nextBorderCenter.getX());
        double distanceZ = Math.abs(loc.getZ() - nextBorderCenter.getZ());

        return distanceX > nextBorderRadius || distanceZ > nextBorderRadius;
    }

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
}