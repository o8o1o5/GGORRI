package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PlayerManager {
    private final GGORRI plugin;
    private  final Map<UUID, PlayerGameData> playersInGame;

    private final Map<UUID, Map<UUID, Long>> playerDamageTracker;
    private static final long DAMAGE_TRACK_DURATION_MILLIS = 15 * 1000;

    public PlayerManager(GGORRI plugin, Map<UUID, PlayerGameData> playersInGame) {
        this.plugin = plugin;
        this.playersInGame = playersInGame;
        this.playerDamageTracker = new ConcurrentHashMap<>();
    }

    /**
     * 게임에 새로운 플레이어를 추가하고 PlayerGameData를 생성합니다.
     * @param player 게임에 참여할 플레이어
     */
    public void addPlayerToGame(Player player) {
        playersInGame.put(player.getUniqueId(), new PlayerGameData(player.getUniqueId()));
    }

    /**
     * 게임에서 플레이어를 제거하고 인벤토리 및 상태를 초기화합니다.
     * @param player 게임에서 나갈 플레이어
     */
    public void removePlayerFromGame(Player player) {
        playersInGame.remove(player.getUniqueId());
        resetPlayer(player);
    }

    /**
     * 플레이어의 인벤토리, 체력, 상태 효과 등을 초기화합니다.
     * @param player 초기화할 플레이어
     */
    public void resetPlayer(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setExp(0);
        player.setLevel(0);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        plugin.getLogger().log(Level.FINE, "[GGORRI] " + player.getName() + "님의 상태 초기화");
    }

    /**
     * 플레이어 간 데미지 기록을 추가하거나 업데이트합니다.
     * @param damagerUUID 데미지를 준 플레이어의 UUID
     * @param victimUUID 데미지를 받은 플레이어의 UUID
     */
    public void recordPlayerDamage(UUID damagerUUID, UUID victimUUID) {
        playerDamageTracker
                .computeIfAbsent(damagerUUID, k -> new ConcurrentHashMap<>())
                .put(victimUUID, System.currentTimeMillis());
        plugin.getLogger().log(Level.FINE, "[GGORRI] 데미지 " + plugin.getServer().getPlayer(damagerUUID).getName() + " -> " + plugin.getServer().getPlayer(victimUUID));
    }

    /**
     * 주어진 플레이어에게 마지막으로 데미지를 준 유효한 공격자를 반환합니다 (15초 이내).
     * @param victimUUID 데미지를 받은 플레이어의 UUID
     * @return 유효한 공격자 UUID (없으면 null)
     */
    public UUID getLastAttacker(UUID victimUUID) {
        long fifteenSecondsAgo = System.currentTimeMillis() - DAMAGE_TRACK_DURATION_MILLIS;
        UUID lastAttacker = null;
        long latestDamageTime = -1;

        for (Map.Entry<UUID, Map<UUID, Long>> damagerEntry : playerDamageTracker.entrySet()) {
            UUID damagerUUID = damagerEntry.getKey();
            Map<UUID, Long> victimTimes = damagerEntry.getValue();

            if (victimTimes.containsKey(victimUUID)) {
                long damageTime = victimTimes.get(victimUUID);
                if (damageTime >= fifteenSecondsAgo) { // 15초 이내의 데미지
                    if (damageTime > latestDamageTime) { // 가장 최근의 데미지
                        latestDamageTime = damageTime;
                        lastAttacker = damagerUUID;
                    }
                }
            }
        }

        return lastAttacker;
    }

    /**
     * 플레이어 사망 시 데미지 기록을 정리합니다.
     * @param playerUUID 사망한 플레이어의 UUID
     */
    public void clearDamageRecordsForPlayer(UUID playerUUID) {
        // 사망한 플레이어가 피해자인 모든 기록에서 제거
        playerDamageTracker.forEach((damager, victimMap) -> victimMap.remove(playerUUID));

        // 사망한 플레이어가 가해자인 모든 기록 제거
        playerDamageTracker.remove(playerUUID);
        plugin.getLogger().log(Level.FINE, "[GGORRI] 플레이어 " + plugin.getServer().getOfflinePlayer(playerUUID).getName() + "의 데미지 기록 정리 완료.");
    }

    /**
     * 특정 플레이어의 게임 데이터를 반환합니다.
     * @param playerUUID 플레이어의 UUID
     * @return PlayerGameData 객체 또는 null
     */
    public PlayerGameData getPlayerGameData(UUID playerUUID) {
        return playersInGame.get(playerUUID);
    }

    /**
     * Return unmodifiable map of PlayerGameData
     */
    public Map<UUID, PlayerGameData> getAllPlayersGameData() {
        return Collections.unmodifiableMap(playersInGame);
    }
}
