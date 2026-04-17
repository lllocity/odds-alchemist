package com.oddsalchemist.backend.scheduler;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.OddsSyncService;
import com.oddsalchemist.backend.service.TargetUrlStore;
import com.oddsalchemist.backend.util.SheetsDates;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 定期的にオッズ情報を取得してスプレッドシートに保存するスケジューラー。
 * URLごとに独立したスケジュールを持ち、各レースの発走時刻に応じて実行間隔を動的に切り替える:
 * <ul>
 *   <li>朝〜12:00 または発走時刻不明: 30分間隔</li>
 *   <li>12:00〜発走60分超前: 5分間隔</li>
 *   <li>発走60分前〜直前: 1分間隔</li>
 * </ul>
 */
@Component
public class OddsScrapingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(OddsScrapingScheduler.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    static final Duration DELAY_30MIN = Duration.ofMinutes(30);
    static final Duration DELAY_5MIN  = Duration.ofMinutes(5);
    static final Duration DELAY_1MIN  = Duration.ofMinutes(1);

    private final OddsSyncService oddsSyncService;
    private final ScrapingProperties properties;
    private final TargetUrlStore targetUrlStore;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    /** URLごとの定期スケジュールタスク（キー: URL文字列） */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> taskMap = new ConcurrentHashMap<>();

    public OddsScrapingScheduler(OddsSyncService oddsSyncService, ScrapingProperties properties,
                                  TargetUrlStore targetUrlStore) {
        this.oddsSyncService = oddsSyncService;
        this.properties = properties;
        this.targetUrlStore = targetUrlStore;
    }

    /**
     * アプリ起動後にスケジューラーを初期化します。
     * URLの登録は起動時に空のため、各URLのスケジュールは scheduleUrl() 呼び出し時に開始されます。
     */
    @PostConstruct
    public void start() {
        taskScheduler.setPoolSize(4);
        taskScheduler.setThreadNamePrefix("odds-scheduler-");
        taskScheduler.initialize();
        logger.info("スケジューラー初期化完了");
    }

    /**
     * Spring Context の完全起動後に、Sheets から復元した監視対象URLをスケジュールします。
     * 次回予定時刻が未来であればその時刻にスケジュール（即時 fetch しない）。
     * 次回予定時刻が未設定・過去であれば即時 fetch 後にスケジュールします。
     * URLが0件の場合はログを出力して終了します。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreFromStore() {
        List<String> urls = targetUrlStore.getUrls();
        if (urls.isEmpty()) {
            logger.info("起動時URL復元: 登録済みURLなし");
            return;
        }
        logger.info("起動時URL復元: {}件のURLを処理します", urls.size());
        for (String url : urls) {
            LocalDateTime nextScheduled = targetUrlStore.getNextScheduledTime(url)
                    .flatMap(s -> {
                        try {
                            return Optional.of(LocalDateTime.parse(s, SheetsDates.FORMATTER));
                        } catch (Exception e) {
                            logger.warn("次回予定時刻のパース失敗: URL={}, value={}", url, s);
                            return Optional.empty();
                        }
                    })
                    .orElse(null);

            if (nextScheduled != null && nextScheduled.isAfter(LocalDateTime.now())) {
                Instant scheduledInstant = nextScheduled.atZone(ZoneId.systemDefault()).toInstant();
                logger.info("起動時URL復元: 予定時刻にスケジュール URL={}, 予定={}", url, nextScheduled.format(SheetsDates.FORMATTER));
                ScheduledFuture<?> task = taskScheduler.schedule(() -> scrapeAndReschedule(url), scheduledInstant);
                taskMap.put(url, task);
            } else {
                logger.info("起動時URL復元: 即時フェッチ開始 URL={}", url);
                fetchAndScheduleAsync(url);
            }
        }
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
     * 指定URLの定期スクレイピングをスケジュールします。
     * 既存のスケジュールがあればキャンセルして再スケジュールします。
     * URL登録後の即時fetch完了時に呼び出すことで、発走時刻に応じた動的間隔を即時反映させます。
     *
     * @param url スケジュール対象URL
     */
    public void scheduleUrl(String url) {
        ScheduledFuture<?> existing = taskMap.get(url);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
        Duration delay = properties.debugIntervalMinutes() > 0
                ? Duration.ofMinutes(properties.debugIntervalMinutes())
                : calculateDelayForUrl(url, LocalTime.now());
        Instant nextTime = Instant.now().plus(delay);
        String nextRunTime = LocalDateTime.ofInstant(nextTime, ZoneId.systemDefault()).format(TIME_FORMATTER);
        logger.info("スケジュール登録: URL={}, {}後（予定時刻: {}）", url, delay, nextRunTime);
        ScheduledFuture<?> task = taskScheduler.schedule(() -> scrapeAndReschedule(url), nextTime);
        taskMap.put(url, task);
    }

    /**
     * 指定URLの定期スクレイピングスケジュールをキャンセルします。
     * URL削除時に呼び出します。
     *
     * @param url キャンセル対象URL
     */
    public void cancelUrl(String url) {
        ScheduledFuture<?> task = taskMap.remove(url);
        if (task != null && !task.isDone()) {
            task.cancel(false);
            logger.info("スケジュールキャンセル: URL={}", url);
        }
    }

    /**
     * 指定URLをスクレイピングし、完了後に次回スケジュールを登録します。
     * 発走時刻を過ぎている場合はスクレイピングをスキップし、再スケジュールしません。
     * URLがすでに削除されていた場合も再スケジュールしません。
     * スクレイピング完了後に実行時刻を更新して Sheets へ永続化します。
     */
    void scrapeAndReschedule(String url) {
        Optional<LocalTime> startTimeOpt = oddsSyncService.getCachedStartTime(url);
        if (startTimeOpt.isPresent()) {
            long minutesUntilStart = ChronoUnit.MINUTES.between(LocalTime.now(), startTimeOpt.get());
            if (minutesUntilStart < 0) {
                logger.info("発走済みのためスキップ: URL={}, 発走時刻={}", url, startTimeOpt.get().format(TIME_FORMATTER));
                return;
            }
        }

        try {
            int saved = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());
            logger.info("定期スクレイピング完了: URL={}, 保存件数={}", url, saved);
        } catch (Exception e) {
            logger.error("定期スクレイピング失敗: URL={}", url, e);
        } finally {
            if (targetUrlStore.containsUrl(url)) {
                scheduleUrl(url);
                updateAndPersistExecutionTimes(url);
            }
        }
    }

    /**
     * 指定URLの最終実行時刻と次回予定時刻を計算してインメモリとSheetsへ反映します。
     * スクレイピング完了直後（定期実行・初回登録どちらも）に呼び出します。
     */
    public void updateAndPersistExecutionTimes(String url) {
        LocalDateTime now = LocalDateTime.now();
        String lastExecution = now.format(SheetsDates.FORMATTER);
        Duration nextDelay = properties.debugIntervalMinutes() > 0
                ? Duration.ofMinutes(properties.debugIntervalMinutes())
                : calculateDelayForUrl(url, now.toLocalTime());
        String nextScheduled = now.plus(nextDelay).format(SheetsDates.FORMATTER);
        targetUrlStore.updateExecutionTimes(url, lastExecution, nextScheduled);
        targetUrlStore.persistToSheet();
    }

    /**
     * 指定URLのスクレイピングを非同期で即時実行し、完了後にスケジュールと実行時刻を更新します。
     * URL新規登録時・起動時復元の即時フェッチ時に呼び出します。
     */
    public void fetchAndScheduleAsync(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                int saved = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());
                logger.info("初回スクレイピング完了: URL={}, 保存件数={}", url, saved);
                scheduleUrl(url);
                updateAndPersistExecutionTimes(url);
            } catch (Exception e) {
                logger.warn("初回スクレイピング失敗: URL={}", url, e);
            }
        });
    }

    /**
     * 全対象URLに対してスクレイピングを実行します。
     * 失敗してもシステムを止めず、次のURLの処理を継続します。
     */
    public void scrapeAllTargets() {
        List<String> urls = targetUrlStore.getUrls();
        logger.info("スクレイピング開始: 対象URL数={}", urls.size());

        for (String url : urls) {
            try {
                int saved = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());
                logger.info("スクレイピング完了: URL={}, 保存件数={}", url, saved);
            } catch (Exception e) {
                logger.error("スクレイピング失敗: URL={}", url, e);
            }
        }

        logger.info("スクレイピング全完了: 対象URL数={}", urls.size());
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
            return DELAY_5MIN;
        } else {
            // 60分前〜直前（プール金・大/最大）
            return DELAY_1MIN;
        }
    }
}
