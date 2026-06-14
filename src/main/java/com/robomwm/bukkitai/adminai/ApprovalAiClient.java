package com.robomwm.bukkitai.adminai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Evaluates pending destructive actions by querying an AI provider
 * for an approve/deny decision. Designed to replace or supplement
 * human approval in the agent loop.
 */
class ApprovalAiClient
{
    private final OpenAiCompatibleClient client;
    private final AdminAiConfig config;
    private final Logger logger;
    private final Gson gson = new Gson();

    ApprovalAiClient(OpenAiCompatibleClient client, AdminAiConfig config, Logger logger)
    {
        this.client = client;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Evaluates whether an action should be approved.
     *
     * @param action          the pending action
     * @param conversationLog recent conversation context (last few messages)
     * @param proactive       whether this is a proactive (automated) task
     * @return result with approved/denied and reasoning
     */
    ApprovalResult evaluate(AiAction action, List<AiMessage> conversationLog, boolean proactive)
    {
        String actionName = action.action == null ? "unknown" : action.action;
        String prompt = buildApprovalPrompt(action, conversationLog, proactive);

        List<AiMessage> messages = List.of(
                new AiMessage("system", approvalSystemPrompt()),
                new AiMessage("user", prompt)
        );

        List<AiProvider> providers = config.getProviders();

        IOException lastError = null;
        for (AiProvider provider : providers)
        {
            try
            {
                String response = client.complete(provider, messages);
                return parseApprovalResponse(response, actionName);
            }
            catch (IOException e)
            {
                lastError = e;
                logger.warning("Approval AI provider " + provider.name() + " failed: " + e.getMessage());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return new ApprovalResult(false, "Approval interrupted.");
            }
        }

        logger.warning("All approval AI providers failed. Last error: " + (lastError != null ? lastError.getMessage() : "none"));
        return new ApprovalResult(false, "All approval AI providers unavailable.");
    }

    private ApprovalResult parseApprovalResponse(String response, String actionName)
    {
        String trimmed = response.trim();
        // Strip markdown fences if present
        if (trimmed.startsWith("```"))
        {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline)
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
        }

        try
        {
            JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
            String decision = json.has("decision") ? json.get("decision").getAsString().toLowerCase(Locale.ROOT) : "";
            String reason = json.has("reason") ? json.get("reason").getAsString() : "No reason given.";
            boolean approved = "approve".equals(decision) || "approved".equals(decision) || "yes".equals(decision);
            logger.info("Approval AI [" + actionName + "]: " + decision + " — " + reason);
            return new ApprovalResult(approved, reason);
        }
        catch (JsonSyntaxException | IllegalStateException e)
        {
            // Fallback: look for keywords in plain text
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("approve") || lower.startsWith("yes"))
            {
                logger.info("Approval AI [" + actionName + "]: approved (plain text)");
                return new ApprovalResult(true, trimmed);
            }
            logger.info("Approval AI [" + actionName + "]: denied (unparseable or explicit deny) — " + truncate(trimmed, 200));
            return new ApprovalResult(false, "Could not parse approval response; defaulting to deny. Response: " + truncate(trimmed, 200));
        }
    }

    private String buildApprovalPrompt(AiAction action, List<AiMessage> conversationLog, boolean proactive)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Pending Action Review\n\n");
        sb.append("**Task type:** ").append(proactive ? "PROACTIVE (automated)" : "MANUAL (admin-initiated)").append('\n');
        sb.append("**Action:** ").append(action.action).append('\n');

        if ("run_command".equals(action.action) || "bash".equals(action.action))
            sb.append("**Command:** `").append(action.command).append("`\n");
        if ("write_file".equals(action.action))
        {
            sb.append("**Path:** `").append(action.path).append("`\n");
            sb.append("**Content length:** ").append(action.content == null ? 0 : action.content.length()).append(" chars\n");
            sb.append("**Content preview:**\n```\n").append(truncate(action.content, 2000)).append("\n```\n");
        }

        sb.append("\n## Denied command substrings\n");
        for (String denied : config.getStringList("admin-ai.actions.denied-command-contains"))
            sb.append("- `").append(denied).append("`\n");

        sb.append("\n## Allowed source roots\n");
        for (java.io.File root : config.getSourceRoots())
            sb.append("- `").append(root.getPath()).append("`\n");

        // Include last few conversation messages for context
        sb.append("\n## Recent conversation context\n");
        int start = Math.max(0, conversationLog.size() - 6);
        for (int i = start; i < conversationLog.size(); i++)
        {
            AiMessage msg = conversationLog.get(i);
            sb.append("**").append(msg.role()).append(":** ").append(truncate(msg.content(), 500)).append("\n\n");
        }

        return sb.toString();
    }

    private String approvalSystemPrompt()
    {
        return """
                You are a security review AI evaluating pending actions from an autonomous Minecraft maintenance agent.
                Your job: decide if the action is SAFE and APPROPRIATE to execute.

                IMPORTANT CONTEXT:
                You are only consulted for commands that are NOT on the agent's pre-approved safe list. 
                DO NOT reject a command simply because it is unfamiliar or not part of a standard list. Evaluate its actual safety.

                SECURITY GUIDELINES:
                1. READ-ONLY COMMANDS: Commands that read state (ls, cat, grep, find, pwd, etc.) are generally SAFE. Approve them so the agent can gather context, unless they attempt to read sensitive files (like passwords).
                2. STATE-CHANGING COMMANDS: Commands that modify files or system state must be scrutinized. Approve only if they align logically with the agent's goal.
                3. DENIED SUBSTRINGS: NEVER approve commands containing "denied command substrings" (if any are provided in the prompt).
                4. FILE WRITES: Path must be within "allowed source roots". Content must be relevant to the task. No malicious code or credential leaks.
                5. PROACTIVE TASKS: These are automated. Apply higher scrutiny to destructive actions, but ALLOW safe exploratory commands so the agent can investigate.

                Remember: Your goal is to block destructive/dangerous actions, not to block the agent from exploring. Default to DENY only if genuinely risky.

                Respond with exactly one JSON object:
                {"decision": "approve" or "deny", "reason": "brief explanation"}
                """;
    }

    private String truncate(String input, int max)
    {
        if (input == null) return "";
        return input.length() <= max ? input : input.substring(0, max);
    }

    record ApprovalResult(boolean approved, String reason) {}
}
