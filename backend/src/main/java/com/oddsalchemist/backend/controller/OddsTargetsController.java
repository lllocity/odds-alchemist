package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.dto.TargetUrlResponseDto;
import com.oddsalchemist.backend.scheduler.OddsScrapingScheduler;
import com.oddsalchemist.backend.service.OddsHistoryService;
import com.oddsalchemist.backend.service.OddsSyncService;
import com.oddsalchemist.backend.service.TargetUrlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 監視対象URLの動的登録・削除を提供するコントローラー。
 * フロントエンドからURLを登録することで、スケジューラーの対象に即時反映される。
 * URLの新規登録時は、次回定期実行を待たずに即時でオッズ取得を非同期実行する。
 */
@RestController
@RequestMapping("/api/odds")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://192.168.*:*"})
public class OddsTargetsController {

    private static final Logger logger = LoggerFactory.getLogger(OddsTargetsController.class);

    private final TargetUrlStore targetUrlStore;
    private final OddsSyncService oddsSyncService;
    private final OddsScrapingScheduler scheduler;
    private final OddsHistoryService oddsHistoryService;

    public OddsTargetsController(TargetUrlStore targetUrlStore, OddsSyncService oddsSyncService,
                                  OddsScrapingScheduler scheduler, OddsHistoryService oddsHistoryService) {
        this.targetUrlStore = targetUrlStore;
        this.oddsSyncService = oddsSyncService;
        this.scheduler = scheduler;
        this.oddsHistoryService = oddsHistoryService;
    }

    /**
     * 登録済みの監視対象URL一覧をレース名・実行時刻情報付きで返します。
     * レース名は OddsData シートから取得します（初回スクレイピング前は空文字）。
     *
     * @return URLとレース名・実行時刻情報のリスト（JSON配列）
     */
    @GetMapping("/targets")
    public ResponseEntity<List<TargetUrlResponseDto>> getTargets() {
        Map<String, String> raceNames = oddsHistoryService.getUrlToRaceNameMap();
        List<TargetUrlResponseDto> response = targetUrlStore.getUrlInfos().stream()
                .map(info -> new TargetUrlResponseDto(
                        info.url(),
                        raceNames.getOrDefault(info.url(), ""),
                        info.lastExecutionTime(),
                        info.nextScheduledTime()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
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
        scheduler.fetchAndScheduleAsync(url);

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
        scheduler.cancelUrl(url);
        oddsSyncService.clearCachedStartTime(url);
        return ResponseEntity.ok(Map.of("message", "URLを削除しました", "urls", targetUrlStore.getUrls()));
    }
}
