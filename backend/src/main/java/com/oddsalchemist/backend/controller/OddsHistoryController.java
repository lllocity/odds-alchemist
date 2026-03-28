package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.dto.AlertHistoryItemDto;
import com.oddsalchemist.backend.dto.HorseDto;
import com.oddsalchemist.backend.dto.OddsHistoryItemDto;
import com.oddsalchemist.backend.service.OddsHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OddsDataシートのオッズ履歴をフロントエンドに提供するコントローラー。
 * グラフ表示用にURL一覧・馬一覧・時系列オッズデータの3エンドポイントを持つ。
 */
@RestController
@RequestMapping("/api/odds/history")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://192.168.*:*"})
public class OddsHistoryController {

    private final OddsHistoryService oddsHistoryService;

    public OddsHistoryController(OddsHistoryService oddsHistoryService) {
        this.oddsHistoryService = oddsHistoryService;
    }

    /**
     * OddsDataシートに存在するレースURLの一覧を返します。
     *
     * @return URLの文字列リスト（昇順、重複なし）
     */
    @GetMapping("/urls")
    public ResponseEntity<List<String>> getUrls() {
        return ResponseEntity.ok(oddsHistoryService.getUrls());
    }

    /**
     * 指定URLのレースに存在する馬の一覧を馬番昇順で返します。
     *
     * @param url 対象レースのURL
     * @return 馬番・馬名のリスト
     */
    @GetMapping("/horses")
    public ResponseEntity<List<HorseDto>> getHorses(@RequestParam String url) {
        return ResponseEntity.ok(oddsHistoryService.getHorses(url));
    }

    /**
     * 指定URLと馬名のオッズ時系列データを取得日時昇順で返します。
     *
     * @param url       対象レースのURL
     * @param horseName 馬名
     * @return オッズ時系列データのリスト
     */
    @GetMapping
    public ResponseEntity<List<OddsHistoryItemDto>> getHistory(
            @RequestParam String url,
            @RequestParam String horseName) {
        return ResponseEntity.ok(oddsHistoryService.getHistory(url, horseName));
    }

    /**
     * 指定URLと馬名のアラート履歴をAlertsシートから検知日時昇順で返します。
     *
     * @param url       対象レースのURL
     * @param horseName 馬名
     * @return アラート履歴のリスト
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertHistoryItemDto>> getAlerts(
            @RequestParam String url,
            @RequestParam String horseName) {
        return ResponseEntity.ok(oddsHistoryService.getAlerts(url, horseName));
    }
}
