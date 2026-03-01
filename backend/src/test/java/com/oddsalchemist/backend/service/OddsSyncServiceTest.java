package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.OddsData;
import com.oddsalchemist.backend.parser.RaceOddsParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OddsSyncServiceTest {

    private OddsScrapingService scrapingService;
    private RaceOddsParser parser;
    private GoogleSheetsService sheetsService;
    private OddsAnomalyDetector anomalyDetector;
    private OddsSyncService service;

    @BeforeEach
    void setUp() {
        scrapingService = mock(OddsScrapingService.class);
        parser = mock(RaceOddsParser.class);
        sheetsService = mock(GoogleSheetsService.class);
        anomalyDetector = mock(OddsAnomalyDetector.class);
        when(anomalyDetector.detect(any())).thenReturn(List.of());
        service = new OddsSyncService(scrapingService, parser, sheetsService, anomalyDetector);
    }

    @Test
    void fetchAndSaveOdds_正常に連携処理が実行されること() throws Exception {
        String url = "https://example.com/race";
        String range = "シート1!A:F";
        String dummyHtml = "<html>dummy</html>";

        when(scrapingService.fetchHtml(url)).thenReturn(dummyHtml);
        when(parser.parse(dummyHtml)).thenReturn(List.of(
                new OddsData("第1回東京1レース", "1", "キタサンブラック", 2.5, 1.2, 1.5)
        ));

        service.fetchAndSaveOdds(url, range);

        verify(scrapingService).fetchHtml(url);
        verify(parser).parse(dummyHtml);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(sheetsService).appendData(eq(range), captor.capture());

        List<List<Object>> savedValues = captor.getValue();
        assertThat(savedValues).hasSize(1);

        List<Object> row = savedValues.get(0);
        assertThat(row.get(1)).isEqualTo("第1回東京1レース"); // B列: レース名
        assertThat(row.get(2)).isEqualTo("1");               // C列: 馬番
        assertThat(row.get(3)).isEqualTo("キタサンブラック"); // D列: 馬名

        // 異常検知が呼び出されていること
        verify(anomalyDetector).detect(any());
    }
}