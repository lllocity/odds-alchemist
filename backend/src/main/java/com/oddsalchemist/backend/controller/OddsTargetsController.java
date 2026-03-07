package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.scheduler.OddsScrapingScheduler;
import com.oddsalchemist.backend.service.OddsSyncService;
import com.oddsalchemist.backend.service.TargetUrlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 監視対象URLの動的登録・削除を提供するコントローラー。
 * フロントエンドからURLを登録することで、スケジューラーの対象に即時反映される。
 * URLの新規登録時は、次回定期実行を待たずに即時でオッズ取得を非同期実行する。
 */
@RestController
@RequestMapping("/api/odds")
@CrossOrigin(origins = "http://localhost:3000")
public class OddsTargetsController {

    private static final Logger logger = LoggerFactory.getLogger(OddsTargetsController.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TargetUrlStore targetUrlStore;
    private final OddsSyncService oddsSyncService;
    private final ScrapingProperties properties;
    private final OddsScrapingScheduler scheduler;

    public OddsTargetsController(TargetUrlStore targetUrlStore, OddsSyncService oddsSyncService,
                                  ScrapingProperties properties, OddsScrapingScheduler scheduler) {
        this.targetUrlStore = targetUrlStore;
        this.oddsSyncService = oddsSyncService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 登録済みの監視対象URL一覧を返します。
     *
     * @return URL文字列のリスト（JSON配列）
     */
    @GetMapping("/targets")
    public ResponseEntity<List<String>> getTargets() {
        return ResponseEntity.ok(targetUrlStore.getUrls());
    }

    /**
     * 監視対象URLを追加します。
     *
     * @param request {"url": "https://..."}
     * @return 登録成功時は更新後のURL一覧、重複・不正の場合は400
     */
    @PostMapping("/targets")
    public ResponseEntity<?> addTarget(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "URLが指定されていません"));
        }

        logger.info("監視対象URL登録リクエスト: {}", url);
        boolean added = targetUrlStore.addUrl(url);
        if (!added) {
            return ResponseEntity.badRequest().body(Map.of("message", "URLはすでに登録済みです: " + url));
        }

        // 登録直後に初回スクレイピングを非同期で実行（次回定期実行を待たずに即時取得）
        CompletableFuture.runAsync(() -> {
            try {
                int saved = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());
                logger.info("初回スクレイピング完了: URL={}, 保存件数={}", url, saved);
                scheduler.reschedule();
                Instant rescheduled = scheduler.getNextScheduledTime();
                String rescheduledTime = rescheduled != null
                        ? LocalDateTime.ofInstant(rescheduled, ZoneId.systemDefault()).format(TIME_FORMATTER)
                        : "未定";
                logger.info("再スケジュール完了: 次回定期実行予定: {}", rescheduledTime);
            } catch (Exception e) {
                logger.warn("初回スクレイピング失敗: URL={}", url, e);
            }
        });

        return ResponseEntity.ok(Map.of("message", "URLを登録しました", "urls", targetUrlStore.getUrls()));
    }

    /**
     * 監視対象URLを削除します。
     *
     * @param request {"url": "https://..."}
     * @return 削除成功時は更新後のURL一覧、対象不在の場合は400
     */
    @DeleteMapping("/targets")
    public ResponseEntity<?> removeTarget(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "URLが指定されていません"));
        }

        logger.info("監視対象URL削除リクエスト: {}", url);
        boolean removed = targetUrlStore.removeUrl(url);
        if (!removed) {
            return ResponseEntity.badRequest().body(Map.of("message", "URLが見つかりません: " + url));
        }
        return ResponseEntity.ok(Map.of("message", "URLを削除しました", "urls", targetUrlStore.getUrls()));
    }
}
