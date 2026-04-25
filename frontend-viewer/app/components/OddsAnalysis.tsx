'use client';

import { useState, useEffect } from 'react';

type HorseAnalysis = {
  number: number;
  name: string;
  verdict: '本命' | '対抗' | '3着紐' | '消し';
  comment: string;
  trend_evidence: string;
};

type AnalysisResult = {
  horses: HorseAnalysis[];
  trend_summary: string;
  summary: string;
  confidence_score: number;
};

const VERDICT_STYLES: Record<string, string> = {
  '本命': 'bg-red-100 text-red-800 border border-red-200',
  '対抗': 'bg-orange-100 text-orange-800 border border-orange-200',
  '3着紐': 'bg-blue-100 text-blue-800 border border-blue-200',
  '消し': 'bg-gray-100 text-gray-400 border border-gray-200',
};

const GEMINI_MODEL = 'gemini-2.5-flash';

type CacheEntry = { result: AnalysisResult; analyzedAt: Date; elapsedMs: number };

function buildBets(horses: HorseAnalysis[]) {
  const honmei = horses.filter(h => h.verdict === '本命');
  const taikou = horses.filter(h => h.verdict === '対抗');
  const himo   = horses.filter(h => h.verdict === '3着紐');
  if (honmei.length === 0) return null;

  // 三連複: 本命×対抗 軸2頭 × 3着紐 流し
  type SanrenpukuBet = { axis1: HorseAnalysis; axis2: HorseAnalysis; himo: HorseAnalysis };
  const sanrenpukuBets: SanrenpukuBet[] = [];
  for (const h1 of honmei) {
    for (const h2 of taikou) {
      for (const h3 of himo) {
        sanrenpukuBets.push({ axis1: h1, axis2: h2, himo: h3 });
      }
    }
  }

  // ワイド: 本命 × 3着紐 の全ペア
  type WideBet = [HorseAnalysis, HorseAnalysis];
  const wideBets: WideBet[] = [];
  for (const h1 of honmei) {
    for (const h2 of himo) {
      wideBets.push([h1, h2]);
    }
  }

  const total = sanrenpukuBets.length + wideBets.length;
  return { honmei, taikou, himo, sanrenpukuBets, wideBets, total };
}

