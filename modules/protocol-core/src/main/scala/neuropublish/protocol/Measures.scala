package neuropublish.protocol

/** The built-in trusted measure module (plan decision 6): the core measures the MVP result tree
  * needs, so no external semantic module is required to label a basic result. Ids are
  * `org.neuropublish.measure/<name>`.
  */
object Measures:
  val Namespace = "org.neuropublish.measure"

  final case class Measure(
      id: SemanticId,
      label: String,
      short: String,
      signed: Boolean,
      inferential: Boolean
  )

  private def m(name: String, label: String, short: String, signed: Boolean, inferential: Boolean) =
    Measure(SemanticId.parse(s"$Namespace/$name").toOption.get, label, short, signed, inferential)

  val effect = m("effect", "effect", "β", signed = true, inferential = false)
  val standardError =
    m("standard-error", "standard error", "SE", signed = false, inferential = false)
  val tStatistic = m("t-statistic", "t statistic", "t", signed = true, inferential = true)
  val zStatistic = m("z-statistic", "z statistic", "z", signed = true, inferential = true)
  val pValue = m("p-value", "p value", "p", signed = false, inferential = true)
  val accuracy = m("accuracy", "accuracy", "acc", signed = false, inferential = false)
  val correlation = m("correlation", "correlation", "r", signed = true, inferential = false)

  val all: List[Measure] =
    List(effect, standardError, tStatistic, zStatistic, pValue, accuracy, correlation)
  private val byId = all.map(x => x.id.value -> x).toMap

  /** Known measure, or None: unknown measures are shown by their raw id, never guessed (axiom 7).
    */
  def lookup(id: String): Option[Measure] = byId.get(id)
  def label(id: String): String = lookup(id).map(_.label).getOrElse(id)
  def short(id: String): String = lookup(id).map(_.short).getOrElse(id.split('/').last)
