package com.oddsalchemist.backend.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;

@Component
public class GoogleSheetsTestRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsTestRunner.class);

    private final Sheets sheetsService;
    private final String spreadsheetId;
    private final OddsScrapingService scrapingService;

    // Configで生成したSheetsインスタンスと、ymlのIDをDI（依存性注入）で受け取る
    public GoogleSheetsTestRunner(
            Sheets sheetsService,
            @Value("${google.sheets.spreadsheet-id}") String spreadsheetId,
            OddsScrapingService scrapingService) {
        this.sheetsService = sheetsService;
        this.spreadsheetId = spreadsheetId;
        this.scrapingService = scrapingService;
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("=== Google Sheets API テスト開始 ===");

        // 1. 書き込むデータ（ダミー）の作成
        // Step 3で想定したヘッダー（取得日時, レース名, 馬番, 馬名, 単勝, 複勝）に合わせる
        List<Object> rowData = Arrays.asList(
                LocalDateTime.now().toString(),
                "テスト通信レース",
                "1",
                "テストディープ",
                "2.5",
                "1.1"
        );
        List<List<Object>> values = Arrays.asList(rowData);
        ValueRange body = new ValueRange().setValues(values);

        // 2. 書き込み先シート名の設定（※スプレッドシートの左下のタブ名に合わせてください）
        String range = "シート1!A:F"; 

        // 3. APIリクエストの実行 (USER_ENTERED: スプレッドシートの画面入力と同じように型を自動判定)
        sheetsService.spreadsheets().values()
                .append(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute();

        logger.info("=== テストデータの書き込みが完了しました ===");

        // スクレイピングの通信テスト
        scrapingService.testFetch("https://db.netkeiba.com/");
    }
}