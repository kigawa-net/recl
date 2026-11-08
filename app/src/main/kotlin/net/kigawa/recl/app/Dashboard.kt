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
            body { font-family: system-ui, sans-serif; max-width: 800px; margin: 40px auto; padding: 0 20px; background: #f5f7fa; color: #172033; }
            section { background: white; padding: 20px; margin: 16px 0; border-radius: 8px; }
            dt { font-weight: bold; margin-top: 12px; }
            dd { margin: 4px 0; }
            a { color: #155bb5; }
            button { padding: 8px 16px; cursor: pointer; }
          </style>
        </head>
        <body>
          <h1>recl 管理ダッシュボード</h1>
          <p>開発用 MVP・読み取り専用（認証なし、プロダクション非対応）</p>
          <nav><a href="/health">ヘルスチェック</a> · <a href="/dashboard/api/metrics">JSON メトリクス</a></nav>
          <section>
            <h2>サービスステータス</h2>
            <p id="status" role="status">取得中…</p>
            <dl>
              <dt>稼働時間（サーバーインスタンス作成から）</dt><dd id="uptime">—</dd>
              <dt>HTTP リクエスト数</dt><dd id="requests">—</dd>
            </dl>
            <p>リクエスト数は全パス・全メソッドを含み、この画面からのメトリクス取得も加算します。</p>
          </section>
          <section><h2>システム情報</h2><dl id="system"></dl></section>
          <section>
            <h2>モジュール状態</h2>
            <p>起動構成の表示です。外部サービスのヘルスチェックは実行しません。</p>
            <dl id="modules"></dl>
          </section>
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
