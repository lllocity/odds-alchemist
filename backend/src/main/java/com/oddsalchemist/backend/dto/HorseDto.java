package com.oddsalchemist.backend.dto;

/**
 * 馬情報を保持するRecordクラス。
 * オッズ推移グラフの馬選択ドロップダウン用に使用する。
 *
 * @param horseNumber 馬番
 * @param horseName   馬名
 */
public record HorseDto(Integer horseNumber, String horseName) {}
