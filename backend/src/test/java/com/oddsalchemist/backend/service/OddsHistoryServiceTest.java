package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.HorseDto;
import com.oddsalchemist.backend.dto.OddsHistoryItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OddsHistoryServiceTest {

    private GoogleSheetsService googleSheetsService;
    private OddsHistoryService service;

    private static final String URL_A = "https://example.com/race/A";
    private static final String URL_B = "https://example.com/race/B";

    /** OddsData!A:H の列: 取得日時, URL, レース名, 馬番, 馬名, 単勝, 複勝下限, 複勝上限 */
    private final List<List<Object>> sampleRows = List.of(
            List.of("2026/03/19 10:00:00", URL_A, "テストレース", "1", "シンザン",  "3.5", "1.5", "2.0"),
            List.of("2026/03/19 10:05:00", URL_A, "テストレース", "1", "シンザン",  "3.3", "1.6", "2.1"),
            List.of("2026/03/19 10:00:00", URL_A, "テストレース", "2", "ハクチカラ", "5.0", "2.0", "3.0"),
            List.of("2026/03/19 10:00:00", URL_B, "別レース",    "1", "タケホープ", "2.0", "1.1", "1.5")
    );

    @BeforeEach
    void setUp() {
        googleSheetsService = mock(GoogleSheetsService.class);
        service = new OddsHistoryService(googleSheetsService);
    }

    // ===== getUrls =====

    @Test
    void getUrls_URLが重複なし昇順で返されること() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(sampleRows);

        List<String> urls = service.getUrls();

        assertThat(urls).containsExactly(URL_A, URL_B);
    }

    @Test
    void getUrls_Sheetsが空の場合は空リストを返すこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(List.of());

        assertThat(service.getUrls()).isEmpty();
    }

    @Test
    void getUrls_Sheets読み込み失敗時は空リストを返してシステムを止めないこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenThrow(new IOException("API失敗"));

        assertThat(service.getUrls()).isEmpty();
    }

    // ===== getHorses =====

    @Test
    void getHorses_指定URLの馬が馬番昇順で返されること() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(sampleRows);

        List<HorseDto> horses = service.getHorses(URL_A);

        assertThat(horses).hasSize(2);
        assertThat(horses.get(0)).isEqualTo(new HorseDto(1, "シンザン"));
        assertThat(horses.get(1)).isEqualTo(new HorseDto(2, "ハクチカラ"));
    }

    @Test
    void getHorses_同じ馬の複数行が重複なく1件で返されること() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(sampleRows);

        // URL_A の「シンザン」は2行あるが1件で返るべき
        List<HorseDto> horses = service.getHorses(URL_A);
        long shinzanCount = horses.stream().filter(h -> "シンザン".equals(h.horseName())).count();

        assertThat(shinzanCount).isEqualTo(1);
    }

    @Test
    void getHorses_存在しないURLの場合は空リストを返すこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(sampleRows);

        assertThat(service.getHorses("https://example.com/race/NONE")).isEmpty();
    }

    @Test
    void getHorses_Sheets読み込み失敗時は空リストを返すこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenThrow(new IOException("API失敗"));

        assertThat(service.getHorses(URL_A)).isEmpty();
    }

    // ===== getHistory =====

    @Test
    void getHistory_指定URLと馬名のデータが時系列昇順で返されること() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(sampleRows);

        List<OddsHistoryItemDto> history = service.getHistory(URL_A, "シンザン");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).detectedAt()).isEqualTo("2026/03/19 10:00:00");
        assertThat(history.get(0).winOdds()).isEqualTo(3.5);
        assertThat(history.get(0).placeOddsMin()).isEqualTo(1.5);
        assertThat(history.get(0).placeOddsMax()).isEqualTo(2.0);
        assertThat(history.get(1).detectedAt()).isEqualTo("2026/03/19 10:05:00");
        assertThat(history.get(1).winOdds()).isEqualTo(3.3);
    }

    @Test
    void getHistory_異なるURLの馬は含まれないこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(sampleRows);

        List<OddsHistoryItemDto> history = service.getHistory(URL_B, "シンザン");

        assertThat(history).isEmpty();
    }

    @Test
    void getHistory_データが存在しない場合は空リストを返すこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenReturn(List.of());

        assertThat(service.getHistory(URL_A, "シンザン")).isEmpty();
    }

    @Test
    void getHistory_Sheets読み込み失敗時は空リストを返すこと() throws Exception {
        when(googleSheetsService.readData("OddsData!A:H")).thenThrow(new IOException("API失敗"));

        assertThat(service.getHistory(URL_A, "シンザン")).isEmpty();
    }
}
