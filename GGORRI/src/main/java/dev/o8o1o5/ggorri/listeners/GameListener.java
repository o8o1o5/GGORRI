package dev.o8o1o5.ggorri.listeners;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.manager.GameManager;
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
            // TODO: 팀킬 방지 등.. etc. (현재는 모두 기록)

            // 플레이어 간 데미지 발생 시 기록
            gameManager.getPlayerManager().recordPlayerDamage(attacker.getUniqueId(), victim.getUniqueId());
        }
    }

    // Player death event
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimUUID = victim.getUniqueId();

        // 게임 진행 중인 플레이어만 처리
        if (!gameManager.getAllPlayersGameData().containsKey(victimUUID)) {
            return;
        }

        // 1. 사망 원인 판별
        DamageCause cause = event.getEntity().getLastDamageCause() != null ? event.getEntity().getLastDamageCause().getCause() : DamageCause.CUSTOM;
        UUID killerUUID = null; // PvP 사망일 경우 공격자의 UUID

        // 2. PvP 사망 여부 및 공격자 확인
        if (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.PROJECTILE) {
            // 마지막 공격자를 GameManager에서 조회
            killerUUID = gameManager.getPlayerManager().getLastAttacker(victimUUID);
        }

        // 3. 사망 판정 및 처리
        if (killerUUID != null && gameManager.getAllPlayersGameData().containsKey(killerUUID)) {
            // PvP 사망
            Player killer = plugin.getServer().getPlayer(killerUUID);
            if (killer != null) {
                PlayerGameData victimData = gameManager.getPlayerManager().getPlayerGameData(victimUUID);
                PlayerGameData killerData = gameManager.getPlayerManager().getPlayerGameData(killerUUID);

                if (victimData == null || killerData == null) {
                    plugin.getLogger().warning("[GGORRI] 사망 또는 공격 플레이어의 게임 데이터가 없습니다: Victim=" + victim.getName() + ", Killer=" + killer.getName());
                    // 데이터 없는 경우 자연사 처리 (임시)
                    handleNaturalDeath(victim);
                    gameManager.getPlayerManager().clearDamageRecordsForPlayer(victimUUID);
                    return;
                }

                plugin.getLogger().info("[GGORRI] " + victim.getName() + "님이 " + killer.getName() + "님에게 살해당했습니다.");

                // 3.1. 올바른 타겟 처치 판정
                if (killerData.getDirectTargetUUID() != null && killerData.getDirectTargetUUID().equals(victimUUID)) {
                    // 정상 처치 (킬러가 죽은 플레이어의 직계 타겟)
                    plugin.getServer().broadcastMessage(ChatColor.GREEN + "[GGORRI] " + killer.getName() + "님이 자신의 타겟 " + victim.getName() + "님을 처치했습니다!");
                    gameManager.enslavePlayerAndAdjustTargets(killerUUID, victimUUID);
                    killer.sendMessage(ChatColor.AQUA + "[GGORRI] 타겟을 처치했습니다!");
                } else {
                    plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] " + killer.getName() + "님이 잘못된 타겟인 " + victim.getName() + "님을 처치했습니다!");
                    killer.sendMessage(ChatColor.RED + "[GGORRI] 잘못된 타겟을 처치했습니다!");
                    handleNaturalDeath(victim); // 잘못된 타겟 처치도 사망 횟수 증가 및 부활 처리 (자연사와 동일)
                }
            } else {
                plugin.getLogger().warning("[GGORRI] PvP 사망했으나 공격자 플레이어가 null 입니다: " + victim.getName());
                handleNaturalDeath(victim); // 자연사 처리
            }
        } else {
            // PvP 사망이 아닌 경우 (환경 요인, 몬스터 등) = 자연사
            plugin.getLogger().info("[GGORRI] " + victim.getName() + "님이 죽었습니다. 사망 원인: " + cause.name());
            handleNaturalDeath(victim);
        }

        // 사망한 플레이어의 데미지 기록 정리
        gameManager.getPlayerManager().clearDamageRecordsForPlayer(victimUUID);

        // 기본 사망 메시지 제거 (옵션)
        event.setDeathMessage(null);
    }

    /**
     * 자연사 또는 잘못된 타겟 처치 등으로 인한 플레이어 사망 처리 (PvP 이외의 사망)
     * @param player 사망한 플레이어
     */
    private void handleNaturalDeath(Player player) {
        // TODO: 2.1단계에서 부활 시스템 구현 시 여기에 로직 추가
        player.sendMessage(ChatColor.YELLOW + "[GGORRI] 당신은 자연사했습니다.");

        PlayerGameData data = gameManager.getPlayerManager().getPlayerGameData(player.getUniqueId());
        if (data != null) {
            data.incrementDeathCount(); // 사망 횟수 증가
            plugin.getLogger().info("[GGORRI] " + player.getName() + "님의 사망 횟수: " + data.getDeathCount());
            // TODO: 2.2단계에서 노예 사망 횟수 증가에 따른 부활 시간 증가 로직 추가
        } else {
            plugin.getLogger().warning("[GGORRI] 자연사한 플레이어(" + player.getName() + ")의 게임 데이터가 없습니다.");
        }
    }
}