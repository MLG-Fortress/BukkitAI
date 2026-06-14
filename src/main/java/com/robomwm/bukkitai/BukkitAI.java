package com.robomwm.bukkitai;

import org.bukkit.plugin.java.JavaPlugin;
import com.robomwm.bukkitai.adminai.AdminAiCommand;

public class BukkitAI extends JavaPlugin {
    private AdminAiCommand adminAiCommand;

    @Override
    public void onEnable() {
        adminAiCommand = new AdminAiCommand(this);
        getCommand("adminai").setExecutor(adminAiCommand);
        getCommand("adminai").setTabCompleter(adminAiCommand);
        getLogger().info("BukkitAI enabled.");
    }

    @Override
    public void onDisable() {
        if (adminAiCommand != null) {
            adminAiCommand.shutdown();
        }
        getLogger().info("BukkitAI disabled.");
    }
}
