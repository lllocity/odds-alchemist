'use client';

import { useState, useEffect } from 'react';

type HorseAnalysis = {
  number: number;
  name: string;
  verdict: '本命' | '対抗' | '紐候補' | '注目' | '消し';
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
  '紐候補': 'bg-blue-100 text-blue-800 border border-blue-200',
  '注目': 'bg-yellow-100 text-yellow-800 border border-yellow-200',
  '消し': 'bg-gray-100 text-gray-400 border border-gray-200',
};

export default function OddsAnalysis({ url }: { url: string }) {
  const [cache, setCache] = useState<Map<string, AnalysisResult>>(new Map());
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openEvidence, setOpenEvidence] = useState<Set<number>>(new Set());

  useEffect(() => {
    setResult(cache.get(url) ?? null);
    setError(null);
    setOpenEvidence(new Set());
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url]);

  const handleAnalyze = async () => {
    setIsLoading(true);
    setError(null);
    setResult(null);
    setOpenEvidence(new Set());
    try {
      const res = await fetch(`/api/odds/analysis?url=${encodeURIComponent(url)}`);
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error((body as { error?: string }).error ?? `エラー: ${res.status}`);
      }
      const data: AnalysisResult = await res.json();
      setCache(prev => new Map(prev).set(url, data));
      setResult(data);
    } catch (e) {
      console.warn('AI分析の取得に失敗しました', e);
      setError(e instanceof Error ? e.message : 'AI分析に失敗しました');
    } finally {
      setIsLoading(false);
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
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-base font-semibold text-gray-800">AI オッズ分析</h2>
        <button
          onClick={handleAnalyze}
          disabled={isLoading || cache.has(url)}
          className="px-4 py-2 text-sm font-medium rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {isLoading ? '分析中...' : 'AI分析を実行'}
        </button>
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
