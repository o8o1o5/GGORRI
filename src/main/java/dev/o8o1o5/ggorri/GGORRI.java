package dev.o8o1o5.ggorri;

import dev.o8o1o5.ggorri.commands.GGORRICommand;
import dev.o8o1o5.ggorri.listeners.GameListener;
import dev.o8o1o5.ggorri.manager.GameManager;
import dev.o8o1o5.ggorri.manager.PlayerManager;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class GGORRI extends JavaPlugin {
    private static GGORRI instance;
    private NamespacedKey customItemIdKey;
    private GameManager gameManager;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("[GGORRI] 플러그인이 활성화되었습니다!");

        // 디버그 로그를 보기 위해 로그 레벨 설정 (필요시 활성화)
        // getLogger().setLevel(Level.FINE);

        customItemIdKey = new NamespacedKey(this, "custom_item_id");
        getLogger().info("[GGORRI] 커스텀 아이템 ID 키가 등록되었습니다 :" + customItemIdKey.getKey());

        gameManager = new GameManager(this);

        if (getCommand("ggorri") != null) {
            GGORRICommand ggorriCommand = new GGORRICommand(this, gameManager);
            getCommand("ggorri").setExecutor(ggorriCommand);
            getCommand("ggorri").setTabCompleter(ggorriCommand);
        } else {
            getLogger().warning("[GGORRI] 명령어 'ggorri'를 찾을 수 없습니다! plugin.yml을 확인해주세요.");
        }

        getLogger().info("[GGORRI] 모든 초기 설정이 완료되었습니다.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[GGORRI] 플러그인이 비활성화되었습니다!");
        // 게임이 진행 중이었다면 안전하게 종료
        if (gameManager != null && gameManager.getCurrentStatus() != GameManager.GameStatus.WAITING) {
            gameManager.stopGame(); // 플러그인 비활성화 시 강제 종료
        }
    }

    public static GGORRI getInstance() {
        return instance;
    }

    public NamespacedKey getCustomItemIdKey() {
        return customItemIdKey;
    }

    public GameManager getGameManager() {
        return gameManager;
    }
}