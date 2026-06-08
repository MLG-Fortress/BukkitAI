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
        installDefaults();
    }

    void reload()
    {
        plugin.reloadConfig();
        installDefaults();
    }

    private void installDefaults()
    {
        FileConfiguration config = plugin.getConfig();
        config.addDefault("admin-ai.enabled", false);
        config.addDefault("admin-ai.interactive", true);
        config.addDefault("admin-ai.max-iterations", 12);
        config.addDefault("admin-ai.max-command-seconds", 300);
        config.addDefault("admin-ai.max-file-bytes", 65536);
        config.addDefault("admin-ai.log-tail-lines", 200);
        config.addDefault("admin-ai.approval-mode", "human"); // human, ai, ai-fallback-human
        config.addDefault("admin-ai.approval-timeout-minutes", 5);
        config.addDefault("admin-ai.provider-order", List.of("ollama"));

        config.addDefault("admin-ai.providers.ollama.enabled", true);
        config.addDefault("admin-ai.providers.ollama.protocol", "ollama-native");
        config.addDefault("admin-ai.providers.ollama.endpoint", "http://localhost:4000/api/chat");
        config.addDefault("admin-ai.providers.ollama.model", "qwen2.5-coder:latest");
        config.addDefault("admin-ai.providers.ollama.api-key", "ollama");
        config.addDefault("admin-ai.providers.ollama.timeout-seconds", 90);

        // Dedicated approval provider (optional — falls back to regular providers if none enabled)
        config.addDefault("admin-ai.approval-providers.ollama.enabled", false);
        config.addDefault("admin-ai.approval-providers.ollama.protocol", "ollama-native");
        config.addDefault("admin-ai.approval-providers.ollama.endpoint", "http://localhost:4000/api/chat");
        config.addDefault("admin-ai.approval-providers.ollama.model", "qwen2.5-coder:latest");
        config.addDefault("admin-ai.approval-providers.ollama.api-key", "ollama");
        config.addDefault("admin-ai.approval-providers.ollama.timeout-seconds", 30);

        File dataFolder = plugin.getDataFolder().getAbsoluteFile();
        File pluginsFolder = dataFolder.getParentFile();
        File rootFolder = (pluginsFolder != null) ? pluginsFolder.getParentFile() : null;
        String rootDir = (rootFolder != null) ? rootFolder.getPath() : dataFolder.getPath();
        config.addDefault("admin-ai.actions.working-directory", rootDir);
        config.addDefault("admin-ai.actions.source-roots", List.of(rootDir));
        config.addDefault("admin-ai.actions.log-files", List.of("logs/latest.log"));
        config.addDefault("admin-ai.actions.allowed-command-prefixes", List.of(
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
        ));
        config.addDefault("admin-ai.actions.denied-command-contains", List.of(
                " rm ", " rm -", " reset --hard", " checkout --", " clean -fd", " clean -fx", " rebase ", " push --force",
                " --force", " --amend", " chmod ", " chown "
        ));
        config.options().copyDefaults(true);
        plugin.getDataFolder().mkdirs();
        plugin.saveConfig();
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