export default function OddsAnalysis({ url, onAnalyzingChange }: { url: string; onAnalyzingChange?: (v: boolean) => void }) {
  const [cache, setCache] = useState<Map<string, CacheEntry>>(new Map());
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openEvidence, setOpenEvidence] = useState<Set<number>>(new Set());
  const [elapsedMs, setElapsedMs] = useState<number | null>(null);
  const [analyzedAt, setAnalyzedAt] = useState<Date | null>(null);

  useEffect(() => {
    const entry = cache.get(url) ?? null;
    setResult(entry?.result ?? null);
    setElapsedMs(entry?.elapsedMs ?? null);
    setAnalyzedAt(entry?.analyzedAt ?? null);
    setError(null);
    setOpenEvidence(new Set());
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url]);

  const handleAnalyze = async () => {
    setIsLoading(true);
    onAnalyzingChange?.(true);
    setError(null);
    setResult(null);
    setElapsedMs(null);
    setAnalyzedAt(null);
    setOpenEvidence(new Set());
    const startedAt = Date.now();
    try {
      const res = await fetch(`/api/odds/analysis?url=${encodeURIComponent(url)}`);
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error((body as { error?: string }).error ?? `エラー: ${res.status}`);
      }
      const data: AnalysisResult = await res.json();
      const elapsed = Date.now() - startedAt;
      const at = new Date();
      setElapsedMs(elapsed);
      setAnalyzedAt(at);
      setCache(prev => new Map(prev).set(url, { result: data, analyzedAt: at, elapsedMs: elapsed }));
      setResult(data);
    } catch (e) {
      console.warn('AI分析の取得に失敗しました', e);
      setError(e instanceof Error ? e.message : 'AI分析に失敗しました');
    } finally {
      setIsLoading(false);
      onAnalyzingChange?.(false);
    }
  };

  const toggleEvidence = (num: number) => {
    setOpenEvidence(prev => {
      const next = new Set(prev);
      if (next.has(num)) next.delete(num);
      else next.add(num);
      return next;
    });
  };

  return (
    <div className="bg-white rounded-xl shadow-md p-4 sm:p-6">
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-base font-semibold text-gray-800">AI オッズ分析</h2>
        <button
          onClick={handleAnalyze}
          disabled={isLoading || cache.has(url)}
          className="px-4 py-2 text-sm font-medium rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {isLoading ? '分析中...' : 'AI分析を実行'}
        </button>
      </div>
      <div className="flex flex-col items-end gap-0.5 mb-3 min-h-[1.25rem]">
        {analyzedAt && (
          <p className="text-xs text-gray-400">
            {analyzedAt.toLocaleString('ja-JP')} 取得 / モデル: {GEMINI_MODEL}
            {elapsedMs !== null && `（${(elapsedMs / 1000).toFixed(1)}秒）`}
          </p>
        )}
        {cache.has(url) && !isLoading && (
          <p className="text-xs text-gray-400">再度分析する際は画面をリロードしてください</p>
        )}
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 rounded-lg p-3">{error}</p>
      )}

      {isLoading && (
        <p className="text-sm text-gray-400 text-center py-6">Gemini が分析しています... しばらくお待ちください</p>
      )}

      {result && (
        <div className="space-y-4">
          {/* 推移分析サマリー */}
          <div className="bg-indigo-50 border border-indigo-100 rounded-lg p-4">
            <p className="text-xs font-bold text-indigo-800 mb-1 tracking-wide">推移分析</p>
            <p className="text-sm text-indigo-900 leading-relaxed">{result.trend_summary}</p>
          </div>

          {/* 全馬評価 */}
          <div className="space-y-2">
            {result.horses.map((horse) => (
              <div key={horse.number} className="border border-gray-200 rounded-lg overflow-hidden">
                <div className="flex items-start gap-3 px-3 py-2">
                  <span
                    className={`mt-0.5 shrink-0 text-xs font-bold px-2 py-0.5 rounded-full ${VERDICT_STYLES[horse.verdict] ?? VERDICT_STYLES['注目']}`}
                  >
                    {horse.verdict}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-gray-800">
                      {horse.number}番 {horse.name}
                    </p>
                    <p className="text-xs text-gray-600 mt-0.5 leading-relaxed">{horse.comment}</p>
                  </div>
                  <button
                    onClick={() => toggleEvidence(horse.number)}
                    className="shrink-0 text-xs text-gray-400 hover:text-gray-600 mt-1"
                    aria-label="推移根拠を開閉"
                  >
                    {openEvidence.has(horse.number) ? '▲' : '▼'}
                  </button>
                </div>
                {openEvidence.has(horse.number) && (
                  <div className="border-t border-gray-100 bg-gray-50 px-3 py-2">
                    <p className="text-xs font-medium text-gray-500 mb-1">推移根拠</p>
                    <p className="text-xs text-gray-600 leading-relaxed whitespace-pre-wrap">
                      {horse.trend_evidence}
                    </p>
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* 買い目推奨 */}
          {(() => {
            const bets = buildBets(result.horses);
            if (!bets) return null;
            const { sanrenpukuBets, wideBets, total } = bets;
            return (
              <div className="border border-gray-200 rounded-lg overflow-hidden">
                <div className="flex items-center justify-between px-3 py-2 bg-gray-50 border-b border-gray-200">
                  <p className="text-xs font-bold text-gray-700">買い目推奨</p>
                  <p className={`text-xs font-bold ${total > 30 ? 'text-red-600' : 'text-gray-600'}`}>
                    {total > 30 ? `⚠ 合計 ${total} 口（30口超）` : `合計 ${total} 口`}
                  </p>
                </div>

                {/* 三連複 */}
                {sanrenpukuBets.length > 0 && (
                  <div className="px-3 py-2 border-b border-gray-100">
                    <p className="text-xs font-semibold text-gray-600 mb-1">三連複（{sanrenpukuBets.length}口）</p>
                    <div className="space-y-0.5">
                      {sanrenpukuBets.map((bet, i) => (
                        <p key={i} className="text-xs text-gray-700">
                          {bet.axis1.number}番 {bet.axis1.name}・{bet.axis2.number}番 {bet.axis2.name} → {bet.himo.number}番 {bet.himo.name}
                        </p>
                      ))}
                    </div>
                  </div>
                )}

                {/* ワイド */}
                {wideBets.length > 0 && (
                  <div className="px-3 py-2">
                    <p className="text-xs font-semibold text-gray-600 mb-1">ワイド（{wideBets.length}口）</p>
                    <div className="space-y-0.5">
                      {wideBets.map(([h1, h2], i) => (
                        <p key={i} className="text-xs text-gray-700">
                          {h1.number}番 {h1.name} ー {h2.number}番 {h2.name}
                        </p>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            );
          })()}

          {/* 全体所感 */}
          <div className="bg-gray-50 rounded-lg p-3">
            <p className="text-xs text-gray-600 leading-relaxed">{result.summary}</p>
          </div>

          {/* 推奨確度 */}
          <p className="text-right text-xs text-gray-400">推奨確度: {result.confidence_score}/100</p>
        </div>
      )}
    </div>
  );
}
