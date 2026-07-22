package dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.TurnGenerator
import io.circe.parser.parse
import lv.id.jc.dicechess.runtime.{Signatures, WebhookHandler}

/** Proves `Main`'s wiring — the library's `WebhookHandler`/`CustomHandlerServer` talking to our
  * real engine-backed, clock-aware `Strategy`, end to end over a real socket. The webhook mechanics
  * themselves (signature verification, the handshake, malformed input, ...) are
  * `dicechess-bot-runtime`'s own responsibility and are covered there; this suite only shows that
  * plugging our strategy into the library produces a legal engine move on a signed, clocked turn.
  *
  * A small clock in the payload exercises the time-managed path; a tiny remaining time keeps the
  * search deadline in the tens of milliseconds so the test stays fast.
  */
class MainSuite extends munit.FunSuite:

  private val Secret     = "test-webhook-secret"
  private val strategy   = new Strategy(overheadBufferMs = 5, defaultThinkMs = 200)
  private val initialNbk = FenParser.InitialPosition + " NBK"

  test("end to end over real HTTP: a signed, clocked turn returns a path the engine considers legal"):
    val server = Main.start(port = 0, secret = Secret, strategy = strategy)
    try
      val base   = s"http://127.0.0.1:${server.getAddress.getPort}/api/webhook"
      val client = java.net.http.HttpClient.newHttpClient()

      def post(body: String, headers: Map[String, String]): java.net.http.HttpResponse[String] =
        val builder = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(base))
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        headers.foreach((k, v) => builder.header(k, v))
        client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())

      val handshake = post("""{"type":"verification","nonce":"live-1"}""", Map.empty)
      assertEquals(handshake.statusCode(), 200)
      assertEquals(parse(handshake.body()).toOption.get.hcursor.get[String]("nonce"), Right("live-1"))

      val body =
        s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"dfen":"$initialNbk","clocks":{"white":800,"black":800},"timeControl":{"Fischer":{"initialSeconds":300,"incrementSeconds":3}}}}"""
      val ts   = System.currentTimeMillis() / 1000
      val turn = post(
        body,
        Map(
          WebhookHandler.TIMESTAMP_HEADER -> ts.toString,
          WebhookHandler.SIGNATURE_HEADER -> Signatures.sign(Secret, ts, body)
        )
      )
      assertEquals(turn.statusCode(), 200)
      val moves = parse(turn.body()).toOption.get.hcursor.get[List[String]]("moves").toOption.get
      assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
      val state      = FenParser.parse(initialNbk).toOption.get
      val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
      assert(legalPaths.contains(moves), s"$moves must be one of the engine's own legal paths")
    finally server.stop(0)
