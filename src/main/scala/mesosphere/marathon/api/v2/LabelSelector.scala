package mesosphere.marathon.api.v2

import mesosphere.marathon.state.AppDefinition
import org.apache.log4j.Logger

import scala.util.control.NonFatal
import scala.util.parsing.combinator.RegexParsers

case class LabelSelector(key: String, fn: String => Boolean, value: List[String]) {
  def matches(app: AppDefinition): Boolean = app.labels.contains(key) && fn(app.labels(key))

}
case class LabelSelectors(selectors: Seq[LabelSelector]) {
  def matches(app: AppDefinition): Boolean = selectors.forall(_.matches(app))
}

/**
  * Parse a label selector query.
  * A label selector query has this format:
  * Query: Selector {, Selector}
  * Selector: ExistenceSelector | SetSelector | EqualsSelector
  * ExistenceSelector: Label
  * SetSelector: Label in|notin Set
  * EqualsSelector: Label ==|!= LabelValue
  * Set: ( LabelValue {, LabelValue} )
  * Label: any character without whitespace
  * LabelValue: any character without , or )
  *
  * Examples:
  * test == foo
  * test != foo
  * environment in (production, qa)
  * tier notin (frontend, backend)
  * partition
  * !!! in (---, &&&, +++)
  * a in (t1, t2, t3), b notin (t1), c, d
  *
  */
class LabelSelectorParsers extends RegexParsers {

  private[this] val log = Logger.getLogger(getClass.getName)

  def label: Parser[String] = """[^\s!=]+""".r
  def eqOp: Parser[String] = """(==|!=)""".r
  def setOp: Parser[String] = """(in|notin)""".r
  def labelValue: Parser[String] = """[^,)=]+""".r

  def list: Parser[List[String]] = "(" ~> repsep(labelValue, ",") <~ ")"

  def existenceSelector: Parser[LabelSelector] = label ^^ {
    case existence => LabelSelector(existence, _ => true, List.empty)
  }

  def equalitySelector: Parser[LabelSelector] = label ~ eqOp ~ labelValue ^^ {
    case label ~ "==" ~ value => LabelSelector(label, value == _, List(value))
    case label ~ "!=" ~ value => LabelSelector(label, value != _, List(value))
  }

  def setSelector: Parser[LabelSelector] = label ~ setOp ~ list ^^ {
    case label ~ "in" ~ set    => LabelSelector(label, set.contains, set)
    case label ~ "notin" ~ set => LabelSelector(label, !set.contains(_), set)
  }

  def selector: Parser[LabelSelector] = setSelector | equalitySelector | existenceSelector

  def selectors: Parser[List[LabelSelector]] = repsep(selector, ",")

  def parseSelectors(in: String): Either[String, LabelSelectors] = {
    try {
      parseAll(selectors, in) match {
        case Success(selectors, _) => Right(LabelSelectors(selectors))
        case NoSuccess(message, _) => Left(message)
      }
    }
    catch {
      case NonFatal(ex) =>
        log.warn(s"Could not parse $in", ex)
        Left(ex.getMessage)
    }
  }

  def parsed(in: String): LabelSelectors = parseSelectors(in) match {
    case Left(message)    => throw new IllegalArgumentException(s"Can not parse label selector $in. Reason: $message")
    case Right(selectors) => selectors
  }
}

