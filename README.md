# Dice Chess bot — Google Cloud Run (engine-powered, Monte-Carlo)

[![CI](https://github.com/rabestro/dicechess-bot-gcp/actions/workflows/ci.yml/badge.svg)](https://github.com/rabestro/dicechess-bot-gcp/actions/workflows/ci.yml)
[![Play Live](https://img.shields.io/badge/Play-Live-success)](https://play.jc.id.lv/)
[![Leaderboard](https://img.shields.io/badge/Ladder-Leaderboard-1E90FF)](https://play.jc.id.lv/leaderboard)
[![Engine](https://img.shields.io/badge/Engine-dicechess--engine--scala-8A2BE2)](https://github.com/rabestro/dicechess-engine-scala)
[![Bot API](https://img.shields.io/badge/Docs-Bot%20API-orange)](https://jc.id.lv/dicechess-play-api/)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

The live [`gcp/scala-monte-carlo`](https://play.jc.id.lv/leaderboard) ladder bot: a Dice Chess
webhook bot in **Scala 3** that links the **real game engine** —
[`dicechess-engine-scala`](https://github.com/rabestro/dicechess-engine-scala) — and plays its
**Monte-Carlo** search, running on the JVM in a container on **Google Cloud Run**.

This is the runtime for the algorithm that didn't fit the others. Monte-Carlo estimates each turn's
win probability by Rao-Blackwellized rollouts; its strength scales with the number of rollouts,
which scales with CPU time. That's too heavy for Cloudflare Workers' per-request CPU budget, and a
poor match for a GraalVM-native cold-start bot — but exactly what a JVM on a real request timeout
delivers.

**One engine, opposite runtime trade-offs.** The [`azure/scala-aggressive-book`](https://github.com/rabestro/dicechess-bot-azure)
bot compiles the same engine to a **GraalVM native image** optimised for *cold start* and plays a
one-ply aggressive search that needs no clock. This bot runs the same engine on the **JVM**,
optimised for *sustained throughput*, and plays a rollout-hungry search that genuinely wants time.
Same dependency, different platform, different algorithm — chosen to fit each.

## Time management

Monte-Carlo strength ∝ rollouts ∝ wall-clock budget, so the one real decision each turn is *how
long to think*. The bot does **not** invent a policy — it delegates to the engine's own
[`TimeManager`](https://github.com/rabestro/dicechess-engine-scala): build a `ClockState` from the
turn's remaining clock, get back a per-turn budget (a reserve-protected, increment-aware,
phase-tapered fraction, hard-capped so a single turn can never flag the clock), and run the search
to that deadline. If the deadline elapses before any rollout finishes, the best material candidate
is returned — a legal move always comes back.

Two knobs, both with safe defaults:

| Env var | Default | Meaning |
| --- | --- | --- |
| `LADDER_INCREMENT_MS` | `0` | Fischer increment in ms. Not on the webhook wire yet ([dicechess-bot-runtime#7](https://github.com/rabestro/dicechess-bot-runtime/issues/7)); `0` is sudden-death budgeting — safe (slightly under-thinks, never flags). Set `3000` for the 300+3 ladder. |
| `OVERHEAD_BUFFER_MS` | `300` | Slack subtracted from the budget for the play-api↔Cloud Run round-trip plus one uninterruptible rollout. |
| `DEFAULT_THINK_MS` | `2000` | Per-turn deadline when there is no clock to manage (unlimited control), so a lobby game against a human stays responsive. |

## Why the JVM, not a native image

The webhook is single-attempt with a hard budget, so cold start matters — but here the game clock
absorbs it: the ladder's Fischer 300+3 leaves ~300 s on the first move, comfortably more than a JVM
cold start, and with **scale-to-zero** a cold start happens only on the first turn after idle, then
the instance stays warm for the game. In exchange the JVM's JIT gives markedly higher steady-state
rollout throughput than a native image — and rollouts/second *is* Monte-Carlo's playing strength.
The container is also simpler: no native-image build, no reflection config.

## Licensing

**AGPL-3.0**, because it links the AGPL engine. Forks and experiments are welcome — derived bots
stay AGPL. If you want a **closed-source** bot, the legal moves are already on the wire: fork a
transport-only MIT starter ([Scala](https://github.com/rabestro/dicechess-bot-scala),
[TypeScript](https://github.com/rabestro/dicechess-bot-typescript),
[Python](https://github.com/rabestro/dicechess-bot-python)) and no engine linkage is ever required.
See [Licensing for Bots](https://jc.id.lv/dicechess-play-api/licensing/).

## Layout

| Path | Role |
| --- | --- |
| `src/main/scala/dicechess/bot/Strategy.scala` | Monte-Carlo search driven by the engine's `TimeManager`; DFEN in, UCI path out. **Swap the algorithm here.** |
| `src/main/scala/dicechess/bot/Main.scala` | Wires `Strategy` into [`dicechess-bot-runtime`](https://github.com/rabestro/dicechess-bot-runtime)'s `WebhookHandler`/`CustomHandlerServer`, binding Cloud Run's `$PORT`. |
| `Dockerfile` | Runtime-only image (`eclipse-temurin:25-jre`) over the `sbt assembly` fat jar. |

HMAC verification, the ownership handshake, `TurnContext`, and the JDK `HttpServer` itself are
[`dicechess-bot-runtime`](https://github.com/rabestro/dicechess-bot-runtime)
(`lv.id.jc:dicechess-bot-runtime`) — the same dependency a Java or Kotlin bot would use. `Main.scala`
is the entire integration: adapt `Strategy.chooseMoves` to the library's
`Function<TurnContext, List<String>>` shape and start the server.

## Local development

Requires JDK 25+ and sbt; resolving the engine + runtime needs a GitHub token with `read:packages`
(`gh auth login` is enough — the build reads `gh auth token`).

```bash
sbt test          # hermetic: legality via the engine + one real-HTTP round trip through the library
sbt run           # serves on :8080; then e.g.:
curl -X POST localhost:8080/api/webhook -d '{"type":"verification","nonce":"x"}'
```

## Deploy to Cloud Run

```bash
# 1. Build the fat jar (needs the read:packages token).
sbt assembly

# 2. Build and push an amd64 image (Cloud Run does not run arm64 images — the --platform flag
#    matters on Apple Silicon). Assumes an Artifact Registry Docker repo created once.
REGION=us-central1   # always-free tier is US-only: us-central1 / us-east1 / us-west1
PROJECT=$(gcloud config get-value project)
REPO=dicechess
IMAGE=$REGION-docker.pkg.dev/$PROJECT/$REPO/dicechess-bot-gcp:latest
docker build --platform linux/amd64 -t "$IMAGE" .
docker push "$IMAGE"

# 3. Deploy. Scale-to-zero (the default min-instances 0) keeps it inside the always-free tier.
gcloud run deploy dicechess-bot-gcp \
  --image "$IMAGE" --region "$REGION" \
  --allow-unauthenticated --cpu 1 --memory 512Mi --min-instances 0
```

`gcloud run deploy` prints the service's public HTTPS URL — that plus `/api/webhook` is the webhook
endpoint. Then the platform-side steps (any HTTP client; `curl` shown):

```bash
BASE=https://play-api.jc.id.lv
URL=https://<the-cloud-run-url>/api/webhook

# 1. Claim a durable identity. Token shown ONCE.
curl -X POST "$BASE/bot/register" -H "Content-Type: application/json" \
  -d '{"team":"gcp","name":"scala-monte-carlo"}'

# 2. Register the webhook (the deployed service must already answer — ownership handshake).
#    The response carries the signing secret, shown ONCE.
curl -X POST "$BASE/bot/webhook" -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" -d "{\"url\":\"$URL\"}"

# 3. Give the service its secret + the ladder increment (the update restarts the service).
#    --update-env-vars adds without clearing others. Secret Manager (--update-secrets) is the
#    hardened alternative to a plaintext env var.
gcloud run services update dicechess-bot-gcp --region "$REGION" \
  --update-env-vars DICECHESS_WEBHOOK_SECRET=<secret>,LADDER_INCREMENT_MS=3000

# 4. Join the rating ladder — passive from here; watch /bots/gcp/scala-monte-carlo converge.
curl -X POST "$BASE/bot/ladder/join" -H "Authorization: Bearer <token>"
```

Before step 4 you can [play against it yourself](https://jc.id.lv/dicechess-play-api/play-your-bot/)
from the lobby to confirm it plays a legal game. Full platform reference:
<https://jc.id.lv/dicechess-play-api/>.

## Does it fit the free tier?

Cloud Run's always-free tier is **2M requests, 180,000 vCPU-seconds (≈ 50 vCPU-hours), and 360,000
GiB-seconds per month** (select US regions). With scale-to-zero the binding limit is vCPU-seconds,
not requests — and a demo bot playing ladder games uses a tiny fraction of 50 vCPU-hours. A billing
account (card on file) is required even for always-free usage; usage within the limits is not
charged.
