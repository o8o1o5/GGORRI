package dev.o8o1o5.ggorri.game;

import java.util.UUID;

public class PlayerGameData {
    private final UUID playerUUID;
    private PlayerRole role;
    private UUID directTargetUUID; // 직계 타겟 (다음 꼬리)
    private int deathCount; // 사망 횟수

    public PlayerGameData(UUID playerUUID) {
        this.playerUUID = playerUUID;
        this.role = PlayerRole.MEMBER; // 초기 역할은 MEMBER (아직 팀장/노예 아님)
        this.directTargetUUID = null;
        this.deathCount = 0;
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

    @Override
    public String toString() {
        return "PlayerGameData{" +
                "playerUUID=" + playerUUID +
                ", role=" + role +
                ", directTargetUUID=" + directTargetUUID +
                ", deathCount=" + deathCount +
                '}';
    }
}