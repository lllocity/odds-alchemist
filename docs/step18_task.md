# タスク: Step 18 Spring Boot Admin による監視ダッシュボードの導入

## 目的
CLIでのプロセス管理とログ監視（`tail -f`）の煩わしさを解消するため、Spring Boot Admin を導入し、ブラウザ上で稼働状況やログストリームを視覚的に管理できるようにする。

## 実行要件

1. **依存関係の追加 (`build.gradle`)**
   - 以下のライブラリを追加する（Spring Bootのバージョンに合わせること）。
     - `spring-boot-admin-starter-server`
     - `spring-boot-admin-starter-client`
     - `spring-boot-starter-actuator`

2. **Admin Serverの有効化**
   - Spring Bootのメインアプリケーションクラス（`@SpringBootApplication` があるクラス）に `@EnableAdminServer` アノテーションを付与する。

3. **設定の追加 (`application.yml`)**
   - **Actuatorのエンドポイント公開**: Spring Boot Adminが情報を収集できるよう、`management.endpoints.web.exposure.include=*` を設定する。
   - **ログファイルの設定**: 画面上でログのリアルタイムストリーム（tail）を見られるようにするため、ファイルへのログ出力設定（`logging.file.name=logs/app.log` 等）を追加する。
   - **Admin UIのパス設定**: 既存のAPIとルーティングが競合しないよう、Adminの画面URLを `spring.boot.admin.context-path=/admin` に設定する。
   - **クライアントの自己登録**: 自身を監視対象として登録するため、`spring.boot.admin.client.url=http://localhost:8080` （ポートは環境に合わせる）を設定する。

4. **ドキュメントの更新**
   - 導入が完了したら、`README.md` と `CLAUDE.md` に、監視ダッシュボードへのアクセス方法（URL）を追記する。