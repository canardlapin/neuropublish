package neuropublish.ingestion

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import fs2.io.file.Path
import cats.effect.Resource
import neuropublish.backend.{LocalRevisionStore, Server}
import neuropublish.persistence.DbConfig
import scala.concurrent.duration.*

/** The ingestion worker process. Shares the control plane's storage configuration (NP_DATA_DIR and
  * the NP_S3_* variables) and polls the queue every NP_WORKER_POLL (default 2s). Run it beside a
  * backend started with NP_INGESTION=worker: `scripts/worker.sh` or `sbt ingestion/run`.
  */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val env = sys.env
    val data = Path(env.getOrElse("NP_DATA_DIR", "data"))
    val poll = env.get("NP_WORKER_POLL").flatMap(_.toIntOption).getOrElse(2).seconds
    val once = args.contains("--once")
    // Same store selection as the control plane: PostgreSQL when NP_DATABASE_URL is set (queue,
    // revisions, sessions), else local-fs under NP_DATA_DIR.
    val db = DbConfig.fromEnv(env)
    val revisionsR: Resource[IO, neuropublish.backend.RevisionStore] = db match
      case Some(cfg) => Server.Stores.postgres(cfg).map(_.revisions)
      case None => Resource.eval(LocalRevisionStore(data))
    (Server.storage(data, env, db), revisionsR).tupled.use { (st, revisions) =>
      IO.unit.flatMap { _ =>
        val worker = Worker(st.objects, st.renditions, st.queue, revisions)
        IO.println(
          s"neuropublish ingestion worker ${Worker.defaultName}; data $data; objects ${st.describe}; queue ${
              db.fold("local-fs")(_ => "postgresql")
            }; poll $poll"
        ) *>
          (if once then worker.drain(IO.realTimeInstant).void
           else
             worker.drain(IO.realTimeInstant).flatMap(n =>
               if n == 0 then IO.sleep(poll) else IO.unit
             ).foreverM)
      }
    }.as(ExitCode.Success)
