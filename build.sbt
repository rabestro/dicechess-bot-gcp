ThisBuild / organization := "lv.id.jc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"

ThisBuild / description := "Dice Chess webhook bot in Scala: the engine's Monte-Carlo search on the JVM, containerised for Google Cloud Run."

// Both the engine and the webhook runtime live in GitHub Packages, which requires authentication
// even for public packages (read:packages scope). GitHub Packages' Maven registry is
// per-repository, so each artifact needs its own resolver entry — but both share one host, so the
// single credentials block below covers both.
ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/rabestro/dicechess-engine-scala"
ThisBuild / resolvers += "GitHub Packages (dicechess-bot-runtime)" at
  "https://maven.pkg.github.com/rabestro/dicechess-bot-runtime"

// Credentials for that resolver, evaluated on every load — even for offline tasks — so we keep it
// free of network calls: GitHub Packages validates only the token (the password) and accepts any
// non-empty username. CI exports GITHUB_TOKEN; locally we read it from the gh CLI, which returns
// the token from the OS keychain without touching the network (works offline; never lands in a file).
def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion     = "1.10.4"
val DiceChessBotRuntimeVersion = "0.1.1"
val MunitVersion               = "1.3.4"

lazy val root = (project in file("."))
  .settings(
    name                := "dicechess-bot-gcp",
    Compile / mainClass := Some("dicechess.bot.Main"),
    libraryDependencies ++= Seq(
      // The whole point: the real engine as a dependency — MonteCarloSearch, TimeManager,
      // FenParser, TurnGenerator. Pulls circe transitively (engine's OpeningBookParser).
      "lv.id.jc" %% "dicechess-engine-scala" % DiceChessEngineVersion,
      // Plain `%`, not `%%` — a Java artifact, not cross-built per Scala version: HMAC signing,
      // the ownership handshake, TurnContext, and the JDK HttpServer (CustomHandlerServer).
      "lv.id.jc"      % "dicechess-bot-runtime" % DiceChessBotRuntimeVersion,
      "org.scalameta" %% "munit"                % MunitVersion % Test
    ),
    // One runnable fat jar the Cloud Run container executes. A fixed output path (not the
    // cross-version target dir) keeps the Dockerfile's COPY deterministic.
    assembly / mainClass          := Some("dicechess.bot.Main"),
    assembly / assemblyJarName    := "dicechess-bot-gcp.jar",
    assembly / assemblyOutputPath := target.value / "dicechess-bot-gcp.jar",
    // Pragmatic merge for a single-main fat jar: drop signatures/manifests/module-info, concat
    // service registries, take-first for the rest (no library here needs a smarter policy).
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*)
          if xs.nonEmpty && {
            val n = xs.last.toLowerCase; n.endsWith(".sf") || n.endsWith(".dsa") || n.endsWith(".rsa")
          } =>
        MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
      case PathList("META-INF", "services", _ @ _*)  => MergeStrategy.concat
      case x if x.endsWith("module-info.class")      => MergeStrategy.discard
      case _                                         => MergeStrategy.first
    }
  )
