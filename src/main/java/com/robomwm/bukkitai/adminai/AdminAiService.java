package com.robomwm.bukkitai.adminai;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.robomwm.bukkitai.BukkitAI;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.server.ServerExceptionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class AdminAiService implements Listener
{
    private final BukkitAI plugin;
    private final AdminAiConfig config;
    private final OpenAiCompatibleClient client;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Boolean>> approvalFuture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<?>> currentTask = new AtomicReference<>();
    private final ApprovalAiClient approvalClient;
    private final java.util.Queue<String> exceptionQueue = new ConcurrentLinkedQueue<>();
    private boolean wasCompacted = false;

    AdminAiService(BukkitAI plugin)
    {
        this.plugin = plugin;
        this.config = new AdminAiConfig(plugin);
        this.client = new OpenAiCompatibleClient(plugin.getLogger());
        this.approvalClient = new ApprovalAiClient(client, config, plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        scheduleMaintenanceCheck();
    }

    private void scheduleMaintenanceCheck()
    {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (config.isEnabled())
                run(plugin.getServer().getConsoleSender(), "Perform a routine maintenance check. Look for errors in the logs and ensure all plugins are up to date.", true);
        }, 100L); // 5 seconds after startup

        // Recurring proactive maintenance
        long intervalTicks = config.getInt("admin-ai.proactive-interval-minutes") * 60L * 20L;
        if (intervalTicks > 0)
        {
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (config.isEnabled() && !isRunning())
                    run(plugin.getServer().getConsoleSender(), "Perform a routine maintenance check. Look for errors in the logs and ensure all plugins are up to date.", true);
            }, intervalTicks, intervalTicks);
        }

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::processQueuedExceptions, 6000L, 6000L); // Every 5 minutes
    }

    private void processQueuedExceptions()
    {
        if (exceptionQueue.isEmpty() || !config.isEnabled()) return;
        
        if (isRunning())
        {
            exceptionQueue.clear();
            return;
        }

        List<String> uniqueExceptions = new ArrayList<>();
        String ex;
        while ((ex = exceptionQueue.poll()) != null)
        {
            if (!uniqueExceptions.contains(ex))
                uniqueExceptions.add(ex);
        }
        if (uniqueExceptions.isEmpty()) return;

        String prompt = "The server encountered the following exceptions recently. Investigate logs for stacktraces and suggest/apply fixes or propose a plan:\n"
                + String.join("\n", uniqueExceptions);
        run(plugin.getServer().getConsoleSender(), prompt, true);
    }

    @EventHandler
    public void onServerException(ServerExceptionEvent event)
    {
        if (!config.isEnabled()) return;
        String msg = event.getException().getMessage();
        if (msg == null) msg = event.getException().getClass().getSimpleName();
        exceptionQueue.offer(event.getException().getClass().getSimpleName() + ": " + msg);
    }

    String getStatus()
    {
        return (config.isEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled")
                + (config.isInteractive() ? ChatColor.YELLOW + " (interactive)" : ChatColor.GREEN + " (autonomous)")
                + ChatColor.GOLD + ", task=" + (isRunning() ? "running" : "idle")
                + ", providers=" + config.getProviders().size();
    }

    void setEnabled(boolean enabled)
    {
        config.setEnabled(enabled);
        if (!enabled)
            abortCurrentTask();
    }

    void setInteractive(boolean interactive)
    {
        config.setInteractive(interactive);
    }

    void approve(boolean approved)
    {
        CompletableFuture<Boolean> future = approvalFuture.getAndSet(null);
        if (future != null)
            future.complete(approved);
    }

    void reload()
    {
        config.reload();
    }

    void run(CommandSender sender, String prompt)
    {
        run(sender, prompt, false);
    }

    void run(CommandSender sender, String prompt, boolean proactive)
    {
        if (!config.isEnabled())
        {
            if (!proactive)
                sender.sendMessage(ChatColor.RED + "Admin AI is disabled. Use /adminai on first.");
            return;
        }
        if (isRunning())
        {
            if (!proactive)
                sender.sendMessage(ChatColor.RED + "Admin AI task already running.");
            return;
        }
        if (config.getProviders().isEmpty())
        {
            if (!proactive)
                sender.sendMessage(ChatColor.RED + "No enabled admin-ai providers in config.yml.");
            return;
        }

        currentTask.set(CompletableFuture.runAsync(() -> runAgent(sender, prompt, proactive), executor));
        if (!proactive)
            sender.sendMessage(ChatColor.GREEN + "Admin AI task started.");
    }

    void abortCurrentTask()
    {
        Process process = currentProcess.getAndSet(null);
        if (process != null)
            process.destroyForcibly();
        CompletableFuture<Boolean> approval = approvalFuture.getAndSet(null);
        if (approval != null)
            approval.complete(false);
        CompletableFuture<?> task = currentTask.getAndSet(null);
        if (task != null)
            task.cancel(true);
    }

    void shutdown()
    {
        abortCurrentTask();
        executor.shutdownNow();
    }

    private boolean isRunning()
    {
        CompletableFuture<?> task = currentTask.get();
        return task != null && !task.isDone();
    }

    private void runAgent(CommandSender sender, String userPrompt, boolean proactive)
    {
        try
        {
            boolean autonomous = proactive && !config.isInteractive();
            String plan = runSession(sender, userPrompt, proactive, true);
            
            if (plan != null && autonomous && plan.contains("PROPOSED PLAN:"))
            {
                String execPrompt = "Execute the following proposed plan:\n" + plan;
                runSession(sender, execPrompt, proactive, false);
            }
        }
        catch (Exception e)
        {
            send(sender, ChatColor.RED + "Admin AI failed: " + e.getMessage());
        }
        finally
        {
            currentProcess.set(null);
        }
    }

    private String runSession(CommandSender sender, String userPrompt, boolean proactive, boolean isPlanning) throws Exception
    {
        wasCompacted = false;
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage("system", systemPrompt(isPlanning)));
        messages.add(new AiMessage("user", buildInitialPrompt(userPrompt)));

        if (isPlanning)
            bootstrapWithInitialLogRead(messages);

        return runAgentWithMessages(sender, messages, proactive, isPlanning);
    }

    private String runAgentWithMessages(CommandSender sender, List<AiMessage> messages, boolean proactive, boolean isPlanning) throws Exception
    {
        try
        {
            boolean autonomous = proactive && !config.isInteractive();
            int maxIterations = autonomous
                    ? config.getInt("admin-ai.autonomous-max-iterations")
                    : config.getInt("admin-ai.max-iterations");
            int compactThreshold = config.getInt("admin-ai.compact-threshold-messages");

            for (int i = 0; i < maxIterations; i++)
            {
                ensureStillEnabled();

                // Proactive compaction: compact before hitting context limits
                if (compactThreshold > 0 && messages.size() > compactThreshold)
                {
                    plugin.getLogger().info("Proactive compaction at " + messages.size() + " messages.");
                    compactMessages(messages);
                }

                broadcastRequest(messages, proactive);
                String response = completeWithFallback(messages);
                AiAction action = parseAction(response);
                if (action.action.equals("invalid_json"))
                {
                    plugin.getLogger().info("Invalid JSON from provider, requesting reformat in fresh context...");
                    String reformatted = reformatJson(response);
                    if (reformatted != null)
                    {
                        response = reformatted;
                        action = parseAction(response);
                    }
                }

                if (!"finish".equalsIgnoreCase(action.action))
                    broadcastResponse(response, proactive);
                messages.add(new AiMessage("assistant", response));

                String result;
                try
                {
                    result = executeAction(action, messages, proactive, isPlanning);
                }
                catch (Throwable e)
                {
                    result = "RESULT error\nAn unexpected error occurred during execution: " + e.getMessage();
                    plugin.getLogger().warning("Error executing AI action: " + e.getMessage());
                    e.printStackTrace();
                }

                messages.add(new AiMessage("user", result));
                if ("finish".equalsIgnoreCase(action.action))
                {
                    if (isPlanning) logAiNotes(action.message);
                    send(sender, ChatColor.GREEN + "Admin AI done: " + nullToEmpty(action.message));
                    return action.message;
                }
            }
            send(sender, ChatColor.YELLOW + "Admin AI stopped: iteration limit reached.");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            send(sender, ChatColor.RED + "Admin AI aborted.");
        }
        catch (Exception e)
        {
            String errorMsg = e.getMessage();
            if (errorMsg != null && (
                    errorMsg.contains("too large") || 
                    errorMsg.contains("context_length_exceeded") || 
                    errorMsg.contains("maximum context length")))
            {
                send(sender, ChatColor.YELLOW + "Context limit reached, attempting to compact history...");
                if (compactMessages(messages))
                {
                    return runAgentWithMessages(sender, messages, proactive, isPlanning);
                }
            }
            send(sender, ChatColor.RED + "Admin AI failed: " + errorMsg);
        }
        return null;
    }

    private boolean compactMessages(List<AiMessage> messages)
    {
        if (messages.size() <= 6) return false;

        boolean changed = false;
        List<AiMessage> kept = new ArrayList<>();
        
        // Always keep original system message and initial task
        kept.add(messages.get(0)); // system
        kept.add(messages.get(1)); // initial task
        
        String notice = "\n\nNOTICE: Previous conversation history has been compacted. " +
                "Older messages were removed. You can re-read specific parts of files using 'startLine' and 'endLine' if needed.";
        AiMessage first = messages.get(0);
        if (!first.content().contains("NOTICE: Previous conversation history has been compacted")) {
            kept.set(0, new AiMessage(first.role(), first.content() + notice));
        } else {
            kept.set(0, first);
        }
        wasCompacted = true;

        // Keep the last 4 messages (2 interactions)
        int keepStart = Math.max(2, messages.size() - 4);
        
        for (int i = keepStart; i < messages.size(); i++)
        {
            AiMessage msg = messages.get(i);
            if (msg.content().length() > 2000)
            {
                kept.add(new AiMessage(msg.role(), "[Result truncated to fit context window.]\n" + truncateMiddle(msg.content(), 2000)));
            }
            else
            {
                kept.add(msg);
            }
        }

        if (kept.size() < messages.size()) changed = true;

        if (changed)
        {
            messages.clear();
            messages.addAll(kept);
        }

        return changed;
    }

    private void bootstrapWithInitialLogRead(List<AiMessage> messages) throws IOException, InterruptedException
    {
        List<String> logFiles = config.getStringList("admin-ai.actions.log-files");
        if (logFiles.isEmpty())
            return;

        AiAction initialAction = new AiAction();
        initialAction.action = "read_log";
        initialAction.path = logFiles.get(0);

        messages.add(new AiMessage("assistant", gson.toJson(initialAction)));
        try
        {
            messages.add(new AiMessage("user", executeAction(initialAction, messages, true, true)));
        }
        catch (Throwable e)
        {
            messages.add(new AiMessage("user", "RESULT error\nBootstrap failed: " + e.getMessage()));
        }
    }

    private void logAiNotes(String message) {
        if (message == null || message.isBlank())
            return;
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "ai-notes.md");
            if (!file.exists()) {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            }
            
            String[] parts = message.split("PROPOSED PLAN:", 2);
            StringBuilder formatted = new StringBuilder();
            formatted.append("### ").append(Instant.now()).append(" UTC\n");
            formatted.append(parts[0].trim()).append("\n");
            if (parts.length > 1) {
                formatted.append("\n**Proposed Actions:**\n");
                formatted.append(parts[1].trim()).append("\n");
            }
            formatted.append("---\n\n");
            
            Files.writeString(file.toPath(), formatted.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not append to ai-notes.md: " + e.getMessage());
        }
    }

    private String completeWithFallback(List<AiMessage> messages) throws IOException, InterruptedException
    {
        IOException last = null;
        for (AiProvider provider : config.getProviders())
        {
            try
            {
                return client.complete(provider, messages);
            }
            catch (IOException e)
            {
                last = e;
                plugin.getLogger().warning("Admin AI provider " + provider.name() + " failed: " + e.getMessage());
            }
        }
        throw last == null ? new IOException("No enabled providers.") : last;
    }

    private String executeAction(AiAction action, List<AiMessage> messages, boolean proactive, boolean isPlanning) throws IOException, InterruptedException
    {
        ensureStillEnabled();
        String actionName = action.action == null ? "" : action.action.toLowerCase(Locale.ROOT);

        if (isPlanning && ("write_file".equals(actionName) || "append_file".equals(actionName) || "replace_in_file".equals(actionName) || "run_command".equals(actionName)))
        {
            return "RESULT error\nYou are in PLANNING MODE. Gather info and use `finish` to propose a plan.";
        }

        if (isDestructive(actionName))
        {
            String approvalMode = config.getApprovalMode();
            boolean autonomous = proactive && !config.isInteractive();
            boolean needsApproval = config.isInteractive() || (proactive && !autonomous);
            
            boolean autoApproved = false;
            if (("run_command".equals(actionName) || "bash".equals(actionName)) && isCommandPreVetted(action.command))
                autoApproved = true;
            else if (("write_file".equals(actionName) || "append_file".equals(actionName) || "replace_in_file".equals(actionName) || "delete_file".equals(actionName)) && config.getAllowedFilePaths().contains(action.path))
                autoApproved = true;

            if (autoApproved)
            {
                broadcastApprovalResult(actionName, action, proactive, new ApprovalAiClient.ApprovalResult(true, "Previously approved or allowed in config."));
                logApproval("config", actionName, action, proactive, "Previously approved or allowed in config.", "");
            }
            else
            {

            if ("ai".equals(approvalMode))
            {
                ApprovalAiClient.ApprovalResult result = approvalClient.evaluate(action, messages, proactive, isPlanning);
                broadcastApprovalResult(actionName, action, proactive, result);
                if (!result.approved())
                    return "RESULT error\nAction denied by approval AI: " + result.reason();
                recordApproval("ai", actionName, action, proactive, result.reason());
            }
            else if ("ai-fallback-human".equals(approvalMode))
            {
                ApprovalAiClient.ApprovalResult result = approvalClient.evaluate(action, messages, proactive, isPlanning);
                if (result.reason().startsWith("All approval AI providers unavailable"))
                {
                    if (autonomous)
                        return "RESULT error\nAction blocked: AI approval unavailable and running in autonomous mode (no human fallback).";
                    String humanResult = requestHumanApproval(actionName, action, proactive);
                    if (humanResult != null)
                        return humanResult;
                    recordApproval("human", actionName, action, proactive, "Fallback approval after AI unavailable.");
                }
                else
                {
                    broadcastApprovalResult(actionName, action, proactive, result);
                    if (!result.approved())
                        return "RESULT error\nAction denied by approval AI: " + result.reason();
                    recordApproval("ai", actionName, action, proactive, result.reason());
                }
            }
            else if (autonomous)
            {
                // Human approval mode but autonomous: use AI approval as safety net
                ApprovalAiClient.ApprovalResult result = approvalClient.evaluate(action, messages, proactive, isPlanning);
                broadcastApprovalResult(actionName, action, proactive, result);
                if (!result.approved())
                    return "RESULT error\nAction denied by approval AI (autonomous safety): " + result.reason();
                recordApproval("ai-autonomous", actionName, action, proactive, result.reason());
            }
            else if (needsApproval)
            {
                String humanResult = requestHumanApproval(actionName, action, proactive);
                if (humanResult != null)
                    return humanResult;
                recordApproval("human", actionName, action, proactive, "Human approval granted.");
            }
            }
        }

        return switch (actionName)
        {
            case "read_log" -> "RESULT read_log\n" + readAllowedFile(action.path, true, action.startLine, action.endLine);
            case "read_file" -> "RESULT read_file\n" + readAllowedFile(action.path, false, action.startLine, action.endLine);
            case "list_dir" -> "RESULT list_dir\n" + listDirectory(action.path);
            case "grep" -> "RESULT grep\n" + grep(action.path, action.content);
            case "find" -> "RESULT find\n" + find(action.path, action.content);
            case "write_file" -> "RESULT write_file\n" + writeAllowedFile(action.path, action.content, false);
            case "append_file" -> "RESULT append_file\n" + writeAllowedFile(action.path, action.content, true);
            case "replace_in_file" -> "RESULT replace_in_file\n" + replaceInFile(action.path, action.content, action.message);
            case "delete_file" -> "RESULT delete_file\n" + deleteAllowedFile(action.path);
            case "bash" -> "RESULT bash\n" + runBashCommand(action.command);
            case "run_command" -> "RESULT run_command\n" + runMinecraftCommand(action.command);
            case "finish" -> "RESULT finish accepted";
            case "invalid_json" -> "RESULT error\n" + action.message;
            default -> "RESULT error\nUnknown action. Use read_log, read_file, list_dir, grep, find, write_file, append_file, replace_in_file, delete_file, bash, run_command, finish.";
        };
    }

    private String reformatJson(String badJson)
    {
        try
        {
            List<AiMessage> reformatMessages = new ArrayList<>();
            reformatMessages.add(new AiMessage("system", "You are a JSON reformatting tool. " +
                    "The user will provide a malformed JSON object that was intended to be a Bukkit AI action. " +
                    "Your job is to fix the JSON and return ONLY the valid JSON object with no prose."));
            reformatMessages.add(new AiMessage("user", "Please fix this JSON response. " +
                    "It must be exactly one JSON object with fields like 'action', 'message', 'command', etc.\n\n" +
                    "Response to fix:\n" + badJson));
            return completeWithFallback(reformatMessages);
        }
        catch (Exception e)
        {
            plugin.getLogger().warning("Failed to reformat JSON in fresh context: " + e.getMessage());
            return null;
        }
    }

    private String runBashCommand(String command) throws IOException, InterruptedException
    {
        if (command == null || command.isBlank())
            return "No command.";
        if (isCommandExplicitlyDenied(command))
            return "Command blocked by admin-ai denylist: " + command;

        Path outputFile = Files.createTempFile("bukkitai-adminai-", ".log");
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        builder.directory(config.getRootFolder());
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputFile.toFile());
        Process process = builder.start();
        currentProcess.set(process);
        boolean done = process.waitFor(config.getInt("admin-ai.max-command-seconds"), TimeUnit.SECONDS);
        currentProcess.compareAndSet(process, null);
        if (!done)
        {
            process.destroyForcibly();
            Files.deleteIfExists(outputFile);
            return "Command timed out: " + command;
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        Files.deleteIfExists(outputFile);
        return "exit=" + process.exitValue() + "\n" + truncate(output, config.getInt("admin-ai.max-context-tokens"));
    }

    private String runMinecraftCommand(String command)
    {
        if (command == null || command.isBlank())
            return "No command.";
        if (isCommandExplicitlyDenied(command))
            return "Command blocked by admin-ai denylist: " + command;

        final String finalCommand = command.startsWith("/") ? command.substring(1) : command;
        CompletableFuture<String> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            StringBuilder output = new StringBuilder();
            try {
                org.bukkit.command.ConsoleCommandSender console = plugin.getServer().getConsoleSender();
                org.bukkit.command.CommandSender capturingSender = (org.bukkit.command.CommandSender) Proxy.newProxyInstance(
                        org.bukkit.command.CommandSender.class.getClassLoader(),
                        new Class[]{org.bukkit.command.ConsoleCommandSender.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("sendMessage") && args != null && args.length > 0) {
                                for (Object arg : args) {
                                    if (arg instanceof String) {
                                        output.append((String) arg).append("\n");
                                    } else if (arg instanceof String[]) {
                                        for (String s : (String[]) arg) output.append(s).append("\n");
                                    } else if (arg != null) {
                                        // Attempt to handle Adventure Components or other message types via reflection
                                        try {
                                            if (arg.getClass().getName().contains("Component")) {
                                                // Simplified: use a well-known method if it exists, or just toString
                                                // In Paper/Adventure, many components have a plainText() or similar if serialized
                                                // But since we can't easily serialize without more reflection, we'll try to find a "content" or "text" or just use toString
                                                // Most Components in Adventure have a very verbose toString, but it's better than nothing.
                                                // Actually, let's try to use the PlainTextComponentSerializer if we can find it.
                                                try {
                                                    Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                                                    Object serializer = serializerClass.getMethod("plainText").invoke(null);
                                                    String text = (String) serializerClass.getMethod("serialize", arg.getClass().getInterfaces()[0]).invoke(serializer, arg);
                                                    output.append(text).append("\n");
                                                } catch (Exception e) {
                                                    output.append(arg).append("\n");
                                                }
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                            try {
                                return method.invoke(console, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                );
                boolean success = plugin.getServer().dispatchCommand(capturingSender, finalCommand);
                String result = output.toString().trim();
                if (result.isEmpty())
                    result = success ? "Command dispatched successfully (no output)." : "Command dispatch failed.";
                future.complete(truncate(result, config.getInt("admin-ai.max-context-tokens")));
            } catch (Throwable e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String result = "Error dispatching command: " + e.getMessage();
                if (output.length() > 0)
                    result += "\nOutput so far:\n" + output;
                result += "\nStack trace:\n" + sw;
                future.complete(truncate(result, config.getInt("admin-ai.max-context-tokens")));
            }
        });

        try {
            return future.get(config.getInt("admin-ai.max-command-seconds"), TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Failed to dispatch command: " + e.getMessage();
        }
    }

    private boolean isCommandExplicitlyDenied(String command)
    {
        if (command == null) return false;
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        String padded = " " + normalized.toLowerCase(Locale.ROOT) + " ";
        for (String denied : config.getStringList("admin-ai.actions.denied-command-contains"))
            if (padded.contains(denied.toLowerCase(Locale.ROOT)))
                return true;
        return false;
    }

    private boolean isCommandPreVetted(String command)
    {
        if (command == null) return false;
        String normalized = command.startsWith("/") ? command.substring(1) : command;

        List<String> commandTokens = CommandLine.split(normalized);
        for (String prefix : config.getStringList("admin-ai.actions.allowed-command-prefixes"))
        {
            List<String> prefixTokens = CommandLine.split(prefix);
            if (commandTokens.size() < prefixTokens.size())
                continue;
            boolean matches = true;
            for (int i = 0; i < prefixTokens.size(); i++)
                if (!commandTokens.get(i).equals(prefixTokens.get(i)))
                    matches = false;
            if (matches)
                return true;
        }
        return false;
    }

    private String readAllowedFile(String path, boolean logOnly, Integer startLine, Integer endLine) throws IOException
    {
        Path resolved = resolveAllowedPath(path, logOnly);
        if (!Files.exists(resolved))
            return "File does not exist: " + resolved;
        if (Files.isDirectory(resolved))
            return "File is a directory. Use list_dir action instead.";
        int maxBytes = config.getInt("admin-ai.max-file-bytes");

        if (startLine != null || endLine != null)
        {
            int start = startLine == null ? 1 : startLine;
            int end = endLine == null ? Integer.MAX_VALUE : endLine;
            return readLines(resolved, start, end, maxBytes);
        }

        if (logOnly)
        {
            int lines = config.getInt("admin-ai.log-tail-lines");
            Deque<String> tailLines = new ArrayDeque<>(lines + 1);
            try (BufferedReader reader = Files.newBufferedReader(resolved, StandardCharsets.UTF_8))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    tailLines.addLast(line);
                    if (tailLines.size() > lines)
                        tailLines.removeFirst();
                }
            }
            return truncate(String.join("\n", tailLines), maxBytes);
        }

        byte[] bytes = Files.readAllBytes(resolved);
        return truncate(new String(bytes, StandardCharsets.UTF_8), maxBytes);
    }

    private String readLines(Path path, int start, int end, int maxBytes) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            String line;
            int current = 0;
            while ((line = reader.readLine()) != null)
            {
                current++;
                if (current >= start && current <= end)
                {
                    if (sb.length() + line.length() + 1 <= maxBytes)
                    {
                        sb.append(line).append("\n");
                    }
                    else if (!sb.toString().endsWith("... (limit reached) ..."))
                    {
                        sb.append("... (limit reached) ...");
                    }
                }
            }
            total = current;
        }

        return "[Reading " + path.getFileName() + ": lines " + start + "-" + Math.min(end, total) + " of " + total + "]\n" + sb.toString();
    }

    private String writeAllowedFile(String path, String content, boolean append) throws IOException
    {
        if (content == null)
            content = "";
        if (content.getBytes(StandardCharsets.UTF_8).length > config.getInt("admin-ai.max-file-bytes"))
            return "Write blocked: content exceeds max-file-bytes.";
        Path resolved = resolveAllowedPath(path, false);
        Files.createDirectories(resolved.getParent());
        if (append && Files.exists(resolved)) {
            Files.writeString(resolved, content + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } else {
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
        }
        return (append ? "Appended to " : "Wrote ") + resolved;
    }

    private Path resolveAllowedPath(String path, boolean logOnly) throws IOException
    {
        Path raw = Path.of(path);
        if (!raw.isAbsolute())
            raw = config.getRootFolder().toPath().resolve(raw);
        Path resolved = Files.exists(raw) ? raw.toRealPath().normalize() : raw.toAbsolutePath().normalize();

        if (logOnly)
        {
            List<String> logs = config.getStringList("admin-ai.actions.log-files");
            Path rootPath = config.getRootFolder().toPath();
            for (String log : logs)
            {
                Path allowed = Path.of(log);
                if (!allowed.isAbsolute())
                    allowed = rootPath.resolve(allowed);
                allowed = allowed.toAbsolutePath().normalize();
                if (resolved.equals(allowed))
                    return resolved;
            }
        }
        else
        {
            for (java.io.File root : config.getSourceRoots())
            {
                Path allowed = root.toPath().toAbsolutePath().normalize();
                if (resolved.startsWith(allowed))
                    return resolved;
            }
        }
        throw new IOException("Path blocked by admin-ai path policy: " + path);
    }

    private String deleteAllowedFile(String path) throws IOException
    {
        Path resolved = resolveAllowedPath(path, false);
        if (!Files.exists(resolved))
            return "File does not exist: " + resolved;
            
        Path trashPath = resolved.resolveSibling(resolved.getFileName() + ".deleted." + System.currentTimeMillis());
        Files.move(resolved, trashPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        
        Path logFile = plugin.getDataFolder().toPath().resolve("ai-deleted-files.sh");
        String restoreCommand = "mv \"" + trashPath.toAbsolutePath() + "\" \"" + resolved.toAbsolutePath() + "\"\n";
        Files.writeString(logFile, restoreCommand, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        
        return "Safely deleted " + resolved + " (can be restored via " + logFile.getFileName() + ")";
    }

    private String listDirectory(String path) throws IOException
    {
        Path resolved = resolveAllowedPath(path, false);
        if (!Files.exists(resolved))
            return "Directory does not exist: " + resolved;
        if (!Files.isDirectory(resolved))
            return "Not a directory: " + resolved;
            
        try (java.util.stream.Stream<Path> stream = Files.list(resolved)) {
            String result = stream
                .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
            return truncate(result, config.getInt("admin-ai.max-context-tokens"));
        }
    }

    private String grep(String path, String pattern) throws IOException
    {
        if (pattern == null || pattern.isEmpty()) return "Error: pattern is empty.";
        Path resolved = resolveAllowedPath(path, false);
        if (!Files.exists(resolved)) return "Path does not exist: " + resolved;
        
        List<String> results = new ArrayList<>();
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(pattern);
        } catch (Exception e) {
            p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(pattern));
        }

        final java.util.regex.Pattern finalP = p;
        if (Files.isDirectory(resolved)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(resolved)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (finalP.matcher(lines.get(i)).find()) {
                                results.add(resolved.relativize(file) + ":" + (i+1) + ":" + lines.get(i).trim());
                            }
                        }
                    } catch (Exception ignored) {}
                });
            }
        } else {
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (finalP.matcher(lines.get(i)).find()) {
                    results.add((i+1) + ":" + lines.get(i).trim());
                }
            }
        }
        String output = String.join("\n", results);
        if (output.isEmpty()) return "No matches found.";
        return truncate(output, config.getInt("admin-ai.max-context-tokens"));
    }

    private String find(String path, String pattern) throws IOException
    {
        if (pattern == null || pattern.isEmpty()) return "Error: pattern is empty.";
        Path resolved = resolveAllowedPath(path, false);
        if (!Files.exists(resolved)) return "Path does not exist: " + resolved;
        if (!Files.isDirectory(resolved)) return "Not a directory: " + resolved;

        List<String> results = new ArrayList<>();
        java.nio.file.PathMatcher matcher;
        try {
            matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (Exception e) {
            return "Invalid glob pattern: " + e.getMessage();
        }
        
        try (java.util.stream.Stream<Path> stream = Files.walk(resolved)) {
            stream.forEach(file -> {
                if (matcher.matches(file.getFileName())) {
                    results.add(resolved.relativize(file).toString());
                }
            });
        }
        String output = String.join("\n", results);
        if (output.isEmpty()) return "No matches found.";
        return truncate(output, config.getInt("admin-ai.max-context-tokens"));
    }

    private String replaceInFile(String path, String target, String replacement) throws IOException
    {
        if (target == null || target.isEmpty()) return "Error: target content is empty.";
        if (replacement == null) replacement = "";
        Path resolved = resolveAllowedPath(path, false);
        if (!Files.exists(resolved)) return "File does not exist: " + resolved;
        if (Files.isDirectory(resolved)) return "File is a directory.";

        String content = Files.readString(resolved, StandardCharsets.UTF_8);
        if (!content.contains(target)) return "Error: target content not found in file.";
        
        int count = (content.length() - content.replace(target, "").length()) / target.length();
        if (count > 1) return "Error: target content appears " + count + " times. Provide a more specific target to ensure safe replacement.";

        String newContent = content.replace(target, replacement);
        Files.writeString(resolved, newContent, StandardCharsets.UTF_8);
        return "Successfully replaced 1 occurrence in " + resolved.getFileName();
    }

    private AiAction parseAction(String response)
    {
        String json = response.trim();
        if (json.startsWith("```"))
        {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline)
                json = json.substring(firstNewline + 1, lastFence).trim();
        }
        
        try
        {
            return gson.fromJson(json, AiAction.class);
        }
        catch (JsonSyntaxException e)
        {
            // Try to extract JSON if it was surrounded by prose
            int firstBrace = response.indexOf('{');
            int lastBrace = response.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace)
            {
                try
                {
                    return gson.fromJson(response.substring(firstBrace, lastBrace + 1), AiAction.class);
                }
                catch (JsonSyntaxException ignored) {}
            }

            AiAction action = new AiAction();
            action.action = "invalid_json";
            action.message = "Your response was not valid JSON. Please provide ONLY the JSON object. Error: " + e.getMessage();
            return action;
        }
    }

    private String buildInitialPrompt(String userPrompt)
    {
        return "Task requested at " + Instant.now() + " UTC:\n" + userPrompt + "\n\nConfigured log files:\n"
                + String.join("\n", config.getStringList("admin-ai.actions.log-files"));
    }

    private String systemPrompt(boolean isPlanning)
    {
        boolean autonomous = !config.isInteractive();
        String modeText = isPlanning ? """
                You are in PLANNING MODE. Investigate issues and propose a plan.
                When you understand the issues, use `finish` with your notes and "PROPOSED PLAN:" detailing the fix.
                """ : (autonomous ? """
                You are in EXECUTION MODE (Autonomous). You must execute the provided plan to fix issues.
                - Apply fixes directly using write_file or run_command.
                - After executing the plan, use `finish`.
                """ : """
                If a task is too big and there are no suitable forks, propose a plan in your `finish` action's message.
                Use the separator "PROPOSED PLAN:" if you have specific actions to recommend after your summary notes.
                """);

        return """
                You are an autonomous Minecraft server admin maintenance agent running inside a Bukkit plugin.
                The server is experimental, testing lots of plugins (minigames, admin tools, etc.).
                Your job: inspect logs and configurations, help maintain the server, and seek out new forks of plugins if existing ones are broken.
                
                """ + modeText + """
                - Investigate errors by reading logs and plugin configurations (e.g. `plugins/PluginName/config.yml`).
                - You are in a live server environment. Plugin source code (.java files) and git repositories are NOT locally available.
                - Do NOT attempt to read, write, or search for .java files.
                - To update plugins, use the Minecraft command `/update` instead of manual git/maven commands. This command updates all plugins at once.
                - Only use `finish` when you have completed all fixes or exhausted what you can do.
                - If a fix is too risky or complex, note it in your finish message with "PROPOSED PLAN:" for human review.
                """ + """

                You must respond with exactly one JSON object and no prose.
                Valid actions:
                {"action":"read_log","path":"path/to/log","startLine":1,"endLine":100}
                {"action":"read_file","path":"path/to/file","startLine":50,"endLine":150}
                {"action":"list_dir","path":"path/to/directory"}
                {"action":"grep","path":"path/to/directory_or_file","content":"search string or regex"}
                {"action":"find","path":"path/to/directory","content":"glob pattern"}
                """ + (isPlanning ? "" : """
                {"action":"write_file","path":"path/to/file","content":"full new file content"}
                {"action":"append_file","path":"path/to/file","content":"content to append"}
                {"action":"replace_in_file","path":"path/to/file","content":"exact target content to replace","message":"replacement content"}
                {"action":"delete_file","path":"path/to/file"}
                {"action":"run_command","command":"minecraft server command"}
                """) + """
                {"action":"bash","command":"allowed shell command"}
                {"action":"finish","message":"summary of work done, followed by notes and PROPOSED PLAN: action items if needed"}
                """ + (wasCompacted ? """

                Large Files & Context:
                - Results include range metadata like "[Reading filename: lines 50-150 of 1000]".
                - Use `startLine` and `endLine` to paginate through large files or logs.
                - If context is compacted, tool results are truncated. Use range metadata to request specific missing parts.
                """ : "") + """

                Safety & Tools:
                - Use `/update` to pull and build updates for all plugins at once instead of manual git/maven commands.
                - Never request destructive commands.
                """ + (isPlanning ? "" : """
                - Use read_file before write_file for configuration files.
                """) + """
                - Use `read_log` without startLine/endLine to tail the latest logs.
                - Use `bash` with `grep -rn` or `find` to search across logs and configurations efficiently instead of reading entire files.
                """;
    }

    private void ensureStillEnabled() throws InterruptedException
    {
        if (!config.isEnabled() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Admin AI disabled or interrupted.");
    }

    private void send(CommandSender sender, String message)
    {
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    private String requestHumanApproval(String actionName, AiAction action, boolean proactive)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        approvalFuture.set(future);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String prefix = "[Admin AI" + (proactive ? " PROACTIVE" : "") + "] ";
            plugin.getServer().broadcast(ChatColor.GOLD + prefix + "Pending Action: " + actionName, "mlg.admin");
            plugin.getLogger().info(prefix + "Pending Action: " + actionName);

            if ("write_file".equals(actionName) || "delete_file".equals(actionName)) {
                plugin.getServer().broadcast(ChatColor.GRAY + "Path: " + action.path, "mlg.admin");
                plugin.getLogger().info("Path: " + action.path);
            }
            if ("run_command".equals(actionName) || "bash".equals(actionName)) {
                plugin.getServer().broadcast(ChatColor.GRAY + "Command: " + action.command, "mlg.admin");
                plugin.getLogger().info("Command: " + action.command);
            }
            plugin.getServer().broadcast(ChatColor.YELLOW + "Use /adminai approve or /adminai deny", "mlg.admin");
            plugin.getLogger().info("Use /adminai approve or /adminai deny");
        });

        try
        {
            int timeoutMinutes = config.getApprovalTimeoutMinutes();
            if (!future.get(timeoutMinutes, TimeUnit.MINUTES))
                return "RESULT error\nAction denied by administrator.";
        }
        catch (TimeoutException e)
        {
            approvalFuture.compareAndSet(future, null);
            plugin.getLogger().warning("Admin AI approval timed out for: " + actionName);
            return "RESULT error\nApproval timed out after " + config.getApprovalTimeoutMinutes() + " minutes.";
        }
        catch (Exception e)
        {
            return "RESULT error\nApproval process interrupted.";
        }
        return null; // approved
    }

    private void recordApproval(String approvalSource, String actionName, AiAction action, boolean proactive, String reason)
    {
        if ("run_command".equals(actionName) || "bash".equals(actionName))
            config.addAllowedCommandPrefix(action.command);
        else if ("write_file".equals(actionName) || "append_file".equals(actionName) || "replace_in_file".equals(actionName) || "delete_file".equals(actionName))
            config.addAllowedFilePath(action.path);
        logApproval(approvalSource, actionName, action, proactive, reason, "");
    }

    private void logApproval(String approvalSource, String actionName, AiAction action, boolean proactive, String reason, String fingerprint)
    {
        Path logFile = plugin.getDataFolder().toPath().resolve("admin-ai-approvals.log");
        try
        {
            Files.createDirectories(logFile.getParent());
            String details = switch (actionName)
            {
                case "run_command", "bash" -> " command=" + nullToEmpty(action.command);
                case "write_file", "append_file", "replace_in_file" -> " path=" + nullToEmpty(action.path) + " content_sha256=" + sha256(nullToEmpty(action.content));
                case "delete_file" -> " path=" + nullToEmpty(action.path);
                default -> "";
            };
            String line = Instant.now() + " source=" + approvalSource + " proactive=" + proactive + " action=" + actionName
                    + details + (fingerprint.isEmpty() ? "" : " fingerprint=" + fingerprint) + " reason=" + reason.replace('\n', ' ');
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            plugin.getLogger().warning("Could not write approval log: " + e.getMessage());
        }
    }

    private String sha256(String input)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes)
                sb.append(String.format("%02x", b));
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void broadcastRequest(List<AiMessage> messages, boolean proactive)
    {
        if (messages.isEmpty()) return;
        String content = messages.get(messages.size() - 1).content();
        String snippet = truncate(content, 500);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String prefix = "[Admin AI" + (proactive ? " PROACTIVE" : "") + "] ";
            for (String line : snippet.split("\n"))
            {
                plugin.getServer().broadcast(ChatColor.GOLD + prefix + "Request: " + ChatColor.GRAY + line, "mlg.admin");
                plugin.getLogger().info(prefix + "Request: " + line);
            }
        });
    }

    private void broadcastResponse(String response, boolean proactive)
    {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String prefix = "[Admin AI" + (proactive ? " PROACTIVE" : "") + "] ";
            for (String line : response.split("\n"))
            {
                plugin.getServer().broadcast(ChatColor.GOLD + prefix + "Response: " + ChatColor.GRAY + line, "mlg.admin");
                plugin.getLogger().info(prefix + "Response: " + line);
            }
        });
    }

    private void broadcastApprovalResult(String actionName, AiAction action, boolean proactive, ApprovalAiClient.ApprovalResult result)
    {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String status = result.approved() ? ChatColor.GREEN + "APPROVED" : ChatColor.RED + "DENIED";
            plugin.getServer().broadcast(
                    ChatColor.GOLD + "[Admin AI" + (proactive ? " PROACTIVE" : "") + "] "
                            + status + ChatColor.GRAY + " " + actionName
                            + (("run_command".equals(actionName) || "bash".equals(actionName)) ? ": " + action.command : "")
                            + (("write_file".equals(actionName) || "delete_file".equals(actionName) || "replace_in_file".equals(actionName)) ? ": " + action.path : ""),
                    "mlg.admin");
            plugin.getServer().broadcast(ChatColor.GRAY + "Reason: " + result.reason(), "mlg.admin");
        });
    }

    private boolean isDestructive(String actionName)
    {
        return "write_file".equals(actionName) || "append_file".equals(actionName) || "replace_in_file".equals(actionName) || "delete_file".equals(actionName) || "run_command".equals(actionName) || "bash".equals(actionName);
    }

    private String truncate(String input, int max)
    {
        if (input == null || input.length() <= max)
            return input;
        return input.substring(0, max);
    }

    private String truncateMiddle(String input, int max)
    {
        if (input == null || input.length() <= max)
            return input;
        int half = max / 2;
        return input.substring(0, half) + "\n...[middle truncated]...\n" + input.substring(input.length() - half);
    }

    private String nullToEmpty(String input)
    {
        return input == null ? "" : input;
    }
}
