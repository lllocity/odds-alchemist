package com.oddsalchemist.backend.service;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Service
public class GoogleSheetsService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);
    /** RAW: 文字列をそのまま保存（USER_ENTEREDだとSheetsが日時を独自フォーマットに変換してしまう） */
    private static final String VALUE_INPUT_OPTION = "RAW";
    private final Sheets sheetsService;
    private final String spreadsheetId;

    public GoogleSheetsService(
            Sheets sheetsService,
            @Value("${google.sheets.spreadsheet-id}") String spreadsheetId) {
        this.sheetsService = sheetsService;
        this.spreadsheetId = spreadsheetId;
    }

    /**
     * スプレッドシートの指定レンジのデータを読み込みます。
     * 値が存在しない場合は空リストを返します。
     */
    public List<List<Object>> readData(String range) throws IOException {
        var response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();
        List<List<Object>> values = response.getValues();
        return values != null ? values : Collections.emptyList();
    }

    /**
     * 指定レンジをクリアしてからデータを書き込みます。
     * values が空の場合はクリアのみ行います。
     */
    public void clearAndWriteData(String range, List<List<Object>> values) throws IOException {
        sheetsService.spreadsheets().values()
                .clear(spreadsheetId, range, new com.google.api.services.sheets.v4.model.ClearValuesRequest())
                .execute();
        if (values.isEmpty()) {
            return;
        }
        ValueRange body = new ValueRange().setValues(values);
        sheetsService.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption(VALUE_INPUT_OPTION)
                .execute();
        logger.info("Sheetsへの上書き完了: range={}, 件数={}", range, values.size());
    }

    /**
     * スプレッドシートの指定レンジにデータを追記します。
     */
    public void appendData(String range, List<List<Object>> values) throws IOException {
        ValueRange body = new ValueRange().setValues(values);

        AppendValuesResponse result = sheetsService.spreadsheets().values()
                .append(spreadsheetId, range, body)
                .setValueInputOption(VALUE_INPUT_OPTION)
                .execute();

        if (result != null && result.getUpdates() != null) {
            logger.info("スプレッドシートへの書き込み完了。更新されたセル数: {}",
                    result.getUpdates().getUpdatedCells());
        }
    }
}