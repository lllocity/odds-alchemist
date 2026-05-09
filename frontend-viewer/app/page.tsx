'use client';

import { useState, useEffect, useCallback } from 'react';
import { signOut } from 'next-auth/react';
import AlertList from '@/app/components/AlertList';
import OddsTrendChart from '@/app/components/OddsTrendChart';
import OddsAnalysis from '@/app/components/OddsAnalysis';
import { AnomalyAlert } from '@/app/types/oddsAlert';

/** アラートをポーリングする間隔（ミリ秒）: スクレイピング最短間隔1分に対し10秒で追従 */
const POLLING_INTERVAL_MS = 10_000;

export default function Home() {
  const [alerts, setAlerts] = useState<AnomalyAlert[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [selectedUrl, setSelectedUrl] = useState('');
  const [isAnalyzing, setIsAnalyzing] = useState(false);

  const fetchAlerts = useCallback(async () => {
    try {
      const response = await fetch('/api/alerts');
      if (!response.ok) {
        console.warn(`アラート取得に失敗しました: ${response.status}`);
        return;
      }
      const data: AnomalyAlert[] = await response.json();
      setAlerts(prev => {
        if (JSON.stringify(prev) === JSON.stringify(data)) return prev;
        setLastUpdated(new Date());
        return data;
      });
    } catch (error) {
      console.warn('アラート取得中にエラーが発生しました', error);
    }
  }, []);

  useEffect(() => {
    fetchAlerts();
    const timer = setInterval(fetchAlerts, POLLING_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [fetchAlerts]);

  return (
    <>
      {/* ヘッダーバー */}
      <header className="w-full bg-[#0d1b2e] px-4 sm:px-8 py-3 flex items-center gap-4 shadow-lg">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/logo.png" alt="" className="h-11 w-auto" />
        <h1 className="text-lg font-semibold tracking-widest text-slate-200">配当の錬金術師</h1>
        <div className="ml-auto">
          <button
            onClick={() => signOut({ callbackUrl: '/login' })}
            className="text-xs text-slate-400 hover:text-slate-200 transition-colors"
          >
            サインアウト
          </button>
        </div>
      </header>

      <main className="min-h-screen bg-gray-50 p-3 sm:p-6">
        <div className="max-w-6xl mx-auto">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 sm:gap-6 items-start">

            {/* 左カラム: オッズ推移グラフ */}
            <div className="space-y-6">
              <div className="bg-white rounded-xl shadow-md p-4 sm:p-6">
                <OddsTrendChart onUrlChange={setSelectedUrl} disabled={isAnalyzing} />
              </div>
            </div>

            {/* 右カラム: 買いの掟 + AI分析 + 検知アラート */}
            <div className="space-y-4 sm:sticky sm:top-6">

              {/* 買いの掟 */}
              <div className="bg-amber-50 border border-amber-300 rounded-xl px-5 py-4">
                <p className="text-xs font-bold text-amber-800 mb-2 tracking-wide">⚠ 買いの掟</p>
                <ul className="space-y-1">
                  <li className="text-xs text-amber-900">
                    ・オッズの動きだけ見て買うな。アラートが鳴って初めて動け。
                  </li>
                  <li className="text-xs text-amber-900">
                    ・人気があり、かつ単勝オッズが緩やかに下がり続けている馬は軸候補。資金が継続して入っている証拠。
                  </li>
                  <li className="text-xs text-amber-900">
                    ・複勝オッズだけが緩やかに下がっている馬は対抗候補。「飛ぶよりは来る」と見られている。
                  </li>
                  <li className="text-xs text-amber-900">
                    ・単勝・複勝がともに緩やかに上がっている馬は切り候補。人気離れが進んでいるサイン。
                  </li>
                  <li className="text-xs text-amber-900">
                    ・AI分析はレースの5〜10分前に。
                  </li>
                </ul>
              </div>

              {/* AI オッズ分析（レース選択後に表示） */}
              {selectedUrl && <OddsAnalysis url={selectedUrl} onAnalyzingChange={setIsAnalyzing} />}

              {/* 検知アラート */}
              <div className="bg-white rounded-xl shadow-md p-4 sm:p-6">
                <AlertList alerts={alerts} lastUpdated={lastUpdated} />
              </div>

            </div>
          </div>
        </div>
      </main>
    </>
  );
}
