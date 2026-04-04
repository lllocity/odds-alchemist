package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.service.GoogleSheetsService;
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
    private final GoogleSheetsService googleSheetsService;

    public OddsController(GoogleSheetsService googleSheetsService) {
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
}
