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
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TrackersCompassListener implements Listener {
    private final GGORRI plugin;
    private final PlayerManager playerManager;

    // 쿨타임 시간 (틱 단위: 20틱 = 1초)
    private final int COOLDOWN_TICKS = 20; // 5초 쿨타임
    private final Map<UUID, BukkitRunnable> compassUpdateTasks = new HashMap<>();

    public TrackersCompassListener(GGORRI plugin, PlayerManager playerManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        ItemStack oldItem = player.getInventory().getItem(event.getPreviousSlot());

        if (oldItem != null && oldItem.hasItemMeta()) {
            ItemMeta oldMeta = oldItem.getItemMeta();
            PersistentDataContainer oldData = oldMeta.getPersistentDataContainer();
            if (oldData.has(plugin.getCustomItemIdKey(), PersistentDataType.STRING) &&
                    "trackers_compass".equals(oldData.get(plugin.getCustomItemIdKey(), PersistentDataType.STRING))) {
                cancelCompassUpdateTask(player.getUniqueId());
            }
        }

        if (newItem != null && newItem.hasItemMeta()) {
            ItemMeta newMeta = newItem.getItemMeta();
            PersistentDataContainer newData = newMeta.getPersistentDataContainer();
            if (newData.has(plugin.getCustomItemIdKey(), PersistentDataType.STRING) &&
                    "trackers_compass".equals(newData.get(plugin.getCustomItemIdKey(), PersistentDataType.STRING))) {
                startCompassUpdateTask(player);
            }
        }
    }

    public void disableAllCompassTask() {
        for (BukkitRunnable task : compassUpdateTasks.values()) {
            task.cancel();
        }
        compassUpdateTasks.clear();
    }

    private void startCompassUpdateTask(Player player) {
        UUID playerUUID = player.getUniqueId();
        cancelCompassUpdateTask(playerUUID);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !isHoldingTrackersCompass(player)) {
                    cancelCompassUpdateTask(playerUUID);
                    return;
                }

                UUID targetUUID = playerManager.getPlayerGameData(player.getUniqueId()).getDirectTargetUUID();
                if (targetUUID == null) {
                    if (player.getWorld().getSpawnLocation() != null) {
                        player.setCompassTarget(player.getWorld().getSpawnLocation());
                    }
                    return;
                }

                Player targetPlayer = Bukkit.getPlayer(targetUUID);
                if (targetPlayer == null || !targetPlayer.isOnline() || !targetPlayer.getWorld().equals(player.getWorld())) {
                    if (player.getWorld().getSpawnLocation() != null) {
                        player.setCompassTarget(player.getWorld().getSpawnLocation());
                    }
                    return;
                }

                player.setCompassTarget(targetPlayer.getLocation());
            }
        };

        task.runTaskTimer(plugin, 0L, COOLDOWN_TICKS);
        compassUpdateTasks.put(playerUUID, task);
    }

    private void cancelCompassUpdateTask(UUID playerUUID) {
        if (compassUpdateTasks.containsKey(playerUUID)) {
            compassUpdateTasks.get(playerUUID).cancel();
            compassUpdateTasks.remove(playerUUID);
        }
    }

    private boolean isHoldingTrackersCompass(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        if (mainHand.hasItemMeta()) {
            PersistentDataContainer data = mainHand.getItemMeta().getPersistentDataContainer();
            if (data.has(plugin.getCustomItemIdKey(), PersistentDataType.STRING) &&
                    "trackers_compass".equals(data.get(plugin.getCustomItemIdKey(), PersistentDataType.STRING))) {
                return true;
            }
        }
        if (offHand.hasItemMeta()) {
            PersistentDataContainer data = offHand.getItemMeta().getPersistentDataContainer();
            if (data.has(plugin.getCustomItemIdKey(), PersistentDataType.STRING) &&
                    "trackers_compass".equals(data.get(plugin.getCustomItemIdKey(), PersistentDataType.STRING))) {
                return true;
            }
        }
        return false;
    }
}