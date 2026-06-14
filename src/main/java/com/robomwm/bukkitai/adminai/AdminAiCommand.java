package com.robomwm.bukkitai.adminai;

import com.robomwm.bukkitai.BukkitAI;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class AdminAiCommand implements CommandExecutor, TabCompleter
{
    private static final List<String> SUBCOMMANDS = List.of(
            "on", "off", "status", "reload", "abort", "run", "approve", "deny",
            "interactive", "autonomous", "check");
    private static final List<String> ON_OFF = List.of("on", "off");

    private final AdminAiService adminAiService;

    public AdminAiCommand(BukkitAI plugin)
    {
        adminAiService = new AdminAiService(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (!sender.hasPermission("mlg.admin"))
            return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("status"))
        {
            sender.sendMessage(ChatColor.GOLD + "Admin AI: " + adminAiService.getStatus());
            return true;
        }

        switch (args[0].toLowerCase())
        {
            case "on":
            case "enable":
                adminAiService.setEnabled(true);
                sender.sendMessage(ChatColor.GREEN + "Admin AI enabled.");
                return true;
            case "off":
            case "disable":
                adminAiService.setEnabled(false);
                sender.sendMessage(ChatColor.RED + "Admin AI disabled. Running task aborted.");
                return true;
            case "abort":
            case "cancel":
                adminAiService.abortCurrentTask();
                sender.sendMessage(ChatColor.RED + "Admin AI task aborted.");
                return true;
            case "approve":
            case "yes":
                adminAiService.approve(true);
                sender.sendMessage(ChatColor.GREEN + "Action approved.");
                return true;
            case "deny":
            case "no":
                adminAiService.approve(false);
                sender.sendMessage(ChatColor.RED + "Action denied.");
                return true;
            case "interactive":
                if (args.length < 2)
                {
                    sender.sendMessage(ChatColor.GOLD + "Interactive mode: " + (adminAiService.getStatus().contains("interactive") ? "on" : "off"));
                    return true;
                }
                boolean interactive = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                adminAiService.setInteractive(interactive);
                sender.sendMessage(ChatColor.GREEN + "Interactive mode " + (interactive ? "enabled" : "disabled") + ".");
                return true;
            case "autonomous":
                if (args.length < 2)
                {
                    boolean isAutonomous = !adminAiService.getStatus().contains("interactive");
                    sender.sendMessage(ChatColor.GOLD + "Autonomous mode: " + (isAutonomous ? "on" : "off"));
                    return true;
                }
                boolean autonomous = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                adminAiService.setInteractive(!autonomous);
                sender.sendMessage(ChatColor.GREEN + "Autonomous mode " + (autonomous ? "enabled" : "disabled")
                        + ". Proactive tasks will " + (autonomous ? "fix issues directly" : "propose plans") + ".");
                return true;
            case "reload":
                adminAiService.reload();
                sender.sendMessage(ChatColor.GREEN + "Admin AI config reloaded.");
                return true;
            case "run":
                if (args.length < 2)
                    return false;
                adminAiService.run(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                return true;
            case "check":
                adminAiService.run(sender, "Perform a maintenance check.", true);
                sender.sendMessage(ChatColor.GREEN + "Proactive maintenance check triggered.");
                return true;
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        if (!sender.hasPermission("mlg.admin"))
            return List.of();

        if (args.length == 1)
        {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 2)
        {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("interactive".equals(sub) || "autonomous".equals(sub))
            {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return ON_OFF.stream()
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    public void shutdown()
    {
        adminAiService.shutdown();
    }
}
