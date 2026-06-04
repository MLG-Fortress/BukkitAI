package com.robomwm.bukkitai.adminai;

record AiProvider(String name, String protocol, String endpoint, String model, String apiKey, int timeoutSeconds) {}
