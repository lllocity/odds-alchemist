package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.GoogleSheetsService;
import com.oddsalchemist.backend.service.OddsSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/odds")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://192.168.*:*"})
public class OddsController {

    private static final Logger logger = LoggerFactory.getLogger(OddsController.class);
    private final OddsSyncService oddsSyncService;
    private final ScrapingProperties properties;
    private final GoogleSheetsService googleSheetsService;

    public OddsController(OddsSyncService oddsSyncService, ScrapingProperties properties,
                          GoogleSheetsService googleSheetsService) {
        this.oddsSyncService = oddsSyncService;
        this.properties = properties;
        this.googleSheetsService = googleSheetsService;
    }

    @DeleteMapping("/sheets")
    public ResponseEntity<?> clearSheet(@RequestParam String sheet) {
        String range = switch (sheet) {
            case "OddsData" -> "OddsData!A2:H";
            case "Alerts"   -> "Alerts!A2:G";
            default -> null;
        };
        if (range == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "不正なシート名: " + sheet));
        }
        try {
            googleSheetsService.clearAndWriteData(range, List.of());
            logger.info("シートをクリアしました: sheet={}", sheet);
            return ResponseEntity.ok(Map.of("message", sheet + " のデータをクリアしました"));
        } catch (Exception e) {
            logger.warn("シートクリアに失敗しました: sheet={}", sheet, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "クリアに失敗しました: " + e.getMessage()));
        }
    }

    @PostMapping("/fetch")
    public ResponseEntity<?> fetchOdds(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "URLが指定されていません"));
        }

        try {
            logger.info("フロントエンドからリクエストを受信。対象URL: {}", url);
            int savedCount = oddsSyncService.fetchAndSaveOdds(url, properties.sheetRange());

            // 取得件数が0件の場合はエラーとして扱う
            if (savedCount == 0) {
                return ResponseEntity.badRequest().body(Map.of("message", "オッズデータを抽出できませんでした。対象ページの構造、またはURLを確認してください。"));
            }

            return ResponseEntity.ok(Map.of("message", savedCount + "件のオッズデータを保存しました。"));
        } catch (Exception e) {
            logger.error("処理中にエラーが発生しました", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "エラーが発生しました: " + e.getMessage()));
        }
    }
}
