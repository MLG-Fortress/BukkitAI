package com.robomwm.bukkitai.adminai;

class AiAction
{
    String action;
    String path;
    String command;
    String content;
    String message;
    String proposedPlan;
    String progress;
    Integer startLine;
    Integer endLine;

    /**
     * Returns a stable string identifying the core operation. Used to detect
     * cross-turn repetition loops (e.g. the AI trying the same failing grep
     * over and over).
     */
    String fingerprint()
    {
        return String.format("%s|%s|%s|%s|%d|%d",
                action, path, command, content, startLine, endLine);
    }
}
