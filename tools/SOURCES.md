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
| 語の分解（接頭辞・語根・接尾辞）、接辞の英語定義 | [English Wiktionary](https://en.wiktionary.org/) の語源テンプレート（prefix / suffix / affix / confix）と接辞の項目 | CC BY-SA 4.0 / GFDL |
| 接辞の日本語訳 | `tools/affix_ja.json`（本リポジトリで書き下ろし） | このリポジトリのライセンスに従う |
| 日本語訳の並び順（頻度の高い語を先頭に） | [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html)（電子辞書研究開発グループ）の優先度タグと頻度帯 nf01–nf48 | CC BY-SA 4.0 |

## 引用の表記

アプリ内の「データについて」画面と、この表が、各ライセンスが求める帰属表示にあたります。

- CEFR-J: 『CEFR-J Wordlist Version 1.6』東京外国語大学投野由紀夫研究室（<https://www.cefr-j.org/download.html> より 2026 年ダウンロード）
- Tatoeba: 例文の一部は Tatoeba Project（<https://tatoeba.org>）に由来し、CC BY 2.0 FR で提供されています。
- Wiktionary / 日本語 WordNet: 語義と日本語訳の一部はこれらに由来します。CC BY-SA の継承条件により、
  生成された語彙データベース（`app/src/main/assets/content.dbz`）も同条件で再配布できます。

## 読解用の英文（passages.dbz）

アプリには英文が最初から入っています。貼らせた時点でほとんどの人は使わないからです。
13ジャンルを3つの取得元でまかない、いずれも再配布が認められています。

| ジャンル | 出典 | ライセンス |
| --- | --- | --- |
| 自然科学 / 技術 / 環境 / 経済 / 歴史 / 哲学 / 言語 / 芸術 / 社会 | English Wikipedia（カテゴリから、API の plaintext 抽出） | CC BY-SA 4.0 |
| やさしい英語 | Simple English Wikipedia | CC BY-SA 4.0 |
| 時事 | English Wikinews | CC BY 2.5 |
| 物語・文学 / 随筆・演説 | Project Gutenberg（Gutendex 経由） | パブリックドメイン |
| 医学・脳科学・心理 | PubMed Central のオープンアクセス集合 | 各論文の `<license>` が CC BY / CC0 のものだけ |

構文解析は端末では行わず、`tools/step8_syntax.py` が spaCy で1度だけ行って木ごと同梱します。
`en_core_web_trf` と `en_core_web_sm` はいずれも MIT です。

### 取得元として採らなかったもの

いずれもスクレイピングが必要で、出所を後から確かめられない英文を入れるくらいなら
ジャンルを1つ落とすほうがよい、と判断しました。

- **VOA Learning English**（パブリックドメイン）— RSS の入口が不透明な ID で、一覧の手段が公開されていない
- **NASA / NOAA**（パブリックドメイン）— WordPress の REST 出力がサイトの付属物ばかりで、本文を掘り出す必要がある
- **World Bank OKR**（CC BY）— 本体が PDF

## 書き下ろした唯一のもの: 接辞の日本語訳

Wiktionary の接辞ページは翻訳表をほとんど持たず、機械的に取れた日本語は **23 件**でした。
接辞は「演算子」として一行で意味が出ないと分解バーも格子も成立しないため、
ここだけは `tools/affix_ja.json` に書き下ろしています（218 項目）。

市販の単語帳・語源本からの転記ではありません。書いてあるのは
「その接辞が語に対して何をするか」の一行だけで、見出し語・語義・例文は含みません。
この表に無い接辞は、Wiktionary の英語定義がそのまま出ます。

## 入っていないもの

- **偏差値・入試の配点**: 予備校の私有データ、または大学ごとの募集要項にしかないため。
- **市販単語帳の見出し語順・訳語**: 著作物であるため。自分の単語帳は TSV 取り込みで追加してください。
- **音声**: Tatoeba の音声は話者ごとにライセンスが異なるため同梱せず、端末の TTS を使います。
- **JMdict の逆引き（＝和英辞典を逆に引いて訳語を作ること）**: 訳語の質が落ちるため採用していません。実際に試すと
  `adaptable`→「滑脱」、`addicted`→「どっぷり」、`agricultural`→「農用」のように、
  日本語としては正しくても見出し語の訳としては使えないものが出ます。そのぶん
  `bravely` `coastal` のような派生語が約 400 語落ちますが、訳がないよりも
  **誤った訳が入るほうが害が大きい**と判断しました。
  JMdict は「その日本語がどれくらい使われるか」の判定にだけ使っています（訳語の並べ替え）。
