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
    private final ActionBarManager actionBarManager;
    private final Map<UUID, PlayerGameData> playersInGame;
    private final Random random;

    private WorldBorder gameBorder;
    private Location currentBorderCenter;
    private double currentBorderSize;
    private int borderPhase;
    private BukkitTask borderAnnouncementTask;
    private BukkitTask borderShrinkEffectTask;
    private BukkitTask borderDamageTask;

    private Map<Integer, Double> phaseSizes;
    private Map<Integer, Double> phaseDamages;

    private final int MAX_BORDER_PHASES = 6;
    private final long ANNOUNCEMENT_INTERVAL_TICKS = 9 * 60 * 20L;
    private final long SHRINK_DURATION_TICKS = 1 * 60 * 20L;
    private final long DAMAGE_INTERVAL_TICKS = 20L;

    private int countdownSeconds;
    private Location nextBorderCenter;
    private double nextBorderSize;

    public BorderManager(GGORRI plugin, ActionBarManager actionBarManager, Map<UUID, PlayerGameData> playersInGame) {
        this.plugin = plugin;
        this.actionBarManager = actionBarManager;
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
        World world = plugin.getServer().getWorld("world");
        gameBorder = world.getWorldBorder();
        currentBorderCenter = world.getSpawnLocation();
        currentBorderSize = phaseSizes.get(0);

        // 초기 설정 적용
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

        // 자기장 외부 플레이어에게 데미지를 주기적으로 적용하는 스케줄러는 게임 시작과 동시에 시작
        borderDamageTask = new BukkitRunnable() {
            @Override
            public void run() {
                applyBorderDamageAndWarnings();
            }
        }.runTaskTimer(plugin, 0L, DAMAGE_INTERVAL_TICKS);

        // 첫 자기장 수축 준비 공지는 게임 시작 후 10분 뒤에 나오도록 예약
        // 10분 = (9분 대기 + 1분 수축) 이후의 첫 공지
        // 즉, 첫 공지는 게임 시작 후 10분 시점에, 그 다음 공지는 10분 간격으로
        // initial delay: 10분 * 20 틱 = 12000 틱
        // period: 10분 * 20 틱 = 12000 틱

        // 첫 자기장 공지 (10분 시점)를 예약하고, 이후 주기적으로 호출되도록 수정
        // 이 스케줄러가 prepareNextBorderPhase()를 호출합니다.
        borderAnnouncementTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[GGORRI] borderAnnouncementTask 실행됨. prepareNextBorderPhase() 호출 시도.");
                prepareNextBorderPhase();
            }
        }.runTaskTimer(plugin, (ANNOUNCEMENT_INTERVAL_TICKS + SHRINK_DURATION_TICKS), (ANNOUNCEMENT_INTERVAL_TICKS + SHRINK_DURATION_TICKS));
        //                           ^ 첫 공지 시점 = 9분 대기 + 1분 수축 시간 (즉 10분 후)
        //                                                      ^ 이후 주기 = 9분 대기 + 1분 수축 시간 (즉 10분마다)

        plugin.getLogger().info("[GGORRI] 자기장 시스템이 시작되었습니다. 첫 자기장 경고는 " + ((ANNOUNCEMENT_INTERVAL_TICKS + SHRINK_DURATION_TICKS) / 20 / 60) + "분 뒤에 나옵니다.");
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

    // 1-4. 다음 자기장 페이즈를 준비하고 공지
    private void prepareNextBorderPhase() {
        if (borderPhase >= MAX_BORDER_PHASES) {
            stopBorderSystem();
            plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "[GGORRI] 더 이상 자기장이 줄어들지 않습니다!");
            return;
        }

        nextBorderSize = phaseSizes.get(borderPhase + 1);

        // 새로운 중심이 이동할 수 있는 최대 반경 (이전 자기장 내에 다음 자기장이 완전히 포함되도록)
        // (현재 자기장 반지름 - 다음 자기장 반지름)
        double maxMovableRadius = (currentBorderSize / 2.0) - (nextBorderSize / 2.0);

        // 만약 다음 자기장이 현재 자기장보다 더 커지는 비정상적인 경우 (로직 오류 방지)
        if (maxMovableRadius < 0) {
            maxMovableRadius = 0; // 중심 이동 불가, 현재 중심 유지
            plugin.getLogger().warning("[GGORRI] 자기장 크기 설정 오류! 다음 페이즈의 자기장이 현재보다 큽니다. 중심 이동이 제한됩니다.");
        }

        // 새로운 중심의 x, z 좌표를 현재 중심을 기준으로 maxMovableRadius 범위 내에서 랜덤하게 결정
        // (random.nextDouble() * 2 - 1) 은 -1.0에서 1.0 사이의 랜덤 값
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

        startCountdownAndShrink(); // 카운트다운 스케줄러 시작
    }

    private void startCountdownAndShrink() {
        if (borderShrinkEffectTask != null) {
            borderShrinkEffectTask.cancel();
            borderShrinkEffectTask = null;
        }

        // --- 카운트다운 스케줄러 ---
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

                    // 현재 자기장 정보 업데이트
                    currentBorderCenter = nextBorderCenter;
                    currentBorderSize = nextBorderSize;
                    borderPhase++; // 다음 페이즈로 전환

                    // **[핵심 변경]** 자기장 수축이 완료될 시점 (SHRINK_DURATION_TICKS 후)에
                    // 다음 prepareNextBorderPhase()를 호출하도록 예약
                    // 이전에 borderAnnouncementTask가 매 주기마다 자신을 반복했으나, 이제는 수축 완료 후 다음 주기를 예약
                    // 이 로직은 borderAnnouncementTask의 initial delay 및 period와 일치해야 합니다.
                    // 즉, 자기장 수축이 시작되고 1분 뒤에 수축이 완료되므로,
                    // 완료된 그 시점부터 다음 공지까지 9분을 기다려야 합니다.
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // 여기서는 prepareNextBorderPhase()를 직접 호출하는 대신,
                            // borderAnnouncementTask가 다음 주기에 호출될 수 있도록 대기합니다.
                            // borderAnnouncementTask의 period가 (ANNOUNCEMENT_INTERVAL_TICKS + SHRINK_DURATION_TICKS)로 설정되어 있으므로,
                            // 이 Runnable은 단순히 수축이 완료되었음을 확인하는 역할만 하고,
                            // 다음 공지는 main borderAnnouncementTask에 의해 자동으로 처리됩니다.
                            plugin.getLogger().info("[GGORRI] 자기장 " + borderPhase + "단계 수축이 완료되었습니다.");
                            plugin.getServer().broadcastMessage(ChatColor.AQUA + "[GGORRI] 자기장 수축이 완료되었습니다!");

                            // 만약 여기에 다음 자기장 공지를 바로 하고 싶다면 prepareNextBorderPhase()를 호출하면 됩니다.
                            // 하지만 요구사항은 "줄어들고 난 뒤 바로 다음 자기장 경고"가 아니라
                            // "줄어들고 난 뒤 (즉시) 다음 자기장 경고" -> (9분 대기) -> "1분간 수축"
                            // 이므로, 첫 공지 시점과 주기성을 borderAnnouncementTask에 맡기는 것이 일관성 있습니다.
                            // 즉, 이 위치에서는 다음 공지를 예약하는 것이 아니라, 수축이 완료되었다는 메시지를 내보내고,
                            // 메인 borderAnnouncementTask가 다음 주기를 기다리는 것이 맞습니다.
                        }
                    }.runTaskLater(plugin, SHRINK_DURATION_TICKS); // 수축이 완료되는 1분 후 실행

                    return; // 카운트다운 스케줄러는 종료
                }

                // --- 액션바 카운트다운 메시지를 직접 보내지 않고 ActionBarManager에 요청 ---
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    actionBarManager.setMessage(p.getUniqueId(), ActionBarManager.PRIORITY_BORDER_IMMINENT_SHRINK,
                            ChatColor.YELLOW + "자기장 수축까지 " + countdownSeconds + "초 남았습니다!");
                }

                // 특정 시점에 채팅 공지
                if (countdownSeconds == 30 || countdownSeconds == 10 || (countdownSeconds <= 5 && countdownSeconds > 0)) {
                    plugin.getServer().broadcastMessage(ChatColor.YELLOW + "[GGORRI] 자기장 수축까지 " + countdownSeconds + "초!");
                }

                countdownSeconds--;
            }
        }.runTaskTimer(plugin, 0L, 20L); // 0초 지연, 1초마다 반복
    }

    public void applyBorderDamageAndWarnings() {
        for (Player p : playersInGame.values().stream()
                .map(data -> plugin.getServer().getPlayer(data.getPlayerUUID()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList())) {

            if (gameBorder == null) {
                // 월드보더가 아직 설정되지 않았다면 아무것도 하지 않음
                continue;
            }

            // --- 이제 플레이어 위치를 현재 WorldBorder가 아닌 다음 WorldBorder 기준으로 판단합니다 ---
            // 즉, WorldBorder가 줄어들고 있는 중이거나, 줄어든 후의 안전 구역을 미리 알려주는 로직입니다.
            boolean isInsideNextBorder = true; // 기본적으로 다음 자기장 안에 있다고 가정
            String borderStateMessage = "";

            // 다음 자기장 중심과 크기가 유효할 때만 다음 자기장 기준 위치 판단
            if (nextBorderCenter != null && nextBorderSize > 0) {
                // 다음 자기장 임시 보더 생성 (실제 WorldBorder를 건드리지 않고 가상으로 검사)
                // WorldBorder는 생성자가 없으므로, 현재 게임 월드의 월드 보더를 잠시 설정 변경하여 검사하는 것은 좋지 않음.
                // 대신, 직접 거리 계산을 통해 판단합니다.
                // 다음 자기장 반지름
                double nextBorderRadius = nextBorderSize / 2.0;

                // 플레이어와 다음 자기장 중심 간의 x, z 거리
                double distanceX = Math.abs(p.getLocation().getX() - nextBorderCenter.getX());
                double distanceZ = Math.abs(p.getLocation().getZ() - nextBorderCenter.getZ());

                // 플레이어가 다음 자기장 정사각형 범위 밖에 있는지 확인
                if (distanceX > nextBorderRadius || distanceZ > nextBorderRadius) {
                    isInsideNextBorder = false;
                }
            } else {
                // nextBorderCenter와 nextBorderSize가 아직 설정되지 않았다면 (예: 게임 초기)
                // 현재 WorldBorder를 기준으로 판단 (기존 로직 사용)
                isInsideNextBorder = gameBorder.isInside(p.getLocation());
            }

            // --- 우선순위 1: 자기장 외부 경고 (현재 자기장 기준) ---
            if (!gameBorder.isInside(p.getLocation())) {
                double damageAmount = phaseDamages.getOrDefault(borderPhase, 1.0);
                p.damage(damageAmount);
                actionBarManager.setMessage(p.getUniqueId(), ActionBarManager.PRIORITY_BORDER_OUTSIDE,
                        ChatColor.RED + "⚠ 자기장 외부입니다! (" + String.format("%.1f", damageAmount) + " 피해) ⚠");
            }
            // --- 나머지 메시지는 자기장 외부가 아닐 때만 보냄 ---
            else {
                // --- 플레이어의 다음 자기장 기준 위치 상태를 액션바로 표시 (배그 스타일) ---
                if (!isInsideNextBorder) {
                    // 다음 자기장 밖에 있을 경우 경고 (이동 필요)
                    borderStateMessage = ChatColor.LIGHT_PURPLE + "⚠️ 다음 자기장 외부입니다! 안전 지대로 이동하세요! ⚠️";
                    actionBarManager.setMessage(p.getUniqueId(), ActionBarManager.PRIORITY_BORDER_APPROACHING, borderStateMessage);
                } else {
                    // 다음 자기장 안에 있을 경우 안전 알림
                    borderStateMessage = ChatColor.GREEN + "✅ 다음 자기장 내부에 있습니다. 안전합니다!";
                    actionBarManager.setMessage(p.getUniqueId(), ActionBarManager.PRIORITY_BORDER_INSIDE, borderStateMessage);
                }

                // 만약 현재 자기장 안에 있고, 다음 자기장도 안에 있다면,
                // 자기장 경계 근접 경고는 더 이상 최고 우선순위가 아님.
                // 따라서 PRIORITY_BORDER_APPROACHING에 덮어씌워지거나 다른 우선순위로 관리됨.
                // 이전에 PRIORITY_BORDER_APPROACHING을 자기장 근접용으로 사용했으나,
                // 이제 "다음 자기장 외부" 의미로 확장하여 사용합니다.
            }
        }
    }

    // 1-7. 두 위치 사이의 방향을 문자열로 반환하는 헬퍼 메서드
    private String getDirectionString(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        String direction = "";
        // 북/남
        if (dz < -5) direction += "북"; // 5블록 이상 차이나야 유의미한 방향으로 간주
        else if (dz > 5) direction += "남";
        // 동/서
        if (dx > 5) direction += "동";
        else if (dx < -5) direction += "서";

        if (direction.isEmpty()) {
            return "제자리 주변"; // 거의 움직이지 않았거나 수직 이동
        }
        return direction;
    }
}
