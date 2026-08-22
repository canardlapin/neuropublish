package neuropublish.frontend

import neuropublish.api.*
import neuropublish.protocol.json.*
import neuropublish.rendition.{ScalarSummary, VolumeRendition}
import neuropublish.viewer.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalafim.image.NeuroVol

/** A revision with its renditions decoded: everything the workspace needs, loaded once. */
final case class Loaded(
    workspace: String,
    project: String,
    detail: RevisionDetail,
    manifest: Manifest,
    volumes: Map[String, NeuroVol[Double]],
    summaries: Map[String, ScalarSummary]
):
  /** Result fields that have a volume representation with a ready rendition, in manifest order. */
  def volumeFields: List[ResultField] =
    manifest.resultFields
      .filter(f => f.representations.exists(r => r.kind == "volume" && volumes.contains(r.asset)))
      .sortBy(_.order.getOrElse(Int.MaxValue))
  def assetOf(f: ResultField): String = f.representations.find(_.kind == "volume").get.asset

  /** The producer's recommendation for a field, or a conservative default (axiom 7: never guessed
    * beyond the data range).
    */
  def published(f: ResultField): LayerDisplay =
    val c = f.publishedDisplay.map(_.hcursor)
    val sm = summaries.get(assetOf(f))
    val lo = c.flatMap(
      _.downField("window").downField("min").as[Double].toOption
    ).orElse(sm.map(_.min)).getOrElse(-1.0)
    val hi = c.flatMap(
      _.downField("window").downField("max").as[Double].toOption
    ).orElse(sm.map(_.max)).getOrElse(1.0)
    val thr = c.flatMap(_.downField("threshold").downField("min").as[Double].toOption)
      .map(m => Threshold("two-sided", m)).getOrElse(Threshold("off", 0.0))
    val cmap = c.flatMap(_.downField("colormap").as[String].toOption).getOrElse("cold-hot")
    val visibleByDefault = f.measure.endsWith("/t-statistic") || f.measure.endsWith("/z-statistic")
    LayerDisplay(
      visible = visibleByDefault,
      opacity = 0.85,
      window = Window(math.min(lo, hi - 1e-9), hi),
      threshold = thr,
      colormap = cmap
    )

  def initialWorkspace: Workspace =
    Workspace(
      volumeFields.map(f => WorkspaceLayer(f.id, published(f), published(f))).toVector,
      None,
      WorkspaceLayout.default,
      "layers"
    )

object Loaded:
  def load(api: Api, ws: String, project: String, revisionId: Option[String]): Future[Loaded] =
    for
      id <- revisionId.fold(api.project(ws, project).map(
        _.head.getOrElse(throw RuntimeException("This project has no revisions yet."))
      ))(Future.successful)
      detail <- api.revision(id)
      manifest <- Future.fromTry(detail.manifest.as[Manifest].toTry)
      ready = detail.renditions.filter(_.status == "ready")
      vols <- Future.traverse(ready) { r =>
        api.rendition(r).map { (hdr, bytes) =>
          val h = VolumeRendition.decodeHeader(hdr).fold(
            m => throw RuntimeException(s"${r.assetId}: $m"),
            identity
          )
          val v = VolumeRendition.decode(
            h,
            bytes
          ).fold(m => throw RuntimeException(s"${r.assetId}: $m"), identity)
          (r.assetId, v, h.summary)
        }
      }
    yield Loaded(
      ws,
      project,
      detail,
      manifest,
      vols.map(t => t._1 -> t._2).toMap,
      vols.flatMap(t => t._3.map(t._1 -> _)).toMap
    )
