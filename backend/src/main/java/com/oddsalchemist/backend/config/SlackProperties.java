package com.oddsalchemist.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml の slack 設定をバインドするプロパティクラス。
 */
@ConfigurationProperties(prefix = "slack")
public record SlackProperties(String webhookUrl, boolean enabled) {}
