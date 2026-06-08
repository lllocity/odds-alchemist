'use client';

import { useState, useEffect } from 'react';

type HorseAnalysis = {
  number: number;
  name: string;
  verdict: '軸候補' | '相手候補' | '対象外';
  comment: string;
  trend_evidence: string;
};

type AnalysisResult = {
  horses: HorseAnalysis[];
  trend_summary: string;
  summary: string;
  confidence_score: number;
  model: string;
  is_chaotic_race: boolean;
  chaotic_note: string;
};

const VERDICT_STYLES: Record<string, string> = {
  '軸候補':   'bg-emerald-100 text-emerald-800 border border-emerald-200',
  '相手候補': 'bg-blue-100 text-blue-800 border border-blue-200',
  '対象外':   'bg-gray-100 text-gray-400 border border-gray-200',
};

const MODELS = [
  { id: 'gemini-3-flash-preview', label: 'Gemini 3 Flash' },
  { id: 'gemini-3.1-flash-lite',  label: 'Gemini 3.1 Flash Lite' },
  { id: 'gemini-2.5-flash',       label: 'Gemini 2.5 Flash' },
] as const;

type CacheEntry = { result: AnalysisResult; analyzedAt: Date; elapsedMs: number };

function buildBets(horses: HorseAnalysis[], isChaoticRace: boolean) {
  const jiku = horses.filter(h => h.verdict === '軸候補');
  const aite = horses.filter(h => h.verdict === '相手候補');
  if (jiku.length === 0) return null;

  // 単勝: 軸候補の馬全員
  const tansho = jiku;

  // 馬単（逆流し）: 軸候補1着 × 相手候補2着 の全組み合わせ
  type UmatanBet = { first: HorseAnalysis; second: HorseAnalysis };
  const umatan: UmatanBet[] = [];
  for (const j of jiku) {
    for (const a of aite) {
      umatan.push({ first: j, second: a });
    }
  }

  // 三連単: 荒れレース判定時のみ。軸候補1着 → 相手候補2・3着の流し
  type SanrentanBet = { first: HorseAnalysis; second: HorseAnalysis; third: HorseAnalysis };
  const sanrentan: SanrentanBet[] = [];
  if (isChaoticRace && aite.length >= 2) {
    for (const j of jiku) {
      for (let i = 0; i < aite.length; i++) {
        for (let k = i + 1; k < aite.length; k++) {
          sanrentan.push({ first: j, second: aite[i], third: aite[k] });
        }
      }
    }
  }

  const total = tansho.length + umatan.length + sanrentan.length;
  return { tansho, umatan, sanrentan, total };
}

