package dev.o8o1o5.ggorri;

import dev.o8o1o5.ggorri.GGORRI;
import dev.o8o1o5.ggorri.manager.GameManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class GGORRICommand implements CommandExecutor, TabCompleter {

    private final GGORRI plugin;
    private final GameManager gameManager;

    public GGORRICommand(GGORRI plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "§l===== GGORRI 명령어 =====");
            sender.sendMessage(ChatColor.YELLOW + "/ggorri join - 게임에 참가합니다.");
            sender.sendMessage(ChatColor.YELLOW + "/ggorri leave - 게임에서 나갑니다.");
            if (sender.hasPermission("ggorri.admin")) {
                sender.sendMessage(ChatColor.YELLOW + "/ggorri start - (관리자) 게임을 시작합니다.");
                sender.sendMessage(ChatColor.YELLOW + "/ggorri stop - (관리자) 게임을 종료합니다.");
            }
            sender.sendMessage(ChatColor.YELLOW + "§l==========================");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "join":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "[GGORRI] 플레이어만 이 명령어를 사용할 수 있습니다.");
                    return true;
                }
                Player player = (Player) sender;
                if (gameManager.joinGame(player)) {
                    // player.sendMessage(ChatColor.GREEN + "[GGORRI] 게임에 참가했습니다! 현재 참가 인원: " + gameManager.getPlayersInGameCount());
                } else {
                    // player.sendMessage(ChatColor.RED + "[GGORRI] 게임에 참가할 수 없습니다. 게임 상태를 확인해주세요.");
                }
                break;

            case "leave":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "[GGORRI] 플레이어만 이 명령어를 사용할 수 있습니다.");
                    return true;
                }
                Player p = (Player) sender;
                if (gameManager.leaveGame(p)) {
                    // p.sendMessage(ChatColor.GREEN + "[GGORRI] 게임에서 성공적으로 나갔습니다. 현재 참가 인원: " + gameManager.getPlayersInGameCount());
                } else {
                    // p.sendMessage(ChatColor.RED + "[GGORRI] 게임에 참여하고 있지 않습니다.");
                }
                break;

            case "start":
                if (!sender.hasPermission("ggorri.admin")) {
                    sender.sendMessage(ChatColor.RED + "[GGORRI] 이 명령어를 사용할 권한이 없습니다.");
                    return true;
                }
                // GameManager.startGame()의 반환값을 활용
                if (gameManager.startGame()) {
                    // sender.sendMessage(ChatColor.GREEN + "[GGORRI] GGORRI 게임 시작 요청이 성공적으로 처리되었습니다!");
                } else {
                    // sender.sendMessage(ChatColor.RED + "[GGORRI] GGORRI 게임을 시작할 수 없습니다. (상태 확인 또는 플레이어 부족)");
                }
                break;

            case "stop":
                if (!sender.hasPermission("ggorri.admin")) {
                    sender.sendMessage(ChatColor.RED + "[GGORRI] 이 명령어를 사용할 권한이 없습니다.");
                    return true;
                }
                // GameManager.stopGame()의 반환값을 활용
                if (gameManager.stopGame()) {
                    // sender.sendMessage(ChatColor.GREEN + "[GGORRI] GGORRI 게임을 성공적으로 종료했습니다.");
                } else {
                    // sender.sendMessage(ChatColor.RED + "[GGORRI] GGORRI 게임이 활성화된 상태가 아닙니다.");
                }
                break;

            default:
                sendHelpMessage(sender);
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>();
            subCommands.add("join");
            subCommands.add("leave");
            if (sender.hasPermission("ggorri.admin")) {
                subCommands.add("start");
                subCommands.add("stop");
            }
            for (String s : subCommands) {
                if (s.startsWith(args[0].toLowerCase())) {
                    completions.add(s);
                }
            }
        }
        return completions;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "§l===== GGORRI 명령어 =====");
        sender.sendMessage(ChatColor.YELLOW + "/ggorri join - 게임에 참가합니다.");
        sender.sendMessage(ChatColor.YELLOW + "/ggorri leave - 게임에서 나갑니다.");
        if (sender.hasPermission("ggorri.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/ggorri start - (관리자) 게임을 시작합니다.");
            sender.sendMessage(ChatColor.YELLOW + "/ggorri stop - (관리자) 게임을 종료합니다.");
        }
        sender.sendMessage(ChatColor.YELLOW + "§l==========================");
    }
}