package dev.o8o1o5.ggorri.commands;

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
            sender.sendMessage(ChatColor.YELLOW + "===== GGORRI 명령어 =====");
            sender.sendMessage(ChatColor.YELLOW + "/ggorri join - 게임에 참가합니다.");
            sender.sendMessage(ChatColor.YELLOW + "/ggorri leave - 게임에서 나갑니다.");
            if (sender.hasPermission("ggorri.admin")) {
                sender.sendMessage(ChatColor.YELLOW + "/ggorri start - (관리자) 게임을 시작합니다.");
                sender.sendMessage(ChatColor.YELLOW + "/ggorri stop - (관리자) 게임을 종료합니다.");
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "join":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "플레이어만 이 명령어를 사용할 수 있습니다.");
                    return true;
                }
                Player player = (Player) sender;
                if (gameManager.joinGame(player)) {
                    player.sendMessage(ChatColor.GREEN + "GGORRI 게임에 참가했습니다! 플레이어를 기다리는 중...");
                } else {
                    player.sendMessage(ChatColor.RED + "GGORRI 게임에 참가할 수 없습니다. 게임이 이미 시작되었거나 참가할 수 없는 상태입니다.");
                }
                break;

            case "leave":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "플레이어만 이 명령어를 사용할 수 있습니다.");
                    return true;
                }
                Player p = (Player) sender;
                if (gameManager.leaveGame(p)) {
                    p.sendMessage(ChatColor.GREEN + "GGORRI 게임에서 나갔습니다.");
                } else {
                    p.sendMessage(ChatColor.RED + "GGORRI 게임에 참여하고 있지 않습니다.");
                }
                break;

            case "start":
                if (!sender.hasPermission("ggorri.admin")) {
                    sender.sendMessage(ChatColor.RED + "이 명령어를 사용할 권한이 없습니다.");
                    return true;
                }
                if (gameManager.startGame()) {
                    sender.sendMessage(ChatColor.GREEN + "GGORRI 게임을 시작했습니다!");
                } else {
                    sender.sendMessage(ChatColor.RED + "GGORRI 게임을 시작할 수 없습니다. 현재 상태를 확인하세요.");
                }
                break;

            case "stop":
                if (!sender.hasPermission("ggorri.admin")) {
                    sender.sendMessage(ChatColor.RED + "이 명령어를 사용할 권한이 없습니다.");
                    return true;
                }
                if (gameManager.stopGame(false)) { // 관리자 명령이므로 강제 종료가 아님
                    sender.sendMessage(ChatColor.GREEN + "GGORRI 게임을 종료했습니다.");
                } else {
                    sender.sendMessage(ChatColor.RED + "GGORRI 게임이 진행 중이지 않습니다.");
                }
                break;

            default:
                sender.sendMessage(ChatColor.RED + "알 수 없는 명령어입니다. /ggorri 를 입력하여 도움말을 확인하세요.");
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            // 첫 번째 인수에 대한 탭 완성
            List<String> subCommands = Arrays.asList("join", "leave");
            if (sender.hasPermission("ggorri.admin")) {
                subCommands = Arrays.asList("join", "leave", "start", "stop");
            }
            for (String s : subCommands) {
                if (s.startsWith(args[0].toLowerCase())) {
                    completions.add(s);
                }
            }
        }
        return completions;
    }
}