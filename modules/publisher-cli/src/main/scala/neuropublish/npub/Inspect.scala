package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.protocol.Measures
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.*

/** `npub inspect <bundle>`: what a reviewer would read first — title, core, the analyses /
  * estimands / fields tree in normative order, assets, warnings, open records with how this build
  * interprets them, and every admission problem. Inspection never stops at the first problem: the
  * structural projection is printed whenever it decodes, problems last.
  */
object Inspect:
  def run(dir: Path, out: String => IO[Unit]): IO[ExitCode] =
    Files[IO].readAll(dir / "manifest.json").compile.to(Array).flatMap { bytes =>
      val (lines, ok) = render(bytes)
      lines.traverse_(out).as(if ok then ExitCode.Success else ExitCode.Error)
    }

  private def short(d: Sha256): String = "sha256:" + d.hex.take(12) + "…"

  /** Lines to print and whether the bundle is admissible. */
  def render(bytes: Array[Byte]): (List[String], Boolean) =
    val admitted = Manifest.parse(bytes)
    val problems = admitted.left.getOrElse(Nil)
    // the projection, even when admission failed, so problems can be read in context
    val projection: Option[Manifest] = admitted.toOption.map(_._2).orElse(
      _root_.io.circe.parser.parse(new String(bytes, "UTF-8")).toOption
        .flatMap(j => Migrations.bring(j).toOption).flatMap(_.as[Manifest].toOption)
    )
    val digest = admitted.toOption.map(_._1).orElse(ByteProfile.admit(bytes).toOption)
    val head = projection.toList.flatMap(m => describe(m, digest))
    val tail =
      if problems.isEmpty then List(s"problems  none")
      else s"problems  ${problems.length}" :: problems.map(p => s"error  ${p.render}")
    (head ++ tail, problems.isEmpty)

  private def describe(m: Manifest, digest: Option[Sha256]): List[String] =
    val b = List.newBuilder[String]
    b += s"title      ${m.title}"
    b += s"core       ${m.core}${m.migratedFrom.map(v => s" (migrated from $v)").getOrElse("")}"
    digest.foreach(d => b += s"digest     ${d.render}")
    b += s"sensitivity ${m.sensitivity.getOrElse("(missing)")}"
    m.synopsis.foreach(s => b += s"synopsis   $s")

    b += s"analyses   ${m.analyses.length}"
    m.analyses.foreach { a =>
      val n = a.sampleSize.map(n => s"  n=$n").getOrElse("")
      val method = a.method.flatMap(_.as[OpenRecord].toOption)
        .map(r => s"  method ${r.schema.render}").getOrElse("")
      b += s"  ${a.id}  ${a.label}$n$method"
      m.orderedEstimands(a).foreach { e =>
        b += s"    ${e.id}  ${e.label}"
        m.orderedFields(e.id).foreach { f =>
          val reps = f.representations.map(r => s"${r.kind}:${r.asset}").mkString(", ")
          val label = f.label.filter(_.trim.nonEmpty).map(l => s"$l  ").getOrElse("")
          val measure = Measures.lookup(f.measure)
            .map(x => s"${x.label} (${x.short})").getOrElse(s"${f.measure} (unknown measure)")
          val display = if f.publishedDisplay.isDefined then "  display recommended" else ""
          b += s"      ${f.id}  $label$measure  $reps  domain ${f.domain}$display"
        }
      }
    }
    val orphans =
      m.resultFields.filterNot(f => m.analyses.exists(_.estimands.exists(_.id == f.estimand)))
    if orphans.nonEmpty then
      b += s"  (fields without a declared estimand: ${orphans.map(_.id).mkString(", ")})"

    b += s"domains    ${m.domains.length}"
    m.domains.foreach(d => b += s"  ${d.id}  ${d.descriptor.schema.render}")

    b += s"assets     ${m.assets.length}  ${m.assets.map(_.size).sum} bytes"
    m.assets.foreach { a =>
      val cat = a.catalog.map(c => s"  catalog $c").getOrElse("")
      b += s"  ${a.id}  ${a.size} B  ${a.mediaType}  ${short(a.digest)}$cat"
    }
    m.underlays.foreach(u => b += s"  underlay ${u.asset}  ${u.label}  domain ${u.domain}")

    b += s"warnings   ${m.warnings.length}"
    m.warnings.foreach { w =>
      val scope = w.concerns.flatMap(_.asObject).map(o =>
        o.toList.flatMap((k, v) => v.asString.map(s => s"$k $s")).mkString("(", ", ", ")") + "  "
      ).getOrElse("")
      b += s"  ${w.id}  $scope${w.message}"
    }

    val records = m.openRecords
    b += s"records    ${records.length}"
    records.foreach { (p, r, i) =>
      val how = i match
        case Interpretation.Understood(_, note) => note
        case Interpretation.Unsupported(_) => "unsupported: retained, shown generically"
        case Interpretation.Invalid(_, ps) => s"invalid: ${Problem.render(ps)}"
      b += s"  $p  ${r.schema.render}  $how"
    }
    b.result()
