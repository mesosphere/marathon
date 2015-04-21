package mesosphere.marathon.api.v2

import mesosphere.marathon.state.AppDefinition

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
  * ExistenceSelector: Id
  * SetSelector: Label in|notin Set
  * EqualsSelector: Label ==|!= Ident
  * Set: ( Ident {, Ident} )
  * Label: any character without whitespace
  * Ident: any character without , or )
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

  def label: Parser[String] = """[^\s!=]+""".r
  def eqOp: Parser[String] = """(==|!=)""".r
  def setOp: Parser[String] = """(in|notin)""".r
  def ident: Parser[String] = """[^,)=]+""".r

  def list: Parser[List[String]] = "(" ~> repsep(ident, ",") <~ ")"

  def existenceSelector: Parser[LabelSelector] = label ^^ {
    case existence => LabelSelector(existence, _ => true, List.empty)
  }

  def equalitySelector: Parser[LabelSelector] = label ~ eqOp ~ ident ^^ {
    case label ~ "==" ~ ident => LabelSelector(label, ident == _, List(ident))
    case label ~ "!=" ~ ident => LabelSelector(label, ident != _, List(ident))
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
        case Failure(message, _)   => Left(message)
        case Error(message, _)     => Left(message)
        case NoSuccess(message, _) => Left(message)
      }
    }
    catch {
      case NonFatal(ex) => Left(ex.getMessage)
    }
  }

  def parsed(in: String): LabelSelectors = parseSelectors(in) match {
    case Left(message)    => throw new IllegalArgumentException(s"Can not parse label selector $in. Reason: $message")
    case Right(selectors) => selectors
  }
}