export default function OddsAnalysis({ url, onAnalyzingChange }: { url: string; onAnalyzingChange?: (v: boolean) => void }) {
  const [cache, setCache] = useState<Map<string, CacheEntry>>(new Map());
  const [selectedModel, setSelectedModel] = useState<string>(MODELS[0].id);
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openEvidence, setOpenEvidence] = useState<Set<number>>(new Set());
  const [elapsedMs, setElapsedMs] = useState<number | null>(null);
  const [analyzedAt, setAnalyzedAt] = useState<Date | null>(null);

  const cacheKey = `${url}:${selectedModel}`;

  useEffect(() => {
    const entry = cache.get(cacheKey) ?? null;
    setResult(entry?.result ?? null);
    setElapsedMs(entry?.elapsedMs ?? null);
    setAnalyzedAt(entry?.analyzedAt ?? null);
    setError(null);
    setOpenEvidence(new Set());
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, selectedModel]);

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
      const res = await fetch(`/api/odds/analysis?url=${encodeURIComponent(url)}&model=${encodeURIComponent(selectedModel)}`);
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error((body as { error?: string }).error ?? `エラー: ${res.status}`);
      }
      const data: AnalysisResult = await res.json();
      const elapsed = Date.now() - startedAt;
      const at = new Date();
      setElapsedMs(elapsed);
      setAnalyzedAt(at);
      setCache(prev => new Map(prev).set(cacheKey, { result: data, analyzedAt: at, elapsedMs: elapsed }));
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
        <div className="flex items-center gap-2">
          <select
            value={selectedModel}
            onChange={e => setSelectedModel(e.target.value)}
            disabled={isLoading}
            className="px-2 py-1.5 text-xs border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {MODELS.map(m => (
              <option key={m.id} value={m.id}>{m.label}</option>
            ))}
          </select>
          <button
            onClick={handleAnalyze}
            disabled={isLoading || cache.has(cacheKey)}
            className="px-4 py-2 text-sm font-medium rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {isLoading ? '分析中...' : 'AI分析を実行'}
          </button>
        </div>
      </div>
      <div className="flex flex-col items-end gap-0.5 mb-3 min-h-[1.25rem]">
        {analyzedAt && (
          <p className="text-xs text-gray-400">
            {analyzedAt.toLocaleString('ja-JP')} 取得 / モデル: {result?.model ?? ''}
            {elapsedMs !== null && `（${(elapsedMs / 1000).toFixed(1)}秒）`}
          </p>
        )}
        {cache.has(cacheKey) && !isLoading && (
          <p className="text-xs text-gray-400">再度分析する際はモデルを切り替えるか、画面をリロードしてください</p>
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
          {/* 荒れレース判定バナー */}
          {result.is_chaotic_race && (
            <div className="bg-amber-50 border border-amber-200 rounded-lg p-3">
              <p className="text-xs font-bold text-amber-800">⚠ 荒れレースシグナルあり — 三連単サブ買いを検討</p>
              <p className="text-xs text-amber-700 mt-1">{result.chaotic_note}</p>
            </div>
          )}

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
                    className={`mt-0.5 shrink-0 text-xs font-bold px-2 py-0.5 rounded-full ${VERDICT_STYLES[horse.verdict] ?? VERDICT_STYLES['対象外']}`}
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
            const bets = buildBets(result.horses, result.is_chaotic_race);
            if (!bets) return null;
            const { tansho, umatan, sanrentan, total } = bets;
            return (
              <div className="border border-gray-200 rounded-lg overflow-hidden">
                <div className="flex items-center justify-between px-3 py-2 bg-gray-50 border-b border-gray-200">
                  <p className="text-xs font-bold text-gray-700">買い目推奨</p>
                  <p className={`text-xs font-bold ${total > 30 ? 'text-red-600' : 'text-gray-600'}`}>
                    {total > 30 ? `⚠ 合計 ${total} 口（30口超）` : `合計 ${total} 口`}
                  </p>
                </div>

                {/* 単勝 */}
                {tansho.length > 0 && (
                  <div className="px-3 py-2 border-b border-gray-100">
                    <p className="text-xs font-semibold text-gray-600 mb-1">単勝（{tansho.length}口）</p>
                    <div className="space-y-0.5">
                      {tansho.map(h => (
                        <p key={h.number} className="text-xs text-gray-700">
                          {h.number}番 {h.name}
                        </p>
                      ))}
                    </div>
                  </div>
                )}

                {/* 馬単 */}
                {umatan.length > 0 && (
                  <div className={`px-3 py-2 ${sanrentan.length > 0 ? 'border-b border-gray-100' : ''}`}>
                    <p className="text-xs font-semibold text-gray-600 mb-1">馬単 逆流し（{umatan.length}口）</p>
                    <div className="space-y-0.5">
                      {umatan.map((bet, i) => (
                        <p key={i} className="text-xs text-gray-700">
                          {bet.first.number}番 {bet.first.name} → {bet.second.number}番 {bet.second.name}
                        </p>
                      ))}
                    </div>
                  </div>
                )}

                {/* 三連単（荒れレース時のみ） */}
                {sanrentan.length > 0 && (
                  <div className="px-3 py-2">
                    <p className="text-xs font-semibold text-amber-700 mb-1">三連単 流し・荒れ対応（{sanrentan.length}口）</p>
                    <div className="space-y-0.5">
                      {sanrentan.map((bet, i) => (
                        <p key={i} className="text-xs text-gray-700">
                          {bet.first.number}番 {bet.first.name} → {bet.second.number}番 {bet.second.name} → {bet.third.number}番 {bet.third.name}
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
