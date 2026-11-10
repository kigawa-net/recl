package net.kigawa.recl.app

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicLong

/** Read-only MVP dashboard. Module states describe launcher wiring, not dependency health checks. */
internal class Dashboard(
    private val mcpEnabled: Boolean = false,
    private val mcpStorageMode: String? = null,
) {
    private val createdAt = System.nanoTime()
    private val requestCount = AtomicLong()

    fun recordRequest() {
        requestCount.incrementAndGet()
    }

    fun page(exchange: HttpExchange) = handle(exchange, "/dashboard", "text/html", ::html)

    fun metrics(exchange: HttpExchange) = handle(exchange, "/dashboard/api/metrics", "application/json", ::json)

    private fun handle(exchange: HttpExchange, path: String, contentType: String, body: () -> String) {
        exchange.use {
            val status: Int
            val response: String
            val type: String
            if (exchange.requestURI.path != path) {
                status = 404
                response = "{\"error\":\"not_found\"}"
                type = "application/json"
            } else if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                status = 405
                response = "{\"error\":\"method_not_allowed\"}"
                type = "application/json"
                exchange.responseHeaders.set("Allow", "GET, HEAD")
            } else {
                status = 200
                response = body()
                type = contentType
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            val head = exchange.requestMethod == "HEAD"
            exchange.responseHeaders.set("Content-Type", "$type; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(status, if (head) -1 else bytes.size.toLong())
            if (!head) exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun json(): String = """
        {
          "status": "ok",
          "uptimeSeconds": ${(System.nanoTime() - createdAt) / 1_000_000_000},
          "requestCount": ${requestCount.get()},
          "system": {
            "javaVersion": ${quote(System.getProperty("java.version"))},
            "osName": ${quote(System.getProperty("os.name"))},
            "availableProcessors": ${Runtime.getRuntime().availableProcessors()}
          },
          "modules": {
            "http": "running",
            "contextApi": "disabled",
            "fortis": "not_configured",
            "qdrant": "not_configured",
            "mcp": "${if (mcpEnabled) "running" else "not_configured"}",
            "mcpStorage": ${quote(if (mcpEnabled) mcpStorageMode ?: "not_configured" else "not_configured")}
          }
        }
    """.trimIndent()

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u" + char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }

    private fun html(): String = """
        <!doctype html>
        <html lang="ja">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>recl 管理ダッシュボード</title>
          <style>
            * { box-sizing: border-box; }
            body { font-family: system-ui, sans-serif; max-width: 1040px; margin: 0 auto; padding: 16px 20px; background: #f5f7fa; color: #172033; font-size: 14px; }
            header { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 16px; margin-bottom: 12px; }
            h1 { font-size: 1.5rem; margin: 0; }
            header p { margin: 0; color: #526078; }
            nav { margin-bottom: 12px; }
            .dashboard { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
            section { background: white; padding: 14px 16px; border-radius: 8px; min-width: 0; }
            section h2 { font-size: 1.05rem; margin: 0 0 10px; }
            .service { grid-column: 1 / -1; }
            .stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
            .stat { padding: 8px 12px; background: #f5f7fa; border-radius: 6px; }
            .stat-label { color: #526078; }
            .stat-value { display: block; font-size: 1.25rem; font-weight: 650; }
            dl { display: grid; grid-template-columns: minmax(100px, 0.8fr) minmax(0, 1.2fr); gap: 5px 10px; margin: 0; }
            dt { font-weight: 600; }
            dd { margin: 0; overflow-wrap: anywhere; }
            .note { margin: 10px 0 0; color: #526078; font-size: .85rem; }
            #status { margin: 0 0 8px; }
            a { color: #155bb5; }
            button { margin-top: 12px; padding: 6px 12px; cursor: pointer; }
            @media (max-width: 620px) {
              body { padding: 12px; }
              .dashboard { grid-template-columns: 1fr; }
              .service { grid-column: auto; }
              .stats { gap: 8px; }
              section { padding: 12px; }
            }
          </style>
        </head>
        <body>
          <header><h1>recl 管理ダッシュボード</h1><p>開発用 MVP・読み取り専用（認証なし、プロダクション非対応）</p></header>
          <nav><a href="/health">ヘルスチェック</a> · <a href="/dashboard/api/metrics">JSON メトリクス</a></nav>
          <main class="dashboard">
          <section class="service">
            <h2>サービスステータス</h2>
            <p id="status" role="status">取得中…</p>
            <div class="stats">
              <div class="stat"><span class="stat-label">稼働時間</span><strong class="stat-value" id="uptime">—</strong></div>
              <div class="stat"><span class="stat-label">HTTP リクエスト数</span><strong class="stat-value" id="requests">—</strong></div>
            </div>
            <p class="note">リクエスト数は全パス・全メソッドを含み、この画面からの取得も加算します。</p>
          </section>
          <section><h2>システム情報</h2><dl id="system"></dl></section>
          <section>
            <h2>モジュール状態</h2>
            <p class="note">起動構成の表示です（外部サービスのヘルスチェックなし）。</p>
            <dl id="modules"></dl>
          </section>
          </main>
          <button id="refresh" type="button">更新</button>
          <script>
            function renderDetails(id, values) {
              const list = document.getElementById(id);
              list.replaceChildren();
              for (const [key, value] of Object.entries(values)) {
                const term = document.createElement('dt');
                const detail = document.createElement('dd');
                term.textContent = key;
                detail.textContent = value;
                list.append(term, detail);
              }
            }
            async function refresh() {
              const button = document.getElementById('refresh');
              button.disabled = true;
              try {
                const response = await fetch('/dashboard/api/metrics', { cache: 'no-store' });
                if (!response.ok) throw new Error('HTTP ' + response.status);
                const metrics = await response.json();
                document.getElementById('uptime').textContent = metrics.uptimeSeconds + ' 秒';
                document.getElementById('requests').textContent = metrics.requestCount;
                renderDetails('system', metrics.system);
                renderDetails('modules', metrics.modules);
                document.getElementById('status').textContent = 'HTTP サービス稼働中 (' + metrics.status + ')';
              } catch (error) {
                document.getElementById('status').textContent = 'メトリクス取得失敗: ' + error.message;
              } finally {
                button.disabled = false;
              }
            }
            document.getElementById('refresh').addEventListener('click', refresh);
            refresh();
          </script>
        </body>
        </html>
    """.trimIndent()
}
