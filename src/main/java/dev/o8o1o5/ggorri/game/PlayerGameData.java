// dev.o8o1o5.ggorri.game.PlayerGameData.java
package dev.o8o1o5.ggorri.game;

import java.util.UUID;

public class PlayerGameData {
    private final UUID playerUUID;
    private PlayerRole role;
    private UUID directTargetUUID; // 직계 타겟 (다음 꼬리)
    private int deathCount; // 사망 횟수
    private UUID masterUUID; // 이 플레이어가 노예일 경우, 자신의 팀장 UUID

    public PlayerGameData(UUID playerUUID) {
        this.playerUUID = playerUUID;
        this.role = PlayerRole.MEMBER; // 초기 역할은 MEMBER (아직 팀장/노예 아님)
        this.directTargetUUID = null;
        this.deathCount = 0;
        this.masterUUID = null; // 초기에는 팀장 없음
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public PlayerRole getRole() {
        return role;
    }

    public void setRole(PlayerRole role) {
        this.role = role;
    }

    public UUID getDirectTargetUUID() {
        return directTargetUUID;
    }

    public void setDirectTargetUUID(UUID directTargetUUID) {
        this.directTargetUUID = directTargetUUID;
    }

    public int getDeathCount() {
        return deathCount;
    }

    public void incrementDeathCount() {
        this.deathCount++;
    }

    public UUID getMasterUUID() {
        return masterUUID;
    }

    public void setMasterUUID(UUID masterUUID) {
        this.masterUUID = masterUUID;
    }

    @Override
    public String toString() {
        return "PlayerGameData{" +
                "playerUUID=" + playerUUID +
                ", role=" + role +
                ", directTargetUUID=" + directTargetUUID +
                ", deathCount=" + deathCount +
                ", masterUUID=" + masterUUID +
                '}';
    }
}