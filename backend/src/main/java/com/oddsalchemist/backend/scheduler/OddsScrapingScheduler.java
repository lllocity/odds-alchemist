package com.oddsalchemist.backend.scheduler;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.OddsSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期的にオッズ情報を取得してスプレッドシートに保存するスケジューラー。
 * 実行間隔と対象URLはapplication.yamlのodds.scraping設定から読み込む。
 */
@Component
public class OddsScrapingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(OddsScrapingScheduler.class);

    private final OddsSyncService oddsSyncService;
    private final ScrapingProperties properties;

    public OddsScrapingScheduler(OddsSyncService oddsSyncService, ScrapingProperties properties) {
        this.oddsSyncService = oddsSyncService;
        this.properties = properties;
    }

    /**
     * application.yamlのodds.scraping.cronに従って定期実行します。
     * 各URLに対して順番にスクレイピングを行い、失敗してもシステムを止めません。
     */
    @Scheduled(cron = "${odds.scraping.cron}")
    public void scrapeAllTargets() {
        int urlCount = properties.targetUrls().size();
        logger.info("定期スクレイピング開始: 対象URL数={}", urlCount);

        for (String url : properties.targetUrls()) {
            try {
                int saved = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());
                logger.info("スクレイピング完了: URL={}, 保存件数={}", url, saved);
            } catch (Exception e) {
                logger.error("スクレイピング失敗: URL={}", url, e);
            }
        }

        logger.info("定期スクレイピング全完了: 対象URL数={}", urlCount);
    }
}
