package com.theleoborges.researchagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Map;

public class AppConfig {

    private static AppConfig instance;
    private final Map<String, String> config;

    private AppConfig() throws IOException {
        this.config = Map.of(
                "BRAVE_API_KEY", System.getenv("BRAVE_API_KEY"),
                "OPENAI_API_KEY", System.getenv("OPENAI_API_KEY")
        );
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            try {
                instance = new AppConfig();
            } catch (IOException e) {
                throw new RuntimeException("Failed to load configuration", e);
            }
        }
        return instance;
    }

    public String getBraveApiKey() {
        return config.get("BRAVE_API_KEY");
    }

    public String getOpenAIApiKey() {
        return config.get("OPENAI_API_KEY");
    }
}