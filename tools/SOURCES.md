# 同梱データの出典とライセンス

このアプリが同梱する語彙データベースは、すべて**再配布が認められている公開データ**から
`tools/` のスクリプトで機械的に生成しています。市販の単語帳は一切使っていません。

生成手順は `tools/fetch.sh` → `tools/step1..4` → `tools/build_db.py` で完全に再現できます。

| 用途 | 出典 | ライセンス |
| --- | --- | --- |
| 語義・定義・例文・語法ラベル・語義ごとの日本語訳・発音記号・語源・活用形・熟語・句動詞 | [English Wiktionary](https://en.wiktionary.org/)（[kaikki.org](https://kaikki.org/dictionary/English/) による機械可読版） | CC BY-SA 4.0 / GFDL |
| 語義ごとの日本語訳・日本語定義・対訳例文 | [日本語 WordNet](https://bond-lab.github.io/wnja/)（NICT） | CC BY 3.0 |
| 語義の並び順・SemCor 出現頻度 | [Princeton WordNet 3.0](https://wordnet.princeton.edu/) | WordNet License（BSD 相当） |
| 英日対訳の例文 | [Tatoeba Project](https://tatoeba.org/) | CC BY 2.0 FR（一部 CC0 1.0） |
| 語彙レベル（A1–B2） | 『CEFR-J Wordlist Version 1.6』東京外国語大学 投野由紀夫研究室 | 出典明記のうえ研究・教育・商用利用可 |
| 高頻度語 2,801 語 | [New General Service List (NGSL)](https://www.newgeneralservicelist.com/) | CC BY-SA 4.0 |
| 学術語 963 語（英語定義・日本語訳つき） | [New Academic Word List (NAWL)](https://www.newgeneralservicelist.com/new-academic-word-list) | CC BY-SA 4.0 |
| TOEIC 頻出語 | [TOEIC Service List (TSL)](https://www.newgeneralservicelist.com/toeic-service-list) | CC BY-SA 4.0 |
| B2 超の語の頻度順位 | [wikipedia-word-frequency](https://github.com/IlyaSemenov/wikipedia-word-frequency)（English Wikipedia 2023-04-13） | MIT |
| 日本語語義の補助 | [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html)（電子辞書研究開発グループ） | CC BY-SA 4.0 |

## 引用の表記

アプリ内の「データについて」画面と、この表が、各ライセンスが求める帰属表示にあたります。

- CEFR-J: 『CEFR-J Wordlist Version 1.6』東京外国語大学投野由紀夫研究室（<https://www.cefr-j.org/download.html> より 2026 年ダウンロード）
- Tatoeba: 例文の一部は Tatoeba Project（<https://tatoeba.org>）に由来し、CC BY 2.0 FR で提供されています。
- Wiktionary / 日本語 WordNet: 語義と日本語訳の一部はこれらに由来します。CC BY-SA の継承条件により、
  生成された語彙データベース（`app/src/main/assets/content.db.gz`）も同条件で再配布できます。

## 入っていないもの

- **偏差値・入試の配点**: 予備校の私有データ、または大学ごとの募集要項にしかないため。
- **市販単語帳の見出し語順・訳語**: 著作物であるため。自分の単語帳は TSV 取り込みで追加してください。
- **音声**: Tatoeba の音声は話者ごとにライセンスが異なるため同梱せず、端末の TTS を使います。
