package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.dto.AlertHistoryItemDto;
import com.oddsalchemist.backend.dto.HorseDto;
import com.oddsalchemist.backend.dto.OddsHistoryItemDto;
import com.oddsalchemist.backend.service.OddsHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OddsHistoryControllerTest {

    private OddsHistoryService oddsHistoryService;
    private OddsHistoryController controller;

    @BeforeEach
    void setUp() {
        oddsHistoryService = mock(OddsHistoryService.class);
        controller = new OddsHistoryController(oddsHistoryService);
    }

    // ===== getUrls =====

    @Test
    void getUrls_URLリストが200で返されること() {
        List<String> expected = List.of("https://example.com/race/A", "https://example.com/race/B");
        when(oddsHistoryService.getUrls()).thenReturn(expected);

        ResponseEntity<List<String>> response = controller.getUrls();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void getUrls_データがない場合は空リストで200を返すこと() {
        when(oddsHistoryService.getUrls()).thenReturn(List.of());

        ResponseEntity<List<String>> response = controller.getUrls();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ===== getHorses =====

    @Test
    void getHorses_馬リストが200で返されること() {
        String url = "https://example.com/race/A";
        List<HorseDto> expected = List.of(new HorseDto(1, "シンザン"), new HorseDto(2, "ハクチカラ"));
        when(oddsHistoryService.getHorses(url)).thenReturn(expected);

        ResponseEntity<List<HorseDto>> response = controller.getHorses(url);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void getHorses_対象URLにデータがない場合は空リストで200を返すこと() {
        String url = "https://example.com/race/NONE";
        when(oddsHistoryService.getHorses(url)).thenReturn(List.of());

        ResponseEntity<List<HorseDto>> response = controller.getHorses(url);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ===== getHistory =====

    @Test
    void getHistory_時系列データが200で返されること() {
        String url = "https://example.com/race/A";
        String horseName = "シンザン";
        List<OddsHistoryItemDto> expected = List.of(
                new OddsHistoryItemDto("2026/03/19 10:00:00", 3.5, 1.5, 2.0),
                new OddsHistoryItemDto("2026/03/19 10:05:00", 3.3, 1.6, 2.1)
        );
        when(oddsHistoryService.getHistory(url, horseName)).thenReturn(expected);

        ResponseEntity<List<OddsHistoryItemDto>> response = controller.getHistory(url, horseName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).winOdds()).isEqualTo(3.5);
    }

    @Test
    void getHistory_データが存在しない場合は空リストで200を返すこと() {
        when(oddsHistoryService.getHistory(any(), any())).thenReturn(List.of());

        ResponseEntity<List<OddsHistoryItemDto>> response =
                controller.getHistory("https://example.com/race/A", "シンザン");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // ===== getAlerts =====

    @Test
    void getAlerts_アラートリストが200で返されること() {
        String url = "https://example.com/race/A";
        String horseName = "シンザン";
        List<AlertHistoryItemDto> expected = List.of(
                new AlertHistoryItemDto("2026/03/19 10:01:00", "順位乖離", 3.0),
                new AlertHistoryItemDto("2026/03/19 10:03:00", "支持率急増", 2.5)
        );
        when(oddsHistoryService.getAlerts(url, horseName)).thenReturn(expected);

        ResponseEntity<List<AlertHistoryItemDto>> response = controller.getAlerts(url, horseName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).alertType()).isEqualTo("順位乖離");
    }

    @Test
    void getAlerts_データがない場合は空リストで200を返すこと() {
        when(oddsHistoryService.getAlerts(any(), any())).thenReturn(List.of());

        ResponseEntity<List<AlertHistoryItemDto>> response =
                controller.getAlerts("https://example.com/race/A", "シンザン");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }
}
