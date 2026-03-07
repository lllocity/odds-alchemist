package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import com.oddsalchemist.backend.service.OddsAnomalyDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 異常検知アラートをフロントエンドに提供するコントローラー。
 * OddsAnomalyDetector が保持する最新のアラート一覧をGETエンドポイントで返す。
 */
@RestController
@RequestMapping("/api/odds")
@CrossOrigin(origins = "http://localhost:3000")
public class OddsAlertController {

    private static final Logger logger = LoggerFactory.getLogger(OddsAlertController.class);
    private final OddsAnomalyDetector oddsAnomalyDetector;

    public OddsAlertController(OddsAnomalyDetector oddsAnomalyDetector) {
        this.oddsAnomalyDetector = oddsAnomalyDetector;
    }

    /**
     * バックエンド起動後に累積された異常検知アラート一覧を返します。
     * スクレイピングがまだ実行されていない場合は空のリストを返します。
     *
     * @return アラートのリスト（JSON配列）
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<AnomalyAlertDto>> getAlerts() {
        logger.debug("アラート一覧の取得リクエストを受信しました");
        List<AnomalyAlertDto> alerts = oddsAnomalyDetector.getLatestAlerts();
        return ResponseEntity.ok(alerts);
    }
}
