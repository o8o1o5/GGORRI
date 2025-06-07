package dev.o8o1o5.ggorri.listeners;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.manager.PlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material; // Material 임포트 추가
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class TrackersCompassListener implements Listener {
    private final GGORRI plugin;
    private final PlayerManager playerManager;

    // 쿨타임 시간 (틱 단위: 20틱 = 1초)
    private final int COOLDOWN_TICKS = 600 * 20; // 5초 쿨타임

    public TrackersCompassListener(GGORRI plugin, PlayerManager playerManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
    }

    @EventHandler
    public void onPlayerInteractEvent(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || !item.hasItemMeta()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();

        if (data.has(plugin.getCustomItemIdKey(), PersistentDataType.STRING)) {
            String customItemId = data.get(plugin.getCustomItemIdKey(), PersistentDataType.STRING);

            if ("trackers_compass".equals(customItemId)) {
                // 플레이어가 오른쪽 클릭했는지 확인
                if (event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {

                    // 1. 쿨타임 확인: 플레이어가 쿨타임 중인지 먼저 체크
                    if (player.hasCooldown(item.getType())) {
                        // 쿨타임 중이면 메시지를 보내고 이벤트를 취소한 뒤 종료
                        player.sendMessage("[GGORRI] 나침반은 아직 쿨타임 중입니다.");
                        event.setCancelled(true);
                        return; // 중요: 쿨타임 중이면 이후 로직을 실행하지 않음
                    }

                    // 2. 타겟 UUID 유효성 검사 및 위치 획득
                    UUID targetUUID = playerManager.getPlayerGameData(player.getUniqueId()).getDirectTargetUUID();
                    if (targetUUID == null) {
                        player.sendMessage("[GGORRI] 추적할 대상이 설정되지 않았습니다.");
                        event.setCancelled(true);
                        return;
                    }
                    Player targetPlayer = Bukkit.getPlayer(targetUUID);
                    if (targetPlayer == null || !targetPlayer.isOnline()) {
                        player.sendMessage("[GGORRI] 추적할 대상이 현재 접속 중이 아닙니다.");
                        event.setCancelled(true);
                        return;
                    }
                    Location targetLocation = targetPlayer.getLocation();

                    // 3. 쿨타임 적용: 모든 조건이 충족되어 기능이 실행되기 직전에 쿨타임 부여
                    player.setCooldown(item.getType(), COOLDOWN_TICKS); // 나침반 아이템(Material.COMPASS)에 쿨타임 적용

                    // 4. 원래의 기능 실행 (타겟 위치 메시지 전송)
                    player.sendMessage("[GGORRI] 타겟의 위치는 (" +
                            Math.round(targetLocation.getX()) + ", " +
                            Math.round(targetLocation.getY()) + ", " +
                            Math.round(targetLocation.getZ()) + ") 입니다.");
                    event.setCancelled(true); // 이벤트 취소 (아이템 사용 애니메이션만 남김)
                }
            }
        }
    }
}