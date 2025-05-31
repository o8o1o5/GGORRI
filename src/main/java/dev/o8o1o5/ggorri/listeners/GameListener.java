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

        if (gameManager.getCurrentStatus() != GameManager.GameStatus.IN_GAME) {
            // 게임 중이 아니면 일반적인 사망 처리
            return;
        }

        event.setShowDeathMessages(false); // 사망 메시지 숨김 (선택 사항)
        event.getDrops().clear(); // 기본 드롭 모두 제거 (우리가 직접 드롭할 것이므로)
        event.setDroppedExp(0); // 경험치 드롭 방지

        // 인벤토리 아이템 절반 드롭 로직
        List<ItemStack> playerInventoryItems = new ArrayList<>();
        // 주 인벤토리 아이템
        for (ItemStack item : deadPlayer.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                playerInventoryItems.add(item.clone()); // 아이템 복사본 추가
            }
        }
        // 갑옷 아이템
        for (ItemStack item : deadPlayer.getInventory().getArmorContents()) {
            if (item != null && item.getType() != Material.AIR) {
                playerInventoryItems.add(item.clone());
            }
        }
        // 오프핸드 아이템
        ItemStack offHand = deadPlayer.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            playerInventoryItems.add(offHand.clone());
        }

        Collections.shuffle(playerInventoryItems, random); // 아이템 섞기

        int itemsToDropCount = playerInventoryItems.size() / 2;
        if (itemsToDropCount == 0 && !playerInventoryItems.isEmpty()) {
            itemsToDropCount = 1; // 아이템이 있다면 최소 1개는 드롭
        }

        List<ItemStack> droppedItems = new ArrayList<>();
        for (int i = 0; i < itemsToDropCount; i++) {
            if (i >= playerInventoryItems.size()) break;
            ItemStack item = playerInventoryItems.get(i);
            droppedItems.add(item);
            // NOTE: 인벤토리에서 직접 제거하는 것은 PlayerManager.resetPlayer에서 일괄 처리되므로 여기서는 제거하지 않습니다.
            // deadPlayer.getInventory().remove(item); // 여기서는 제거하지 않음
        }

        // 인벤토리 비우기 (일단 다 비우고, 드롭되지 않은 아이템만 다시 돌려주거나 리셋 로직에 맡김)
        deadPlayer.getInventory().clear();
        deadPlayer.getInventory().setArmorContents(null);
        deadPlayer.getInventory().setItemInOffHand(null);


        // 월드에 아이템 실제로 드롭
        Location deathLocation = deadPlayer.getLocation(); // 사망 위치
        for (ItemStack item : droppedItems) {
            if (item != null && item.getType() != Material.AIR) {
                deadPlayer.getWorld().dropItemNaturally(deathLocation, item);
            }
        }

        // 플레이어를 스펙테이터 모드로 변경하고 사망 위치에 고정
        new BukkitRunnable() {
            @Override
            public void run() {
                if (deadPlayer.isOnline()) { // 플레이어가 아직 온라인일 경우에만 처리
                    deadPlayer.setGameMode(GameMode.SPECTATOR);
                    deadPlayer.teleport(deathLocation);
                    deadPlayer.setSpectatorTarget(null); // 다른 엔티티 추적 방지 (선택 사항)
                    deadPlayer.sendMessage(ChatColor.RED + "[GGORRI] 당신은 사망했습니다. 잠시 후 부활합니다...");

                    // 플레이어 사망 시 기존 액션바 메시지 제거 (부활 타이머 메시지를 위해)
                    gameManager.getActionBarManager().removeMessage(deadPlayer.getUniqueId(), ActionBarManager.PRIORITY_BORDER_OUTSIDE);
                    gameManager.getActionBarManager().removeMessage(deadPlayer.getUniqueId(), ActionBarManager.PRIORITY_BORDER_IMMINENT_SHRINK);
                    gameManager.getActionBarManager().removeMessage(deadPlayer.getUniqueId(), ActionBarManager.PRIORITY_BORDER_APPROACHING);
                    gameManager.getActionBarManager().removeMessage(deadPlayer.getUniqueId(), ActionBarManager.PRIORITY_BORDER_INSIDE);
                    gameManager.getActionBarManager().removeMessage(deadPlayer.getUniqueId(), ActionBarManager.PRIORITY_PLAYER_ROLE_INFO);
                }
            }
        }.runTaskLater(plugin, 1L); // 다음 틱에 실행하여 게임모드 변경 (즉시 적용)

        // 사망 처리 로직은 GameRulesManager에 위임
        DamageCause cause = event.getEntity().getLastDamageCause() != null ? event.getEntity().getLastDamageCause().getCause() : DamageCause.CUSTOM;
        gameManager.getGameRulesManager().handlePlayerDeath(deadPlayer, deadPlayer.getKiller(), cause);
    }
}