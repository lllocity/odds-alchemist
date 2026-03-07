package com.oddsalchemist.backend.scheduler;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.OddsSyncService;
import com.oddsalchemist.backend.service.TargetUrlStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 定期的にオッズ情報を取得してスプレッドシートに保存するスケジューラー。
 * レース発走時刻からの残り時間に応じて実行間隔を動的に切り替える:
 * <ul>
 *   <li>朝〜12:00 または発走時刻不明: 30分間隔</li>
 *   <li>12:00〜発走60分超前: 15分間隔</li>
 *   <li>発走60分前〜10分前: 5分間隔（コアタイム）</li>
 *   <li>発走10分前〜直前: 1分間隔</li>
 * </ul>
 */
@Component
public class OddsScrapingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(OddsScrapingScheduler.class);

    static final Duration DELAY_30MIN = Duration.ofMinutes(30);
    static final Duration DELAY_15MIN = Duration.ofMinutes(15);
    static final Duration DELAY_5MIN  = Duration.ofMinutes(5);
    static final Duration DELAY_1MIN  = Duration.ofMinutes(1);

    private final OddsSyncService oddsSyncService;
    private final ScrapingProperties properties;
    private final TargetUrlStore targetUrlStore;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private volatile Instant nextScheduledTime;

    public OddsScrapingScheduler(OddsSyncService oddsSyncService, ScrapingProperties properties,
                                  TargetUrlStore targetUrlStore) {
        this.oddsSyncService = oddsSyncService;
        this.properties = properties;
        this.targetUrlStore = targetUrlStore;
    }

    /**
     * アプリ起動後にスケジューラーを初期化し、初回実行をスケジュールします。
     */
    @PostConstruct
    public void start() {
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("odds-scheduler-");
        taskScheduler.initialize();
        logger.info("スケジューラー初期化完了: 初回実行を{}後にスケジュール", DELAY_30MIN);
        scheduleNext();
    }

    /**
     * アプリ終了時にスケジューラーをシャットダウンします。
     */
    @PreDestroy
    public void stop() {
        taskScheduler.shutdown();
        logger.info("スケジューラーをシャットダウンしました");
    }

    /**
     * 次回実行をスケジュールします。
     * scrapeAllTargets 完了後に呼び出され、動的な間隔で自己連鎖します。
     * debugIntervalMinutes が 0 より大きい場合はその値で固定します。
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private void scheduleNext() {
        Duration delay = properties.debugIntervalMinutes() > 0
                ? Duration.ofMinutes(properties.debugIntervalMinutes())
                : calculateDelay(LocalTime.now());
        nextScheduledTime = Instant.now().plus(delay);
        String nextRunTime = LocalDateTime.ofInstant(nextScheduledTime, ZoneId.systemDefault()).format(TIME_FORMATTER);
        logger.info("次回スクレイピングを{}後にスケジュール（予定時刻: {}）", delay, nextRunTime);
        taskScheduler.schedule(this::runAndReschedule, nextScheduledTime);
    }

    /**
     * 次回スケジュール実行の予定時刻を返します。
     */
    public Instant getNextScheduledTime() {
        return nextScheduledTime;
    }

    /**
     * スクレイピングを実行し、完了後に次回をスケジュールします。
     */
    private void runAndReschedule() {
        try {
            scrapeAllTargets();
        } finally {
            scheduleNext();
        }
    }

    /**
     * 全対象URLに対してスクレイピングを実行します。
     * 失敗してもシステムを止めず、次のURLの処理を継続します。
     */
    public void scrapeAllTargets() {
        int urlCount = targetUrlStore.getUrls().size();
        logger.info("定期スクレイピング開始: 対象URL数={}", urlCount);

        for (String url : targetUrlStore.getUrls()) {
            try {
                int saved = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());
                logger.info("スクレイピング完了: URL={}, 保存件数={}", url, saved);
            } catch (Exception e) {
                logger.error("スクレイピング失敗: URL={}", url, e);
            }
        }

        logger.info("定期スクレイピング全完了: 対象URL数={}", urlCount);
    }

    /**
     * 全対象URLの発走時刻を考慮し、最短の遅延時間を返します。
     * URLが1件もない場合はデフォルトの30分を返します。
     *
     * @param now 現在時刻（テストで時刻を注入するための引数）
     * @return 次回実行までの待機時間
     */
    Duration calculateDelay(LocalTime now) {
        return targetUrlStore.getUrls().stream()
                .map(url -> calculateDelayForUrl(url, now))
                .min(Duration::compareTo)
                .orElse(DELAY_30MIN);
    }

    /**
     * 指定URLの発走時刻キャッシュと現在時刻を比較して次回遅延を算出します。
     *
     * @param url 対象URL
     * @param now 現在時刻
     * @return 遅延時間
     */
    Duration calculateDelayForUrl(String url, LocalTime now) {
        Optional<LocalTime> startTimeOpt = oddsSyncService.getCachedStartTime(url);
        if (startTimeOpt.isEmpty()) {
            // 発走時刻不明（初回スクレイピング前）→ デフォルト30分
            return DELAY_30MIN;
        }

        LocalTime startTime = startTimeOpt.get();
        long minutesUntilStart = ChronoUnit.MINUTES.between(now, startTime);

        if (now.isBefore(LocalTime.NOON)) {
            // 朝〜12:00（プール金・少）
            return DELAY_30MIN;
        } else if (minutesUntilStart < 0) {
            // レース発走後
            return DELAY_30MIN;
        } else if (minutesUntilStart > 60) {
            // 12:00〜60分超前（プール金・中）
            return DELAY_15MIN;
        } else if (minutesUntilStart >= 10) {
            // 60分前〜10分前（プール金・大）
            return DELAY_5MIN;
        } else {
            // 10分前〜直前（プール金・最大）
            return DELAY_1MIN;
        }
    }
}
