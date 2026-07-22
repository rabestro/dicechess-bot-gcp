package dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.TurnGenerator

/** The engine does the chess; this suite only proves the wiring: a tiny wall-clock budget still
  * yields one of the engine's own legal turn paths (Monte-Carlo always returns the best material
  * candidate as a fallback, so a legal move comes back even if the deadline elapses before any
  * rollout completes), the no-clock path works, and an unusable DFEN degrades to a pass.
  */
class StrategySuite extends munit.FunSuite:

  private val initialNbk = FenParser.InitialPosition + " NBK"

  private def legalPaths(dfen: String): Set[List[String]] =
    TurnGenerator.generateAllLegalTurnPaths(FenParser.parse(dfen).toOption.get).map(_.map(Strategy.toUci)).toSet

  test("returns one of the engine's own legal turn paths under a tight deadline"):
    val moves = new Strategy(incrementMs = 0, overheadBufferMs = 5, defaultThinkMs = 200).chooseMoves(initialNbk, Some(800L))
    assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
    assert(legalPaths(initialNbk).contains(moves), s"$moves must be a legal path")

  test("plays a legal move with no clock (unlimited control)"):
    val moves = new Strategy(incrementMs = 0, overheadBufferMs = 5, defaultThinkMs = 200).chooseMoves(initialNbk, None)
    assert(moves.nonEmpty)
    assert(legalPaths(initialNbk).contains(moves))

  test("an unusable dfen yields no moves (the server auto-passes)"):
    assertEquals(new Strategy(0, 5, 200).chooseMoves("not-a-fen", Some(1000L)), Nil)
