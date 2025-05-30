package dev.o8o1o5.ggorri.listeners;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.manager.GameManager;
import dev.o8o1o5.ggorri.manager.PlayerManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.UUID;
import java.util.logging.Level;

public class GameListener implements Listener {
    private final GGORRI plugin;
    private final GameManager gameManager;

    public GameListener(GGORRI plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    // PvP damage event
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // 게임 진행 중에만 처리
        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            return;
        }

        // attacker: Player; victim: Player;
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof Player victim) {
            UUID attackerLeaderUUID = gameManager.getGameRulesManager().findTeamLeaderForSlave(attacker.getUniqueId());
            UUID victimLeaderUUID = gameManager.getGameRulesManager().findTeamLeaderForSlave(victim.getUniqueId());

            // 공격자와 피해자가 게임에 모두 참여중일 것
            // 공격자와 피해자의 리더가 유효할 것
            // 그 팀 리더가 동일할 것
            if (attackerLeaderUUID != null && victimLeaderUUID != null && attackerLeaderUUID.equals(victimLeaderUUID)) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "[GGORRI] 같은 팀원에게는 데미지를 줄 수 없습니다!");
                return;
            }

            gameManager.getPlayerManager().recordPlayerDamage(attacker.getUniqueId(), victim.getUniqueId());
        }
    }

    // Player death event
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimUUID = victim.getUniqueId();

        // 게임 진행 중인 플레이어만 처리
        if (!gameManager.getPlayerManager().getAllPlayersGameData().containsKey(victimUUID)) {
            return;
        }

        DamageCause cause = event.getEntity().getLastDamageCause() != null ? event.getEntity().getLastDamageCause().getCause() : DamageCause.CUSTOM;

        gameManager.getGameRulesManager().handlePlayerDeath(victim, victim.getKiller(), cause);
    }
}