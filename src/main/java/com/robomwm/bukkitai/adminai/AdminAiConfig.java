package com.robomwm.bukkitai.adminai;

import com.robomwm.bukkitai.BukkitAI;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

class AdminAiConfig
{
    private final BukkitAI plugin;

    AdminAiConfig(BukkitAI plugin)
    {
        this.plugin = plugin;
        plugin.reloadConfig();
        installDefaults();
        plugin.saveConfig();
    }

    void reload()
    {
        plugin.reloadConfig();
        installDefaults();
    }

    private void installDefaults()
    {
        FileConfiguration config = plugin.getConfig();
        
        // Copy existing values to temporary storage
        org.bukkit.configuration.MemoryConfiguration old = new org.bukkit.configuration.MemoryConfiguration();
        for (String key : config.getKeys(true))
            old.set(key, config.get(key));

        // Clear everything
        for (String key : config.getKeys(false))
            config.set(key, null);

        // Repopulate with only recognized keys
        config.set("admin-ai.enabled", old.getBoolean("admin-ai.enabled", false));
        config.set("admin-ai.interactive", old.getBoolean("admin-ai.interactive", true));
        config.set("admin-ai.max-iterations", old.getInt("admin-ai.max-iterations", 12));
        config.set("admin-ai.max-command-seconds", old.getInt("admin-ai.max-command-seconds", 300));
        config.set("admin-ai.max-file-bytes", old.getInt("admin-ai.max-file-bytes", 65536));
        config.set("admin-ai.log-tail-lines", old.getInt("admin-ai.log-tail-lines", 200));
        config.set("admin-ai.approval-mode", old.getString("admin-ai.approval-mode", "human"));
        config.set("admin-ai.approval-timeout-minutes", old.getInt("admin-ai.approval-timeout-minutes", 5));
        config.set("admin-ai.provider-order", old.getList("admin-ai.provider-order", List.of("ollama")));

        // Dynamic sections: providers
        ConfigurationSection providers = old.getConfigurationSection("admin-ai.providers");
        if (providers != null)
            config.set("admin-ai.providers", providers);
        else
            installDefaultOllama(config, "admin-ai.providers.ollama");

        // Dynamic sections: approval-providers
        ConfigurationSection approvalProviders = old.getConfigurationSection("admin-ai.approval-providers");
        if (approvalProviders != null)
            config.set("admin-ai.approval-providers", approvalProviders);
        else
            installDefaultOllama(config, "admin-ai.approval-providers.ollama");

        config.set("admin-ai.actions.log-files", old.getList("admin-ai.actions.log-files", List.of("logs/latest.log")));
        config.set("admin-ai.actions.allowed-command-prefixes", old.getList("admin-ai.actions.allowed-command-prefixes", List.of(
                "git status",
                "git diff",
                "git add",
                "git commit",
                "git push",
                "mvn -B --no-transfer-progress compile",
                "mvn -B --no-transfer-progress test",
                "mvn -B --no-transfer-progress package",
                "mvn -B --no-transfer-progress clean package",
                "mvn -B --no-transfer-progress clean install"
        )));
        config.set("admin-ai.actions.denied-command-contains", old.getList("admin-ai.actions.denied-command-contains", List.of(
                " rm ", " rm -", " reset --hard", " checkout --", " clean -fd", " clean -fx", " rebase ", " push --force",
                " --force", " --amend", " chmod ", " chown "
        )));

        plugin.getDataFolder().mkdirs();
    }

    private void installDefaultOllama(ConfigurationSection config, String path)
    {
        config.set(path + ".enabled", true);
        config.set(path + ".protocol", "ollama-native");
        config.set(path + ".endpoint", "http://localhost:4000/api/chat");
        config.set(path + ".model", "llama3.2:3b");
        config.set(path + ".api-key", "ollama");
        config.set(path + ".timeout-seconds", 90);
    }

    File getRootFolder()
    {
        File root = plugin.getServer().getWorldContainer().getAbsoluteFile();
        if (root.exists())
            return root;

        File dataFolder = plugin.getDataFolder().getAbsoluteFile();
        File pluginsFolder = dataFolder.getParentFile();
        File rootFolder = (pluginsFolder != null) ? pluginsFolder.getParentFile() : null;
        return (rootFolder != null) ? rootFolder : dataFolder;
    }

    List<File> getSourceRoots()
    {
        return List.of(getRootFolder());
    }

    boolean isEnabled()
    {
        return plugin.getConfig().getBoolean("admin-ai.enabled", false);
    }

    void setEnabled(boolean enabled)
    {
        plugin.getConfig().set("admin-ai.enabled", enabled);
        plugin.saveConfig();
    }

    boolean isInteractive()
    {
        return plugin.getConfig().getBoolean("admin-ai.interactive", true);
    }

    void setInteractive(boolean interactive)
    {
        plugin.getConfig().set("admin-ai.interactive", interactive);
        plugin.saveConfig();
    }

    int getInt(String path)
    {
        return plugin.getConfig().getInt(path);
    }

    List<String> getStringList(String path)
    {
        return plugin.getConfig().getStringList(path);
    }

    String getString(String path)
    {
        return plugin.getConfig().getString(path, "");
    }

    String getApprovalMode()
    {
        return plugin.getConfig().getString("admin-ai.approval-mode", "human").toLowerCase(java.util.Locale.ROOT);
    }

    int getApprovalTimeoutMinutes()
    {
        return plugin.getConfig().getInt("admin-ai.approval-timeout-minutes", 5);
    }

    List<AiProvider> getProviders()
    {
        return loadProviders("admin-ai.providers", "admin-ai.provider-order");
    }

    List<AiProvider> getApprovalProviders()
    {
        // Scan all sections under approval-providers for enabled ones
        List<AiProvider> providers = new ArrayList<>();
        ConfigurationSection parent = plugin.getConfig().getConfigurationSection("admin-ai.approval-providers");
        if (parent == null)
            return providers;
        for (String name : parent.getKeys(false))
        {
            ConfigurationSection section = parent.getConfigurationSection(name);
            if (section == null || !section.getBoolean("enabled"))
                continue;
            providers.add(new AiProvider(
                    name,
                    section.getString("protocol", "ollama-native"),
                    section.getString("endpoint", ""),
                    section.getString("model", ""),
                    section.getString("api-key", ""),
                    section.getInt("timeout-seconds", 30)
            ));
        }
        return providers;
    }

    private List<AiProvider> loadProviders(String sectionPath, String orderPath)
    {
        List<AiProvider> providers = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList(orderPath))
        {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionPath + "." + name);
            if (section == null || !section.getBoolean("enabled"))
                continue;
            providers.add(new AiProvider(
                    name,
                    section.getString("protocol", "ollama-native"),
                    section.getString("endpoint", ""),
                    section.getString("model", ""),
                    section.getString("api-key", ""),
                    section.getInt("timeout-seconds", 90)
            ));
        }
        return providers;
    }
}
