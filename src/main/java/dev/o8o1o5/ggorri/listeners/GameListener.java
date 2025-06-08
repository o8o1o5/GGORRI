package dev.o8o1o5.ggorri.listeners;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerRole;
import dev.o8o1o5.ggorri.manager.ChainManager;
import dev.o8o1o5.ggorri.manager.GameManager;
import dev.o8o1o5.ggorri.manager.PlayerManager;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

public class GameListener implements Listener {
    private final GGORRI plugin;
    private final GameManager gameManager;
    private final PlayerManager playerManager;
    private final ChainManager chainManager;

    public GameListener(GGORRI plugin, GameManager gameManager, PlayerManager playerManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.playerManager = playerManager;
        this.chainManager = gameManager.getChainManager();
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // 게임 상태 체크는 유지
        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            event.setCancelled(true);
            return;
        }

        // 플레이어 간의 데미지 확인
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player)) {
            return;
        }

        Player victim = (Player) event.getEntity();
        Player attacker = (Player) event.getDamager();

        // 팀킬 방지 로직 (기존과 동일)
        UUID attackerLeaderUUID = gameManager.getGameRulesManager().findTeamLeaderForSlave(attacker.getUniqueId());
        UUID victimLeaderUUID = gameManager.getGameRulesManager().findTeamLeaderForSlave(victim.getUniqueId());

        // TODO: 디버깅 로그는 개발 완료 후 제거하거나 로그 레벨 조정 (예: Level.FINE)
        plugin.getLogger().info("팀킬 체크: 공격자(" + attacker.getName() + ") 리더: " + (attackerLeaderUUID != null ? plugin.getServer().getOfflinePlayer(attackerLeaderUUID).getName() : "없음") +
                " 피해자(" + victim.getName() + ") 리더: " + (victimLeaderUUID != null ? plugin.getServer().getOfflinePlayer(victimLeaderUUID).getName() : "없음"));

        if (attackerLeaderUUID != null && victimLeaderUUID != null && attackerLeaderUUID.equals(victimLeaderUUID)) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "[GGORRI] 같은 팀원에게는 데미지를 줄 수 없습니다!");
            return;
        }

        // 데미지 기록 (기존과 동일)
        playerManager.recordPlayerDamage(attacker.getUniqueId(), victim.getUniqueId());
    }

    // ---
    // 플레이어의 '사망' 처리 로직을 onPlayerDamage 이벤트로 통합
    // PlayerDeathEvent는 이 로직에서는 필요 없으므로 제거합니다.
    // ---
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            // 게임 중이 아니면 데미지 무효 (옵션: 필요에 따라 다르게 처리 가능)
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            // 플레이어가 받을 피해량이 현재 체력보다 크거나 같아서 '죽음'에 이르는 경우
            if (player.getHealth() - event.getFinalDamage() <= 0) {
                event.setCancelled(true); // **데미지 이벤트를 취소하여 실제 죽음을 막음**

                // 플레이어의 상태를 즉시 복구하여 '죽지 않은' 상태로 만듦
                player.setHealth(player.getMaxHealth()); // 체력을 최대치로 복구
                player.setFoodLevel(20); // 허기 회복
                player.setSaturation(5.0f); // 포만감 회복
                player.setFireTicks(0); // 불 제거
                player.setFallDistance(0); // 낙하 데미지 초기화

                // 플레이어를 스펙테이터 모드로 전환 (1틱 지연으로 안정성 확보)
                // 이 시점에서는 클라이언트가 아직 '죽음'을 완전히 인지하지 않았을 수 있어 지연이 효과적
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(GameMode.SPECTATOR);
                    // deadPlayer.teleport(deadPlayer.getLocation()); // 고정된 위치에 묶어두고 싶다면 활성화
                }, 1L); // 1틱(약 50ms) 지연

                // 킬러 및 피해 원인 정보 추출
                Player killer = null;
                DamageCause cause = event.getCause();

                // EntityDamageByEntityEvent인 경우 킬러(공격자) 추출
                if (event instanceof EntityDamageByEntityEvent) {
                    Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
                    if (damager instanceof Player) {
                        killer = (Player) damager;
                    }
                    // TODO: 투사체(Projectile)에 의한 사망 시 발사자(shooter)를 킬러로 설정하는 로직 추가 가능
                    // if (damager instanceof Projectile) {
                    //     Projectile proj = (Projectile) damager;
                    //     if (proj.getShooter() instanceof Player) {
                    //         killer = (Player) proj.getShooter();
                    //     }
                    // }
                }

                // GameRulesManager의 handlePlayerDeath 메서드 호출
                // 이 메서드 내에서는 플레이어의 게임모드를 다시 설정하지 않도록 주의
                // 주로 아이템 처리, 스코어 보드 업데이트, 사망 메시지 전송 등 게임 규칙 관련 로직을 담당
                gameManager.getGameRulesManager().handlePlayerDeath(player, killer, cause);

                // PlayerDeathEvent가 발생하지 않으므로, 사망 메시지는 여기서 직접 처리해야 합니다.
                // 예: Bukkit.broadcastMessage(player.getName() + " 님이 사망했습니다!");
            }
        }
    }

    // ---
    // 기존 onPlayerDeath 이벤트 핸들러는 제거합니다.
    // EntityDamageEvent에서 모든 사망 관련 로직을 처리하므로, 이 이벤트는 더 이상 필요 없습니다.
    // ---
    // @EventHandler
    // public void onPlayerDeath(PlayerDeathEvent event) {
    //     // 이전에 있던 PlayerDeathEvent 로직은 onPlayerDamage로 통합되었습니다.
    //     // 따라서 이 메서드는 제거하거나, 특정 상황에만 필요한 로직이 있다면 최소한으로 유지해야 합니다.
    // }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quitPlayer = event.getPlayer();
        UUID quitUUID = quitPlayer.getUniqueId();

        if (playerManager.getPlayerGameData(quitUUID).getRole() != PlayerRole.LEADER) {
            return;
        }

        chainManager.handleLeaderExit(quitUUID);
    }
}