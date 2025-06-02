package dev.o8o1o5.ggorri.listeners;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.manager.ActionBarManager;
import dev.o8o1o5.ggorri.manager.GameManager;
import dev.o8o1o5.ggorri.manager.PlayerManager;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Level;

public class GameListener implements Listener {
    private final GGORRI plugin;
    private final GameManager gameManager;
    private final PlayerManager playerManager;
    private final Random random;

    public GameListener(GGORRI plugin, GameManager gameManager, PlayerManager playerManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.playerManager = playerManager;
        this.random = new Random();
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player)) {
            return; // 플레이어 간의 데미지가 아니면 무시
        }

        Player victim = (Player) event.getEntity();
        Player attacker = (Player) event.getDamager();

        // 같은 팀원인지 확인 (masterUUID 기반으로 팀 확인)
        UUID attackerLeaderUUID = gameManager.getGameRulesManager().findTeamLeaderForSlave(attacker.getUniqueId());
        UUID victimLeaderUUID = gameManager.getGameRulesManager().findTeamLeaderForSlave(victim.getUniqueId());

        // TODO: 디버깅을 위해 아래 라인을 추가합니다.
        plugin.getLogger().info("팀킬 체크: 공격자(" + attacker.getName() + ") 리더: " + (attackerLeaderUUID != null ? plugin.getServer().getOfflinePlayer(attackerLeaderUUID).getName() : "없음") +
                " 피해자(" + victim.getName() + ") 리더: " + (victimLeaderUUID != null ? plugin.getServer().getOfflinePlayer(victimLeaderUUID).getName() : "없음"));

        if (attackerLeaderUUID != null && victimLeaderUUID != null && attackerLeaderUUID.equals(victimLeaderUUID)) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "[GGORRI] 같은 팀원에게는 데미지를 줄 수 없습니다!");
            return;
        }

        // 데미지 기록
        playerManager.recordPlayerDamage(attacker.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player deadPlayer = event.getEntity();

        // 1. 사망 이벤트를 취소하여 기본 사망 처리 (리스폰 버튼 활성화, 자동 리스폰 화면 등)를 막음
        event.setCancelled(true);

        // 2. 기본 아이템 드롭 및 경험치 드롭 방지 (cancel()만으로는 부족할 수 있음)
        event.getDrops().clear();
        event.setDroppedExp(0);

        // 3. 플레이어의 현재 체력과 허기를 풀로 채워 '죽지 않은' 상태로 만들지만,
        // 시각적으로는 스펙테이터 모드로 전환하여 사망 상태를 표현
        deadPlayer.setHealth(deadPlayer.getMaxHealth()); // 체력을 최대치로 복구
        deadPlayer.setFoodLevel(20); // 허기 회복
        deadPlayer.setSaturation(5.0f); // 포만감 회복
        deadPlayer.setFireTicks(0); // 불 제거
        deadPlayer.setFallDistance(0); // 낙하 데미지 초기화
        deadPlayer.setGameMode(GameMode.SPECTATOR); // 플레이어를 스펙테이터 모드로 전환
        // deadPlayer.teleport(deadPlayer.getLocation()); // 고정된 위치에 묶어두는 효과 (선택 사항)

        Player killer = deadPlayer.getKiller();
        DamageCause damageCause = deadPlayer.getLastDamageCause() != null ? deadPlayer.getLastDamageCause().getCause() : DamageCause.CUSTOM;

        // GameRulesManager의 handlePlayerDeath 메서드 호출
        // 이제 handlePlayerDeath 내에서는 플레이어의 게임모드를 다시 설정하지 않습니다.
        // 대신 아이템 처리만 담당하게 됩니다.
        gameManager.getGameRulesManager().handlePlayerDeath(deadPlayer, killer, damageCause);
    }
}