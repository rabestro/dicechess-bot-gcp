package dicechess.bot

import com.sun.net.httpserver.HttpServer
import lv.id.jc.dicechess.runtime.{CustomHandlerServer, TurnContext, WebhookHandler}

import java.util.function.{Function => JFunction}
import scala.jdk.CollectionConverters.*

/** The Cloud Run entry point. All webhook/HTTP-server plumbing — HMAC verification, the ownership
  * handshake, the JDK `HttpServer` itself — lives in `dicechess-bot-runtime`
  * (`lv.id.jc:dicechess-bot-runtime`); this object only wires our engine-backed, clock-aware
  * [[Strategy]] into it and binds the port Cloud Run gives us.
  *
  * Configuration (env vars; Cloud Run service settings in production):
  *   - `DICECHESS_WEBHOOK_SECRET` — the per-bot signing key from webhook registration. Absent, only
  *     the registration handshake succeeds (deliberate: register → set secret → play).
  *   - `LADDER_INCREMENT_MS` — Fischer increment in ms (default `0`). A deployment-specific stand-in
  *     until the increment rides the wire (dicechess-bot-runtime#7); set `3000` for the 300+3 ladder.
  *   - `OVERHEAD_BUFFER_MS` — slack subtracted from the time-managed budget for network round-trip
  *     plus one uninterruptible rollout (default `300`).
  */
object Main:

  private val WebhookPath = "/api/webhook"

  def main(args: Array[String]): Unit =
    val _      = args
    val secret = sys.env.getOrElse("DICECHESS_WEBHOOK_SECRET", "")
    if secret.isEmpty then
      System.err.println("[bot] DICECHESS_WEBHOOK_SECRET is not set — only the verification handshake will succeed")
    val server = CustomHandlerServer.start(resolvePort, WebhookPath, new WebhookHandler(secret, adapt(Strategy.fromEnvironment)))
    println(s"[bot] monte-carlo custom handler listening on :${server.getAddress.getPort}$WebhookPath")
    Thread.currentThread().join() // serve until the host stops the process

  /** Cloud Run injects `PORT` (default 8080). Fall back to Azure's `FUNCTIONS_CUSTOMHANDLER_PORT`
    * and then 8080, so the same image runs unchanged on Cloud Run, under `func start`, or locally.
    */
  private def resolvePort: Int =
    sys.env
      .get("PORT")
      .orElse(sys.env.get("FUNCTIONS_CUSTOMHANDLER_PORT"))
      .flatMap(_.toIntOption)
      .getOrElse(8080)

  /** Start the server on an explicit port (exposed for the end-to-end test; port 0 = ephemeral). */
  def start(port: Int, secret: String, strategy: Strategy): HttpServer =
    CustomHandlerServer.start(port, WebhookPath, new WebhookHandler(secret, adapt(strategy)))

  /** `dicechess-bot-runtime`'s strategy shape is a plain `java.util.function.Function` — a Scala
    * lambda converts to it via SAM automatically. Unlike the aggressive bot, Monte-Carlo needs the
    * clock, so this reads both `ctx.dfen()` and `ctx.remainingMillis()` (nullable → `Option`).
    */
  private def adapt(strategy: Strategy): JFunction[TurnContext, java.util.List[String]] =
    (ctx: TurnContext) =>
      strategy.chooseMoves(ctx.dfen(), Option(ctx.remainingMillis()).map(_.longValue)).asJava
