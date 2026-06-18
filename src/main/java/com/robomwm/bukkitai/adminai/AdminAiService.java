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
import java.util.concurrent.locks.ReentrantLock;

class AdminAiService implements Listener
{
    public enum TaskMode {
        PLANNING,
        EXECUTION,
        DIAGNOSTIC
    }

    private final BukkitAI plugin;
    private final AdminAiConfig config;
    private final OpenAiCompatibleClient client;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ReentrantLock taskLock = new ReentrantLock();
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Boolean>> approvalFuture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<?>> currentTask = new AtomicReference<>();
    private final AtomicReference<Path> currentDiagnosticFile = new AtomicReference<>();
    private final java.util.Map<String, List<String>> currentDiagnosticSections = new java.util.LinkedHashMap<>();
    private String currentDiagnosticHeader = "";
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
                run(plugin.getServer().getConsoleSender(), "Perform a routine maintenance check. Look for errors in the logs and ensure all plugins are up to date.", true, config.getProactiveMode());
        }, 100L); // 5 seconds after startup

        // Recurring proactive maintenance
        long intervalTicks = config.getInt("admin-ai.proactive-interval-minutes") * 60L * 20L;
        if (intervalTicks > 0)
        {
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (config.isEnabled() && !isRunning())
                    run(plugin.getServer().getConsoleSender(), "Perform a routine maintenance check. Look for errors in the logs and ensure all plugins are up to date.", true, config.getProactiveMode());
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
        run(plugin.getServer().getConsoleSender(), prompt, true, config.getProactiveMode());
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
        run(sender, prompt, false, TaskMode.PLANNING);
    }

    void run(CommandSender sender, String prompt, boolean proactive, TaskMode mode)
    {
        if (!config.isEnabled())
        {
            if (!proactive)
                sender.sendMessage(ChatColor.RED + "Admin AI is disabled. Use /adminai on first.");
            return;
        }

        if (!taskLock.tryLock())
        {
            if (!proactive)
                sender.sendMessage(ChatColor.RED + "Admin AI task already running.");
            return;
        }

        if (config.getProviders().isEmpty())
        {
            taskLock.unlock();
            if (!proactive)
                sender.sendMessage(ChatColor.RED + "No enabled admin-ai providers in config.yml.");
            return;
        }

        try
        {
            currentTask.set(CompletableFuture.runAsync(() -> {
                try
                {
                    runAgent(sender, prompt, proactive, mode);
                }
                finally
                {
                    taskLock.unlock();
                }
            }, executor));
            if (!proactive)
                sender.sendMessage(ChatColor.GREEN + "Admin AI task started.");
        }
        catch (Exception e)
        {
            taskLock.unlock();
            throw e;
        }
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
        return taskLock.isLocked();
    }

    private void runAgent(CommandSender sender, String userPrompt, boolean proactive, TaskMode mode)
    {
        try
        {
            boolean autonomous = proactive && !config.isInteractive();
            String plan = runSession(sender, userPrompt, proactive, mode);
            
            if (plan != null && autonomous && !plan.isBlank() && mode == TaskMode.PLANNING)
            {
                String execPrompt = "Execute the following proposed plan:\n" + plan;
                runSession(sender, execPrompt, proactive, TaskMode.EXECUTION);
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

    private String runSession(CommandSender sender, String userPrompt, boolean proactive, TaskMode mode) throws Exception
    {
        wasCompacted = false;
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage("system", systemPrompt(mode)));
        messages.add(new AiMessage("user", buildInitialPrompt(userPrompt)));

        if (mode == TaskMode.DIAGNOSTIC)
            messages.add(new AiMessage("user", startDiagnosticFile(userPrompt)));
        else
            currentDiagnosticFile.set(null);

        if (mode == TaskMode.PLANNING || mode == TaskMode.DIAGNOSTIC)
            bootstrapWithInitialLogRead(messages, mode);

        return runAgentWithMessages(sender, messages, proactive, mode);
    }

    private String runAgentWithMessages(CommandSender sender, List<AiMessage> messages, boolean proactive, TaskMode mode) throws Exception
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
                    result = executeAction(action, messages, proactive, mode);
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
                    String finishMessage = finishMessage(action);
                    String plan = finishPlan(action);
                    if (mode == TaskMode.PLANNING) logAiNotes(finishMessage, plan);
                    
                    String finalMessage = ChatColor.GREEN + "Admin AI done: " + finishMessage;
                    Path diagFile = currentDiagnosticFile.get();
                    if (mode == TaskMode.DIAGNOSTIC && diagFile != null)
                        finalMessage += "\n" + ChatColor.YELLOW + "Diagnostic report created: " + diagFile.getFileName();
                        
                    send(sender, finalMessage);
                    return plan;
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
                    return runAgentWithMessages(sender, messages, proactive, mode);
                }
            }
            send(sender, ChatColor.RED + "Admin AI failed: " + errorMsg);
        }
        return null;
    }

    private boolean compactMessages(List<AiMessage> messages)
    {
        if (messages.size() <= 6) return false;

        List<AiMessage> rawKept = new ArrayList<>();
        
        // Always keep original system message and initial task
        AiMessage first = messages.get(0);
        String notice = "\n\nNOTICE: Previous conversation history has been compacted. " +
                "Older messages were removed. You can re-read specific parts of files using 'startLine' and 'endLine' if needed.";
        if (!first.content().contains("NOTICE: Previous conversation history has been compacted")) {
            rawKept.add(new AiMessage(first.role(), first.content() + notice));
        } else {
            rawKept.add(first);
        }
        
        rawKept.add(messages.get(1)); // initial task
        wasCompacted = true;

        // Keep the last 4 messages (2 interactions)
        int keepStart = Math.max(2, messages.size() - 4);

        // Preserve the trail of what the agent has already tried by harvesting the
        // terse `progress` notes from the assistant turns we're about to drop.
        StringBuilder breadcrumbs = new StringBuilder();
        for (int i = 2; i < keepStart; i++)
        {
            AiMessage msg = messages.get(i);
            if (!"assistant".equals(msg.role()))
                continue;
            try
            {
                AiAction dropped = gson.fromJson(msg.content(), AiAction.class);
                if (dropped != null && dropped.progress != null && !dropped.progress.isBlank())
                    breadcrumbs.append("- ").append(dropped.progress.trim()).append("\n");
            }
            catch (Exception ignored) {}
        }
        if (breadcrumbs.length() > 0)
            rawKept.add(new AiMessage("user", "PROGRESS SO FAR (summary of compacted earlier steps):\n"
                    + truncate(breadcrumbs.toString(), 2000)));

        for (int i = keepStart; i < messages.size(); i++)
        {
            AiMessage msg = messages.get(i);
            if (msg.content().length() > 2000)
            {
                rawKept.add(new AiMessage(msg.role(), "[Result truncated to fit context window.]\n" + truncateMiddle(msg.content(), 2000)));
            }
            else
            {
                rawKept.add(msg);
            }
        }

        // Merge consecutive messages with the same role (strict backends requirement)
        List<AiMessage> merged = new ArrayList<>();
        if (!rawKept.isEmpty())
        {
            AiMessage current = rawKept.get(0);
            for (int i = 1; i < rawKept.size(); i++)
            {
                AiMessage next = rawKept.get(i);
                if (current.role().equals(next.role()))
                {
                    current = new AiMessage(current.role(), current.content() + "\n\n" + next.content());
                }
                else
                {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
        }

        if (merged.size() < messages.size())
        {
            messages.clear();
            messages.addAll(merged);
            return true;
        }

        return false;
    }

    private void bootstrapWithInitialLogRead(List<AiMessage> messages, TaskMode mode) throws IOException, InterruptedException
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
            messages.add(new AiMessage("user", executeAction(initialAction, messages, true, mode)));
        }
        catch (Throwable e)
        {
            messages.add(new AiMessage("user", "RESULT error\nBootstrap failed: " + e.getMessage()));
        }
    }

    private void logAiNotes(String message, String proposedPlan) {
        if ((message == null || message.isBlank()) && (proposedPlan == null || proposedPlan.isBlank()))
            return;
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "ai-notes.md");
            if (!file.exists()) {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            }
            
            StringBuilder formatted = new StringBuilder();
            formatted.append("### ").append(Instant.now()).append(" UTC\n");
            formatted.append(nullToEmpty(message).trim()).append("\n");
            if (proposedPlan != null && !proposedPlan.isBlank()) {
                formatted.append("\n**Proposed Actions:**\n");
                formatted.append(proposedPlan.trim()).append("\n");
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

    private String executeAction(AiAction action, List<AiMessage> messages, boolean proactive, TaskMode mode) throws IOException, InterruptedException
    {
        ensureStillEnabled();
        String actionName = action.action == null ? "" : action.action.toLowerCase(Locale.ROOT);

        if ((mode == TaskMode.PLANNING || mode == TaskMode.DIAGNOSTIC) && ("write_file".equals(actionName) || "append_file".equals(actionName) || "replace_in_file".equals(actionName) || "run_command".equals(actionName)))
        {
            return "RESULT error\nYou are in " + mode + " MODE. Gather info and use `finish` to propose a plan.";
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
                ApprovalAiClient.ApprovalResult result = approvalClient.evaluate(action, messages, proactive, mode);
                broadcastApprovalResult(actionName, action, proactive, result);
                if (!result.approved())
                    return "RESULT error\nAction denied by approval AI: " + result.reason();
                recordApproval("ai", actionName, action, proactive, result.reason());
            }
            else if ("ai-fallback-human".equals(approvalMode))
            {
                ApprovalAiClient.ApprovalResult result = approvalClient.evaluate(action, messages, proactive, mode);
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
                ApprovalAiClient.ApprovalResult result = approvalClient.evaluate(action, messages, proactive, mode);
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
            case "add_to_diagnostic" -> "RESULT add_to_diagnostic\n" + recordDiagnostic(action, null);
            case "add_file_to_diagnostic" -> "RESULT add_file_to_diagnostic\n" + recordDiagnostic(action, false);
            case "add_log_to_diagnostic" -> "RESULT add_log_to_diagnostic\n" + recordDiagnostic(action, true);
            case "grep_to_diagnostic" -> "RESULT grep_to_diagnostic\n" + grepToDiagnostic(action);
            case "finish" -> "RESULT finish accepted";
            case "invalid_json" -> "RESULT error\n" + action.message;
            default -> "RESULT error\nUnknown action. Use read_log, read_file, list_dir, grep, find, write_file, append_file, replace_in_file, delete_file, bash, run_command, add_to_diagnostic, add_file_to_diagnostic, add_log_to_diagnostic, grep_to_diagnostic, finish.";
        };
    }

    private String grepToDiagnostic(AiAction action) throws IOException
    {
        if (action.path == null || action.issue == null || action.issue.isBlank() || action.content == null || action.content.isBlank())
            return "Error: path, issue description, and content (pattern) are required.";

        String grepResult = grep(action.path, action.content);
        if (grepResult.startsWith("Path does not exist") || grepResult.startsWith("Error"))
             return "Error: " + grepResult;

        Path file = currentDiagnosticFile.get();
        if (file == null)
            file = createDiagnosticFile("Grep diagnostic entry");

        String issue = action.issue.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("### Grep: `").append(action.content).append("` in ").append(action.path);
        sb.append("\n```\n").append(grepResult.replace("```", "'' '")).append("\n```\n");

        currentDiagnosticSections.computeIfAbsent(issue, ignored -> new ArrayList<>()).add(sb.toString());
        writeDiagnosticFile(file);
        return "Added grep results for issue '" + action.issue + "' to " + file;
    }

    private String recordDiagnostic(AiAction action, Boolean logOnlyHint) throws IOException
    {
        if (action.path == null || action.issue == null || action.issue.isBlank())
            return "Error: path and issue description are required.";
        
        boolean isLog = logOnlyHint != null ? logOnlyHint : action.path.toLowerCase(Locale.ROOT).endsWith(".log");
        Path resolved = resolveAllowedPath(action.path, isLog);
        String content = readAllowedFile(action.path, isLog, action.startLine, action.endLine);
        if (content.startsWith("File does not exist") || content.startsWith("Path blocked") || content.startsWith("File is a directory"))
            return "Error recording diagnostic: " + content;

        Path file = currentDiagnosticFile.get();
        if (file == null)
            file = createDiagnosticFile("Manual diagnostic entry");

        String issue = action.issue.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("### File: ").append(resolved);
        if (action.startLine != null || action.endLine != null)
            sb.append(" (lines ").append(action.startLine == null ? 1 : action.startLine)
              .append("-").append(action.endLine == null ? "?" : action.endLine).append(")");
        
        // Protect against nested markdown code blocks breaking formatting
        String safeContent = content.trim().replace("```", "'' '");
        sb.append("\n```\n").append(safeContent).append("\n```\n");

        currentDiagnosticSections.computeIfAbsent(issue, ignored -> new ArrayList<>()).add(sb.toString());
        writeDiagnosticFile(file);
        return "Added diagnostic info for issue '" + action.issue + "' from " + resolved + " to " + file;
    }

    private String startDiagnosticFile(String userPrompt) throws IOException
    {
        Path file = createDiagnosticFile(userPrompt);
        return "DIAGNOSTIC OUTPUT FILE\n" + file + "\nUse add_log_to_diagnostic or add_file_to_diagnostic for every issue/file snippet that should be copy-pasted into an external AI prompt.";
    }

    private Path createDiagnosticFile(String userPrompt) throws IOException
    {
        plugin.getDataFolder().mkdirs();
        String timestamp = Instant.now().toString().replace(":", "-");
        Path file = plugin.getDataFolder().toPath().resolve("ai-diagnostics-" + timestamp + ".md");
        currentDiagnosticHeader = "# Admin AI Diagnostics\n\n"
                + "- Created: " + Instant.now() + " UTC\n"
                + "- Task: " + nullToEmpty(userPrompt).trim().replace("\n", " ") + "\n\n"
                + "Each issue section is intended to be copy-pasted into an external AI prompt.\n";
        currentDiagnosticSections.clear();
        Files.writeString(file, currentDiagnosticHeader, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        currentDiagnosticFile.set(file);
        return file;
    }

    private void writeDiagnosticFile(Path file) throws IOException
    {
        StringBuilder document = new StringBuilder(currentDiagnosticHeader);
        for (java.util.Map.Entry<String, List<String>> entry : currentDiagnosticSections.entrySet())
        {
            document.append("\n## Issue: ").append(entry.getKey()).append("\n\n");
            for (String section : entry.getValue())
                document.append(section).append("\n");
        }
        Files.writeString(file, document.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
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

    private String finishMessage(AiAction action)
    {
        return nullToEmpty(action.message).trim();
    }

    private String finishPlan(AiAction action)
    {
        return nullToEmpty(action.proposedPlan).trim();
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
                                        boolean handled = false;
                                        try {
                                            if (arg.getClass().getName().contains("Component")) {
                                                try {
                                                    Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                                                    Object serializer = serializerClass.getMethod("plainText").invoke(null);
                                                    // Most Components implement Component directly as their first interface.
                                                    // This is a bit brittle but we'll try it, and fallback to toString if it fails.
                                                    String text = (String) serializerClass.getMethod("serialize", arg.getClass().getInterfaces()[0]).invoke(serializer, arg);
                                                    output.append(text).append("\n");
                                                    handled = true;
                                                } catch (Exception ignored) {}
                                            }
                                        } catch (Exception ignored) {}

                                        if (!handled) {
                                            output.append(arg.toString()).append("\n");
                                        }
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
            return "File does not exist: " + resolved + nearestDirectoryListing(resolved);
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

    /**
     * Walks up from a missing path to the nearest existing, policy-allowed directory and
     * returns its contents. Lets the AI self-correct a wrong path in one turn instead of
     * blindly guessing filenames (which previously caused read_file retry loops).
     */
    private String nearestDirectoryListing(Path missing)
    {
        Path dir = missing.getParent();
        while (dir != null && !Files.isDirectory(dir))
            dir = dir.getParent();
        if (dir == null)
            return "";

        Path normalized = dir.toAbsolutePath().normalize();
        boolean allowed = false;
        for (java.io.File root : config.getSourceRoots())
            if (normalized.startsWith(root.toPath().toAbsolutePath().normalize()))
            {
                allowed = true;
                break;
            }
        if (!allowed)
            return "";

        try (java.util.stream.Stream<Path> stream = Files.list(dir))
        {
            String listing = stream
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (listing.isEmpty())
                return "";
            return "\nContents of nearest existing directory (" + normalized + "):\n" + truncate(listing, 4000);
        }
        catch (IOException e)
        {
            return "";
        }
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
        
        // Strip markdown code blocks if present
        if (json.contains("```"))
        {
            int firstFence = json.indexOf("```");
            int firstNewline = json.indexOf('\n', firstFence);
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline)
                json = json.substring(firstNewline + 1, lastFence).trim();
            else if (firstFence >= 0 && lastFence > firstFence)
                json = json.substring(firstFence + 3, lastFence).trim();
        }

        // Heal truncated JSON (missing closing braces)
        if (json.startsWith("{") && !json.endsWith("}"))
        {
            int openBraces = 0;
            int closeBraces = 0;
            for (char c : json.toCharArray())
            {
                if (c == '{') openBraces++;
                else if (c == '}') closeBraces++;
            }
            if (openBraces > closeBraces)
            {
                StringBuilder sb = new StringBuilder(json);
                for (int i = 0; i < openBraces - closeBraces; i++)
                    sb.append("}");
                json = sb.toString();
            }
        }
        
        try
        {
            return parseWithGson(json);
        }
        catch (Exception e)
        {
            // Try to extract JSON if it was surrounded by prose
            int firstBrace = response.indexOf('{');
            int lastBrace = response.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace)
            {
                try
                {
                    return parseWithGson(response.substring(firstBrace, lastBrace + 1));
                }
                catch (Exception ignored) {}
            }

            AiAction action = new AiAction();
            action.action = "invalid_json";
            action.message = "Your response was not valid JSON. Please provide ONLY the JSON object. Error: " + e.getMessage();
            return action;
        }
    }

    private AiAction parseWithGson(String json)
    {
        com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);
        if (!element.isJsonObject())
            throw new com.google.gson.JsonSyntaxException("Not a JSON object");

        com.google.gson.JsonObject obj = element.getAsJsonObject();
        
        // Handle proposedPlan as array if AI sends it that way
        if (obj.has("proposedPlan") && obj.get("proposedPlan").isJsonArray())
        {
            com.google.gson.JsonArray array = obj.getAsJsonArray("proposedPlan");
            StringBuilder sb = new StringBuilder();
            for (com.google.gson.JsonElement item : array)
            {
                if (item.isJsonPrimitive())
                {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("- ").append(item.getAsString());
                }
            }
            obj.addProperty("proposedPlan", sb.toString());
        }

        return gson.fromJson(obj, AiAction.class);
    }

    private String buildInitialPrompt(String userPrompt)
    {
        return "Task requested at " + Instant.now() + " UTC:\n" + userPrompt + "\n\nConfigured log files:\n"
                + String.join("\n", config.getStringList("admin-ai.actions.log-files"));
    }

    private String systemPrompt(TaskMode mode)
    {
        boolean autonomous = !config.isInteractive();
        String modeText = switch (mode) {
            case PLANNING -> """
                You are in PLANNING MODE. Investigate issues and propose a plan.
                When you understand the issues, use `finish` with your notes in `message` and action items in `proposedPlan`.
                """;
            case EXECUTION -> autonomous ? """
                You are in EXECUTION MODE (Autonomous). You must execute the provided plan to fix issues.
                - Apply fixes directly using write_file or run_command.
                - After executing the plan, use `finish`.
                """ : """
                If a task is too big and there are no suitable forks, propose action items in your `finish` action's `proposedPlan` field.
                """;
            case DIAGNOSTIC -> """
                You are in DIAGNOSTIC MODE. Your goal is to gather relevant log snippets and configuration files for issues.
                - Use `add_log_to_diagnostic` to record relevant logs. It automatically tails the log if startLine/endLine are omitted.
                - Use `add_file_to_diagnostic` to record relevant config file sections or full files.
                - Use `grep_to_diagnostic` to search across logs or configurations and record the matching lines directly to the diagnostic report.
                - Use the same `issue` value for all snippets that belong to the same problem; the plugin groups them under one issue entry.
                - Prefer exact line ranges for logs and focused config sections, but include entire config files when the whole file is relevant.
                - The diagnostic file you build is intended to be shared with an external AI for troubleshooting.
                - When you have gathered all relevant info for the requested task, use `finish`.
                """;
        };

        return """
                You are an autonomous Minecraft server admin maintenance agent running inside a Bukkit plugin.
                The server is experimental, testing lots of plugins (minigames, admin tools, etc.).
                Your job: inspect logs and configurations, help maintain the server, and seek out new forks of plugins if existing ones are broken.
                
                """ + modeText + """
                - Investigate logs and plugin configurations (e.g. `plugins/PluginName/config.yml`).
                - Live server environment. Source code (.java) and git repos are NOT available.
                - Use Minecraft command `/update` to update all plugins at once; do not use git/maven.
                - Only use `finish` when you have completed all fixes or exhausted what you can do.
                - If a fix is too risky/complex, note it in `proposedPlan` for human review.
                """ + """

                You must respond with exactly one JSON object and no prose.
                Valid actions:
                {"action":"read_log","path":"path/to/log","startLine":1,"endLine":100}
                {"action":"read_file","path":"path/to/file","startLine":50,"endLine":150}
                {"action":"list_dir","path":"path/to/directory"}
                {"action":"grep","path":"path/to/directory_or_file","content":"search string or regex"}
                {"action":"find","path":"path/to/directory","content":"glob pattern"}
                """ + (mode == TaskMode.PLANNING || mode == TaskMode.DIAGNOSTIC ? "" : """
                {"action":"write_file","path":"path/to/file","content":"full new file content"}
                {"action":"append_file","path":"path/to/file","content":"content to append"}
                {"action":"replace_in_file","path":"path/to/file","content":"exact target content to replace","message":"replacement content"}
                {"action":"delete_file","path":"path/to/file"}
                {"action":"run_command","command":"minecraft server command"}
                """) + (mode == TaskMode.DIAGNOSTIC ? """
                {"action":"add_log_to_diagnostic","path":"path/to/file","issue":"Brief Issue Name","startLine":1,"endLine":100}
                {"action":"add_file_to_diagnostic","path":"path/to/file","issue":"Brief Issue Name","startLine":1,"endLine":100}
                {"action":"grep_to_diagnostic","path":"path/to/file_or_dir","issue":"Brief Issue Name","content":"search string or regex"}
                """ : "") + """
                {"action":"bash","command":"allowed shell command"}
                {"action":"finish","message":"summary of work done, followed by notes.", "proposedPlan": "optional: plan items if needed (string or array of strings)"}

                Escape all newlines in JSON string fields as `\\n`.
                On every non-finish action, include a `"progress"` field (1-2 terse sentences).
                """ + (wasCompacted ? """

                Large Files: Use `startLine` and `endLine` to paginate through truncated results.
                """ : "") + """

                Safety:
                - Never request destructive commands.
                """ + (mode == TaskMode.PLANNING || mode == TaskMode.DIAGNOSTIC ? "" : """
                - Use read_file before write_file for configuration files.
                """);
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
