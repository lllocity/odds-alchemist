package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.service.TargetUrlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 監視対象URLの動的登録・削除を提供するコントローラー。
 * フロントエンドからURLを登録することで、スケジューラーの対象に即時反映される。
 */
@RestController
@RequestMapping("/api/odds")
@CrossOrigin(origins = "http://localhost:3000")
public class OddsTargetsController {

    private static final Logger logger = LoggerFactory.getLogger(OddsTargetsController.class);
    private final TargetUrlStore targetUrlStore;

    public OddsTargetsController(TargetUrlStore targetUrlStore) {
        this.targetUrlStore = targetUrlStore;
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
