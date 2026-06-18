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
    private final java.util.Properties secrets = new java.util.Properties();

    AdminAiConfig(BukkitAI plugin)
    {
        this.plugin = plugin;
        plugin.reloadConfig();
        loadSecrets();
        installDefaults();
        plugin.saveConfig();
    }

    void reload()
    {
        plugin.reloadConfig();
        loadSecrets();
        installDefaults();
    }

    private void loadSecrets()
    {
        secrets.clear();
        File file = new File(plugin.getDataFolder(), "secrets.properties");
        if (!file.exists())
        {
            try
            {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
                java.io.PrintWriter writer = new java.io.PrintWriter(file);
                writer.println("# API keys and sensitive credentials");
                writer.println("# Format: provider_name.api-key=your_key");
                writer.println("ollama.api-key=ollama");
                writer.println("arliai.api-key=YOUR_ARLIAI_KEY");
                writer.close();
            }
            catch (java.io.IOException e)
            {
                plugin.getLogger().warning("Could not create secrets.properties: " + e.getMessage());
            }
        }

        try (java.io.FileInputStream fis = new java.io.FileInputStream(file))
        {
            secrets.load(fis);
        }
        catch (java.io.IOException e)
        {
            plugin.getLogger().warning("Could not load secrets.properties: " + e.getMessage());
        }
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
        config.set("admin-ai.enabled", old.getBoolean("admin-ai.enabled", true));
        config.set("admin-ai.interactive", old.getBoolean("admin-ai.interactive", false));
        config.set("admin-ai.max-iterations", old.getInt("admin-ai.max-iterations", 1000));
        config.set("admin-ai.autonomous-max-iterations", old.getInt("admin-ai.autonomous-max-iterations", 1000));
        config.set("admin-ai.compact-threshold-messages", old.getInt("admin-ai.compact-threshold-messages", 15));
        config.set("admin-ai.proactive-interval-minutes", old.getInt("admin-ai.proactive-interval-minutes", 10));
        config.set("admin-ai.proactive-mode", old.getString("admin-ai.proactive-mode", "PLANNING"));
        config.set("admin-ai.max-command-seconds", old.getInt("admin-ai.max-command-seconds", 300));
        config.set("admin-ai.max-file-bytes", old.getInt("admin-ai.max-file-bytes", 131072));
        config.set("admin-ai.max-context-tokens", old.getInt("admin-ai.max-context-tokens", 32000));
        config.set("admin-ai.log-tail-lines", old.getInt("admin-ai.log-tail-lines", 500));
        config.set("admin-ai.approval-mode", old.getString("admin-ai.approval-mode", "ai"));
        config.set("admin-ai.approval-timeout-minutes", old.getInt("admin-ai.approval-timeout-minutes", 5));
        config.set("admin-ai.provider-order", old.getList("admin-ai.provider-order", List.of("ollama", "arliai")));

        // Dynamic sections: providers
        ConfigurationSection providers = old.getConfigurationSection("admin-ai.providers");
        if (providers != null)
            config.set("admin-ai.providers", providers);
        else
        {
            installDefaultOllama(config, "admin-ai.providers.ollama");
            installDefaultArliAI(config, "admin-ai.providers.arliai");
        }

        config.set("admin-ai.actions.log-files", old.getList("admin-ai.actions.log-files", List.of("logs/latest.log")));
        config.set("admin-ai.actions.allowed-command-prefixes", old.getList("admin-ai.actions.allowed-command-prefixes", List.of(
                "ls",
                "cat",
                "grep",
                "find",
                "pwd",
                "head",
                "tail",
                "wc",
                "du",
                "df",
                "git status",
                "git diff",
                "git log",
                "git branch",
                "git show",
                "git add",
                "git commit",
                "git push",
                "mvn -B --no-transfer-progress compile",
                "mvn -B --no-transfer-progress test",
                "mvn -B --no-transfer-progress package",
                "mvn -B --no-transfer-progress clean package",
                "mvn -B --no-transfer-progress clean install"
        )));
        config.set("admin-ai.actions.allowed-file-paths", old.getList("admin-ai.actions.allowed-file-paths", List.of("ai-notes.md", "ai-diagnostics.md")));
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
        config.set(path + ".endpoint", "http://localhost:11434/api/chat");
        config.set(path + ".model", "qwen3.5:2b");
        config.set(path + ".api-key", "ollama");
        config.set(path + ".timeout-seconds", 600);
    }

    private void installDefaultArliAI(ConfigurationSection config, String path)
    {
        config.set(path + ".enabled", true);
        config.set(path + ".protocol", "openai-compatible");
        config.set(path + ".endpoint", "https://api.arliai.com/v1/chat/completions");
        config.set(path + ".model", "Qwen3.5-27B-Derestricted");
        config.set(path + ".api-key", "arliai");
        config.set(path + ".timeout-seconds", 600);
        config.set(path + ".sampling.temperature", 0.7);
        config.set(path + ".sampling.top_p", 0.8);
        config.set(path + ".sampling.top_k", 20);
        config.set(path + ".sampling.presence_penalty", 1.5);
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
        return plugin.getConfig().getBoolean("admin-ai.enabled", true);
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

    AdminAiService.TaskMode getProactiveMode()
    {
        try
        {
            return AdminAiService.TaskMode.valueOf(getString("admin-ai.proactive-mode").toUpperCase(java.util.Locale.ROOT));
        }
        catch (Exception e)
        {
            return AdminAiService.TaskMode.PLANNING;
        }
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

    List<String> getAllowedCommandPrefixes()
    {
        return plugin.getConfig().getStringList("admin-ai.actions.allowed-command-prefixes");
    }

    void addAllowedCommandPrefix(String prefix)
    {
        List<String> list = new ArrayList<>(getAllowedCommandPrefixes());
        if (!list.contains(prefix))
        {
            list.add(prefix);
            plugin.getConfig().set("admin-ai.actions.allowed-command-prefixes", list);
            plugin.saveConfig();
        }
    }

    List<String> getAllowedFilePaths()
    {
        return plugin.getConfig().getStringList("admin-ai.actions.allowed-file-paths");
    }

    void addAllowedFilePath(String path)
    {
        List<String> list = new ArrayList<>(getAllowedFilePaths());
        if (!list.contains(path))
        {
            list.add(path);
            plugin.getConfig().set("admin-ai.actions.allowed-file-paths", list);
            plugin.saveConfig();
        }
    }

    List<AiProvider> getProviders()
    {
        return loadProviders("admin-ai.providers", "admin-ai.provider-order");
    }

    private List<AiProvider> loadProviders(String sectionPath, String orderPath)
    {
        List<AiProvider> providers = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList(orderPath))
        {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionPath + "." + name);
            if (section == null || !section.getBoolean("enabled"))
                continue;

            java.util.Map<String, Object> sampling = new java.util.HashMap<>();
            ConfigurationSection samplingSection = section.getConfigurationSection("sampling");
            if (samplingSection != null)
                for (String key : samplingSection.getKeys(false))
                    sampling.put(key, samplingSection.get(key));

            providers.add(new AiProvider(
                    name,
                    section.getString("protocol", "ollama-native"),
                    section.getString("endpoint", ""),
                    section.getString("model", ""),
                    secrets.getProperty(name + ".api-key", ""),
                    section.getInt("timeout-seconds", 600),
                    sampling
            ));
        }
        return providers;
    }
}

}
