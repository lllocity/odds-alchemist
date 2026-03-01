package com.oddsalchemist.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * application.yaml の odds.scraping 設定をバインドするプロパティクラス。
 */
@ConfigurationProperties(prefix = "odds.scraping")
public record ScrapingProperties(
        String cron,
        String sheetRange,
        List<String> targetUrls
) {}
