package dev.o8o1o5.ggorri.manager;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.game.PlayerGameData;
import dev.o8o1o5.ggorri.game.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class ChainManager {
    private final GGORRI plugin;
    private final GameManager gameManager;
    private final Map<UUID, PlayerGameData> playersInGame;
    private final Random random;

    public ChainManager(GGORRI plugin, GameManager gameManager, Map<UUID, PlayerGameData> playersInGame) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.playersInGame = playersInGame;
        this.random = new Random();
    }

    /**
     * 플레이어들에게 꼬리 고리 타겟을 설정하는 로직
     */
    public void setupPlayerTargets() {
        List<UUID> shuffledPlayers = new ArrayList<>(playersInGame.keySet());
        Collections.shuffle(shuffledPlayers, random);

        for (int i = 0; i < shuffledPlayers.size(); i ++) {
            UUID currentPlayerUUID = shuffledPlayers.get(i);
            UUID nexPlayerUUID = shuffledPlayers.get((i + 1) % shuffledPlayers.size());

            PlayerGameData currentPlayerGameData = playersInGame.get(currentPlayerUUID);
            if (currentPlayerGameData != null) {
                currentPlayerGameData.setDirectTargetUUID(nexPlayerUUID);
                currentPlayerGameData.setRole(PlayerRole.LEADER);
            }
        }
        plugin.getLogger().info("[GGORRI] 플레이어 타겟 설정을 완료했습니다!");
    }

    /**
     * 사망한 플레이어를 공격자의 노예로 종속시키고 꼬리 고리 구조를 업데이트합니다.
     * 이 메서드는 '정상 처치'가 발생했을 때 호출됩니다.
     *
     * @param killerUUID 사망한 플레이어를 처치한 공격자 (새로운 팀장)의 UUID
     * @param victimUUID 노예가 될 사망한 플레이어의 UUID
     */
    public void enslavePlayerAndAdjustTarget(UUID killerUUID, UUID victimUUID) {
        PlayerGameData killerData = playersInGame.get(killerUUID);
        PlayerGameData victimData = playersInGame.get(victimUUID);

        if (killerData == null || victimData == null) {
            plugin.getLogger().warning("[GGORRI] enslaving: 유효하지 않은 플레이어 데이터.");
            return;
        }

        victimData.setRole(PlayerRole.SLAVE);
        // !!! 수정: 노예가 된 플레이어에게 마스터 UUID 설정 !!!
        victimData.setMasterUUID(killerUUID); // <-- 이 줄이 새로 추가되었습니다!
        plugin.getLogger().info("[GGORRI] " + plugin.getServer().getOfflinePlayer(victimUUID).getName() + "님이 " + plugin.getServer().getOfflinePlayer(killerUUID).getName() + "님의 노예가 되었습니다.");

        UUID oldVictimTargetUUID = victimData.getDirectTargetUUID();

        killerData.setDirectTargetUUID(oldVictimTargetUUID);
        plugin.getLogger().info("[GGORRI] " + plugin.getServer().getOfflinePlayer(killerUUID).getName() + "님의 새로운 타겟: " +
                (oldVictimTargetUUID != null ? plugin.getServer().getOfflinePlayer(oldVictimTargetUUID).getName() : "없음 (고리가 끊어짐)"));

        victimData.setDirectTargetUUID(null);
        plugin.getLogger().info("[GGORRI] " + plugin.getServer().getOfflinePlayer(victimUUID).getName() + "님은 이제 타겟이 없습니다.");

        plugin.getServer().broadcastMessage(ChatColor.AQUA + "[GGORRI] " + plugin.getServer().getOfflinePlayer(victimUUID).getName() + "님이 " +
                plugin.getServer().getOfflinePlayer(killerUUID).getName() + "팀에 편입되었습니다!");
    }

    /**
     * 팀장 이탈 시 노예들 중 한 명을 새 팀장으로 승격시키고,
     * 나머지는 그 새 팀장의 노예로 종속시킨 후 기존 고리 관계를 유지합니다.
     * @param leavingLeaderUUID 이탈하는 팀장의 UUID
     */
    public void handleLeaderExit(UUID leavingLeaderUUID) {
        plugin.getServer().broadcastMessage(ChatColor.RED + "[GGORRI] " + plugin.getServer().getOfflinePlayer(leavingLeaderUUID).getName() + " 플레이어가 게임을 이탈했습니다!");
        plugin.getLogger().info("[GGORRI] 팀장 이탈 처리 시작: " + plugin.getServer().getOfflinePlayer(leavingLeaderUUID).getName());

        PlayerGameData leavingLeaderData = playersInGame.get(leavingLeaderUUID);
        if (leavingLeaderData == null) {
            plugin.getLogger().warning("[GGORRI] handleLeaderExit: 이탈 팀장(" + plugin.getServer().getOfflinePlayer(leavingLeaderUUID).getName() + ")의 PlayerGameData를 찾을 수 없습니다. 고리 조정 없이 종료.");
            return;
        }

        UUID oldLeaderTargetUUID = leavingLeaderData.getDirectTargetUUID(); // 이탈 팀장의 직계 타겟
        // playersInGame.remove(leavingLeaderUUID); // 이탈한 팀장은 GameManager의 leaveGame에서 제거됩니다.

        // 1. 이탈한 팀장의 노예들을 식별 (masterUUID 필드를 활용)
        List<UUID> formerSlavesOfLeavingLeader = playersInGame.values().stream()
                .filter(data -> data.getRole() == PlayerRole.SLAVE && Objects.equals(data.getMasterUUID(), leavingLeaderUUID))
                .map(PlayerGameData::getPlayerUUID)
                .collect(Collectors.toList());

        UUID newLeaderUUID = null;
        if (!formerSlavesOfLeavingLeader.isEmpty()) {
            // 이탈 팀장의 노예들 중 한 명을 무작위로 선택하여 새로운 팀장으로 승격
            newLeaderUUID = formerSlavesOfLeavingLeader.get(random.nextInt(formerSlavesOfLeavingLeader.size()));
            PlayerGameData newLeaderData = playersInGame.get(newLeaderUUID);

            if (newLeaderData != null) {
                newLeaderData.setRole(PlayerRole.LEADER); // 새 팀장으로 승격
                newLeaderData.setDirectTargetUUID(oldLeaderTargetUUID); // **[핵심]** 이탈한 팀장의 이전 타겟을 그대로 물려받음
                newLeaderData.setMasterUUID(null); // 새 팀장은 이제 마스터가 없음 (리더는 마스터가 없음)

                // ... (생략) ...
            }
        }

        // 2. 새 팀장을 제외한 나머지 노예들은 모두 이 새 팀장의 노예로 종속시킵니다.
        if (newLeaderUUID != null) {
            for (UUID slaveUUID : formerSlavesOfLeavingLeader) {
                if (!slaveUUID.equals(newLeaderUUID)) {
                    PlayerGameData slaveData = playersInGame.get(slaveUUID);
                    if (slaveData != null) {
                        slaveData.setRole(PlayerRole.SLAVE); // 역할이 SLAVE임을 확실히 함
                        slaveData.setDirectTargetUUID(null); // 노예는 직계 타겟 없음
                        // !!! 수정: 노예가 된 플레이어에게 새 팀장의 마스터 UUID 설정 !!!
                        slaveData.setMasterUUID(newLeaderUUID); // <-- 이 줄이 새로 추가되었습니다!
                        Player slavePlayer = Bukkit.getPlayer(slaveUUID);
                        if (slavePlayer != null) {
                            slavePlayer.sendMessage(ChatColor.GRAY + "[GGORRI] 당신은 이제 새로운 팀장 " + Bukkit.getOfflinePlayer(newLeaderUUID).getName() + "님의 노예가 되었습니다.");
                        }
                    }
                }
            }
            plugin.getLogger().info("[GGORRI] 새로운 팀장(" + Bukkit.getOfflinePlayer(newLeaderUUID).getName() + ")을 제외한 나머지 노예들은 그에게 종속되었습니다.");
        } else {
            // ... (생략) ...
        }

        gameManager.getGameRulesManager().checkWinCondition();

        plugin.getLogger().info("[GGORRI] 팀장 이탈 처리 완료.");
    }

    /**
     * 남은 모든 LEADER들을 대상으로 새로운 꼬리 고리를 재설정합니다.
     * 이 메서드는 더 이상 handleLeaderExit에서 직접 호출되지 않습니다.
     * 필요한 경우 외부에서 명시적으로 호출되어야 합니다.
     */
    public void reorganizeTargetRing() {
        // 이 메서드는 이제 handleLeaderExit에서 호출되지 않으므로,
        // 필요하다면 GameRulesManager나 GameManager 등 다른 곳에서 전체 고리를 재편성하고 싶을 때 호출할 수 있습니다.
        // 현재 요구사항에 따라 handleLeaderExit에서는 호출하지 않습니다.
        List<UUID> remainingLeadersUUIDs = playersInGame.values().stream()
                .filter(data -> data.getRole() == PlayerRole.LEADER)
                .map(PlayerGameData::getPlayerUUID)
                .collect(Collectors.toList());

        if (remainingLeadersUUIDs.size() > 1) {
            Collections.shuffle(remainingLeadersUUIDs, random);
            for (int i = 0; i < remainingLeadersUUIDs.size(); i++) {
                UUID current = remainingLeadersUUIDs.get(i);
                UUID target = remainingLeadersUUIDs.get((i + 1) % remainingLeadersUUIDs.size());
                PlayerGameData currentData = playersInGame.get(current);
                if (currentData != null) {
                    currentData.setDirectTargetUUID(target);
                    currentData.setMasterUUID(null); // 리더는 마스터 없음
                }
                Player p = Bukkit.getPlayer(current);
                if (p != null) {
                    p.sendMessage(ChatColor.AQUA + "[GGORRI] 새로운 꼬리 고리가 재설정되었습니다. 당신의 새로운 타겟: " + plugin.getServer().getOfflinePlayer(target).getName());
                }
            }
            plugin.getLogger().info("[GGORRI] 전체 꼬리 고리가 재설정되었습니다.");
        } else if (remainingLeadersUUIDs.size() == 1) {
            UUID soleLeaderUUID = remainingLeadersUUIDs.get(0);
            PlayerGameData soleLeaderData = playersInGame.get(soleLeaderUUID);
            if(soleLeaderData != null) {
                soleLeaderData.setDirectTargetUUID(soleLeaderUUID);
                soleLeaderData.setMasterUUID(null);
                plugin.getLogger().info("[GGORRI] 리더가 한 명 남았고, 자기 자신을 타겟으로 설정되었습니다: " + plugin.getServer().getOfflinePlayer(soleLeaderUUID).getName());
            }
        } else {
            plugin.getLogger().info("[GGORRI] 모든 LEADER가 사라져 꼬리 고리를 재설정할 수 없습니다.");
        }
    }
}