package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActionBarManager {
    private final GGORRI plugin;
    private final Map<UUID, Map<Integer, String>> playerMessages;
    private BukkitTask displayTask;

    private final long UPDATE_INTERVAL_TICKS = 10L;

    public ActionBarManager(GGORRI plugin) {
        this.plugin = plugin;
        this.playerMessages = new ConcurrentHashMap<>();
    }

    /**
     * 액션바 시스템을 시작합니다.
     * 주기적으로 플레이어들에게 최우선순위의 액션바 메시지를 전송합니다.
     */
    public void startDisplaying() {
        if (displayTask != null) {
            displayTask.cancel();
        }
        displayTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    UUID playerUUID = player.getUniqueId();
                    Map<Integer, String> messages = playerMessages.get(playerUUID);

                    if (messages != null && !messages.isEmpty()) {
                        // 가장 높은 우선순위의 메시지 선택
                        String finalMessage = messages.entrySet().stream()
                                .max(Comparator.comparingInt(Map.Entry::getKey)) // 우선순위(키)가 가장 높은 것 선택
                                .map(Map.Entry::getValue)
                                .orElse(""); // 메시지가 없으면 빈 문자열

                        if (!finalMessage.isEmpty()) {
                            player.sendActionBar(finalMessage);
                        }
                    } else {
                        // 표시할 메시지가 없을 경우 액션바 비우기 (선택 사항, 필요 시)
                        // player.sendActionBar("");
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, UPDATE_INTERVAL_TICKS);
        plugin.getLogger().info("[GGORRI] 액션바 관리 시스템이 시작되었습니다.");
    }

    /**
     * 액션바 시스템을 중지합니다.
     */
    public void stopDisplaying() {
        if (displayTask != null) {
            displayTask.cancel();
            displayTask = null;
        }
        playerMessages.clear(); // 모든 플레이어 메시지 초기화
        plugin.getLogger().info("[GGORRI] 액션바 관리 시스템이 중지되었습니다.");
    }

    /**
     * 플레이어의 액션바 메시지를 설정합니다.
     * @param playerUUID 메시지를 받을 플레이어의 UUID
     * @param priority 메시지의 우선순위 (높을수록 먼저 표시됨)
     * @param message 표시할 메시지
     */
    public void setMessage(UUID playerUUID, int priority, String message) {
        playerMessages.computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>()).put(priority, message);
    }

    /**
     * 플레이어의 특정 우선순위 메시지를 제거합니다.
     * @param playerUUID 메시지를 받을 플레이어의 UUID
     * @param priority 제거할 메시지의 우선순위
     */
    public void removeMessage(UUID playerUUID, int priority) {
        Map<Integer, String> messages = playerMessages.get(playerUUID);
        if (messages != null) {
            messages.remove(priority);
            if (messages.isEmpty()) {
                playerMessages.remove(playerUUID); // 메시지가 없으면 플레이어 엔트리 자체를 제거
            }
        }
    }

    // --- 액션바 우선순위 상수 정의 ---
    public static final int PRIORITY_RESPAWN_COUNTDOWN = 1000;
    public static final int PRIORITY_BORDER_OUTSIDE = 100;    // 자기장 외부 (최고)
    public static final int PRIORITY_BORDER_IMMINENT_SHRINK = 90; // 자기장 수축 임박 카운트다운
    public static final int PRIORITY_BORDER_APPROACHING = 80; // 자기장 근접
    public static final int PRIORITY_BORDER_INSIDE = 70;      // 자기장 내부 (안전)
    public static final int PRIORITY_PLAYER_ROLE_INFO = 60;   // 플레이어 역할 정보 (예: 팀장/노예)
    // ... 필요한 다른 정보에 대한 우선순위 추가 ...
}
