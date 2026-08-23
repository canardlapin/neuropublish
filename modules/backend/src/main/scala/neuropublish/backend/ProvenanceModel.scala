package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Json
import neuropublish.api.*
import neuropublish.api.Stage4.given

/** The provenance read model: a DAG over the manifest's `provenance.entities` and `.activities`,
  * plus compatibility facets across receipts that share the fmrireg analysis-receipt schema.
  * Heterogeneous inputs are summarized as value groups and never as one invented shared setting
  * (Stage 4 exit criterion). Unknown schemas are kept verbatim and marked `unsupported`. Cached per
  * revision under `<data>/provenance/<rev>.json`; the cache is a pure function of the manifest.
  */
object ProvenanceModel:
  val ReceiptSchema = "org.bbuchsbaum.fmrireg/analysis-receipt"
  private val UnderstoodNamespaces = List("org.neuropublish.", "org.bbuchsbaum.")
  private val NonFacetKeys = Set("subject")

  def interpretation(schemaId: String): String =
    if UnderstoodNamespaces.exists(schemaId.startsWith) then "understood" else "unsupported"

  def compute(revision: String, manifest: Json): Provenance =
    val prov = manifest.hcursor.downField("provenance")
    def str(j: Json, k: String) = j.hcursor.get[String](k).toOption
    val assets = manifest.hcursor.downField("assets").as[List[Json]].getOrElse(Nil)
      .flatMap(a => str(a, "id").map(_ -> a)).toMap

    val entities = prov.downField("entities").as[List[Json]].getOrElse(Nil).flatMap { e =>
      str(e, "id").map(id =>
        ProvenanceNode(
          id,
          "entity",
          str(e, "label").getOrElse(id),
          None,
          None,
          "understood",
          e,
          e.hcursor.get[Boolean]("hosted").toOption
        )
      )
    }
    val activitiesJson = prov.downField("activities").as[List[Json]].getOrElse(Nil)
    val activities = activitiesJson.flatMap { a =>
      str(a, "id").map { id =>
        val schema = a.hcursor.downField("schema")
        val sid = schema.get[String]("id").toOption
        ProvenanceNode(
          id,
          "activity",
          str(a, "label").getOrElse(id),
          sid,
          schema.get[String]("version").toOption,
          sid.fold("unsupported")(interpretation),
          a,
          None
        )
      }
    }
    val edges = prov.downField("edges").as[List[Json]].getOrElse(Nil).flatMap(e =>
      (str(e, "from"), str(e, "to")).mapN(ProvenanceEdge.apply)
    )
    // edge endpoints that name manifest assets (outputs) become asset nodes so the DAG is closed
    val known = (entities ++ activities).map(_.id).toSet
    val assetNodes = edges.flatMap(e => List(e.from, e.to)).distinct.filterNot(known)
      .flatMap(id =>
        assets.get(id).map(a =>
          ProvenanceNode(
            id,
            "asset",
            str(a, "label").getOrElse(id),
            None,
            None,
            "understood",
            a,
            Some(true)
          )
        )
      )
    val receipts = activitiesJson.filter(a =>
      a.hcursor.downField("schema").get[String]("id").toOption.contains(ReceiptSchema)
    ).flatMap(a =>
      str(a, "id").map(id => id -> a.hcursor.downField("payload").focus.getOrElse(Json.Null))
    )

    val facets =
      if receipts.isEmpty then Nil
      else
        val keys = receipts.flatMap(_._2.asObject.toList.flatMap(_.keys)).distinct.sorted
          .filterNot(NonFacetKeys)
        keys.map { k =>
          // a receipt lacking the key is its own group (value null): the facet is not shared
          val byValue =
            receipts.groupMap((_, p) => p.hcursor.downField(k).focus.getOrElse(Json.Null))(_._1)
          val groups = byValue.toList.map((v, ms) => FacetGroup(v, ms.size, ms.sorted))
            .sortBy(g => (-g.count, g.value.noSpaces))
          CompatibilityFacet(k, shared = groups.size == 1, groups)
        }
    val warnings = prov.downField("warnings").as[List[Json]].getOrElse(Nil)
    Provenance(
      revision,
      entities ++ activities ++ assetNodes,
      edges,
      Option.when(receipts.nonEmpty)(ReceiptSchema),
      receipts.size,
      facets,
      warnings
    )

  /** Cached read: computed from the manifest on first request. */
  def cached(root: Path, revision: String, manifest: => IO[Json]): IO[Provenance] =
    val p = root / "provenance" / s"$revision.json"
    JsonFiles.read[Provenance](p).flatMap {
      case Some(pr) => IO.pure(pr)
      case None => manifest.map(compute(revision, _)).flatTap(JsonFiles.write(p, _))
    }
