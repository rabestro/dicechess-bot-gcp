package dicechess.bot

import dicechess.engine.domain.{FenParser, Move}
import dicechess.engine.search.{ClockState, MonteCarloSearch, TimeManager}

import scala.util.Random

/** The move-choosing brain: the engine's Monte-Carlo search, driven by the game clock.
  *
  * This is the payoff of linking the engine instead of talking to it over the wire — the bot needs
  * nothing but the DFEN: the engine parses it (dice pool included, 7th field), enumerates the legal
  * turns itself, and estimates each resulting position's win probability by Rao-Blackwellized
  * rollouts. Strength scales with the number of rollouts, which scales with the wall-clock budget —
  * so unlike a one-ply bot, Monte-Carlo genuinely wants time, and this class's whole job each turn
  * is to turn the remaining clock into a search deadline.
  *
  * Time management is **not** reinvented here: the engine's own
  * [[dicechess.engine.search.TimeManager]] maps the clock to a per-turn budget (reserve-protected,
  * increment-aware, phase-tapered, hard-capped so a single turn can never flag). We only build the
  * [[dicechess.engine.search.ClockState]] and hand the resulting deadline to the search — keeping
  * every game-logic decision in the engine, exactly like `/play` and play-api do.
  *
  * @param incrementMs
  *   Fischer increment in ms. Not on the webhook wire yet (see dicechess-bot-runtime#7), so it is
  *   configured; `0` means sudden-death budgeting, which is safe (slightly under-thinks, never flags).
  * @param overheadBufferMs
  *   slack subtracted from the budget for the play-api↔Cloud Run round-trip and one uninterruptible
  *   rollout, so the realised think time stays short of the allocation.
  * @param defaultThinkMs
  *   per-turn deadline used when there is no clock to manage (unlimited control) — the unbounded
  *   rollout path can take tens of seconds on a high-branching roll, so the search is always bounded.
  */
final class Strategy(incrementMs: Long, overheadBufferMs: Long, defaultThinkMs: Long):

  /** DFEN in (the envelope's `state.dfen`), UCI micro-move path out. `Nil` = nothing to play (a
    * forced pass, an unusable DFEN, or a deadline too short for even the fallback) — the server
    * auto-passes and the webhook answers `{"moves": []}`, which plays nothing: correct and harmless.
    *
    * @param remainingMillis
    *   the mover's own clock, or `None` when the control is unlimited / the clock is absent — then
    *   the search runs against a fixed default deadline (`defaultThinkMs`).
    */
  def chooseMoves(dfen: String, remainingMillis: Option[Long]): List[String] =
    FenParser.parse(dfen) match
      case Left(reason) =>
        System.err.println(s"[bot] unusable dfen: $reason")
        Nil
      case Right(state) =>
        val rng = new Random()
        val scored = remainingMillis match
          case Some(remaining) if remaining > 0 =>
            val clock         = ClockState(remainingMs = remaining, incrementMs = incrementMs, moveNumber = state.fullMoveNumber)
            val budgetMs      = TimeManager.budgetMs(clock, overheadBufferMs)
            val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L
            MonteCarloSearch.findBestMove(state, deadlineNanos, rng)
          case _ =>
            // Unlimited / unknown clock: nothing to manage, but the unbounded rollout path can take
            // tens of seconds on a high-branching opening roll — so bound it with a fixed default
            // deadline, keeping lobby games against a human responsive.
            val deadlineNanos = System.nanoTime() + defaultThinkMs * 1_000_000L
            MonteCarloSearch.findBestMove(state, deadlineNanos, rng)
        scored.map(_.moves.map(Strategy.toUci)).getOrElse(Nil)

object Strategy:

  /** UCI for a search-layer `Move` (which has no notation of its own) — the same recipe play-api's
    * `EngineOps` uses, so the strings this bot submits are byte-for-byte what the server's own
    * enumeration produces.
    */
  def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")

  /** Production wiring: read the clock knobs from the environment with safe defaults. */
  def fromEnvironment: Strategy =
    val incrementMs  = sys.env.get("LADDER_INCREMENT_MS").flatMap(_.toLongOption).getOrElse(0L)
    val overheadMs   = sys.env.get("OVERHEAD_BUFFER_MS").flatMap(_.toLongOption).getOrElse(300L)
    val defaultThink = sys.env.get("DEFAULT_THINK_MS").flatMap(_.toLongOption).getOrElse(2000L)
    new Strategy(incrementMs, overheadMs, defaultThink)
