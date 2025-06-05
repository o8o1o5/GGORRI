package dev.o8o1o5.ggorri.listeners;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.manager.PlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
                if (event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
                    UUID targetUUID = playerManager.getPlayerGameData(player.getUniqueId()).getDirectTargetUUID();
                    Location targetLocation = Bukkit.getPlayer(targetUUID).getLocation();

                    player.sendMessage("[GGORRI] 타겟의 위치는 (" + targetLocation.getX() + ", " + targetLocation.getY() + ", " + targetLocation.getZ() + ") 입니다..");
                    event.setCancelled(true);
                }
            }
        }
    }
}
