package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.OddsData;
import com.oddsalchemist.backend.parser.RaceOddsParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

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
        String range = "OddsData!A:H";
        String dummyHtml = "<html>dummy</html>";

        when(scrapingService.fetchHtml(url)).thenReturn(dummyHtml);
        when(parser.parse(dummyHtml)).thenReturn(List.of(
                new OddsData("第1回東京1レース", "1", "キタサンブラック", 2.5, 1.2, 1.5, null)
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
        assertThat(row.get(1)).isEqualTo(url);               // B列: URL
        assertThat(row.get(2)).isEqualTo("第1回東京1レース"); // C列: レース名
        assertThat(row.get(3)).isEqualTo("1");               // D列: 馬番
        assertThat(row.get(4)).isEqualTo("キタサンブラック"); // E列: 馬名

        // 異常検知が呼び出されていること
        verify(anomalyDetector).detect(any());
    }

    @Test
    void fetchAndSaveOdds_パース済みの発走時刻がキャッシュされること() throws Exception {
        String url = "https://example.com/race";
        String range = "OddsData!A:G";
        String dummyHtml = "<html>dummy</html>";
        LocalTime startTime = LocalTime.of(15, 25);

        when(scrapingService.fetchHtml(url)).thenReturn(dummyHtml);
        when(parser.parse(dummyHtml)).thenReturn(List.of(
                new OddsData("第1回東京1レース", "1", "テスト馬", 2.5, 1.2, 1.5, null)
        ));
        when(parser.parseStartTime(dummyHtml)).thenReturn(Optional.of(startTime));

        service.fetchAndSaveOdds(url, range);

        // 発走時刻がキャッシュに保存されていること
        Optional<LocalTime> cached = service.getCachedStartTime(url);
        assertThat(cached).isPresent();
        assertThat(cached.get()).isEqualTo(startTime);
    }

    @Test
    void getCachedStartTime_スクレイピング前はemptyを返すこと() {
        Optional<LocalTime> result = service.getCachedStartTime("https://example.com/race/unknown");

        assertThat(result).isEmpty();
    }
}