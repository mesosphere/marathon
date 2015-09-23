package mesosphere.marathon.api.v2

import mesosphere.marathon.core.appinfo.AppSelector
import mesosphere.marathon.state.AppDefinition
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal
import scala.util.parsing.combinator.RegexParsers

case class LabelSelector(key: String, fn: String => Boolean, value: List[String]) extends AppSelector {
  def matches(app: AppDefinition): Boolean = app.labels.contains(key) && fn(app.labels(key))
}

case class LabelSelectors(selectors: Seq[LabelSelector]) extends AppSelector {
  def matches(app: AppDefinition): Boolean = selectors.forall(_.matches(app))
}

/**
  * Parse a label selector query.
  * A label selector query has this format:
  * query: selector {, selector}
  * selector: existenceSelector | setSelector | equalsSelector
  * existenceSelector: label
  * equalsSelector: term ==|!= term
  * setSelector: term in|notin set
  * set: ( term {, term} )
  * term: character sequence with character groups A-Za-z0-9 and characters .-_.
  * Any other character needs to be escaped with backslash.
  *
  * Examples:
  * test == foo
  * test != foo
  * environment in (production, qa)
  * tier notin (frontend, backend)
  * partition
  * \!\!\! in (\-\-\-, \&\&\&, \+\+\+)
  * a in (t1, t2, t3), b notin (t1), c, d
  *
  */
class LabelSelectorParsers extends RegexParsers {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  //Allowed characters are A-Za-z0-9._- All other characters can be used, but need to be escaped.
  def term: Parser[String] = """(\\.|[-A-Za-z0-9_.])+""".r ^^ { _.replaceAll("""\\(.)""", "$1") }

  def existenceSelector: Parser[LabelSelector] = term ^^ {
    case existence: String => LabelSelector(existence, _ => true, List.empty)
  }

  def equalityOp: Parser[String] = """(==|!=)""".r
  def equalitySelector: Parser[LabelSelector] = term ~ equalityOp ~ term ^^ {
    case label ~ "==" ~ value => LabelSelector(label, value == _, List(value))
    case label ~ "!=" ~ value => LabelSelector(label, value != _, List(value))
  }

  def set: Parser[List[String]] = "(" ~> repsep(term, ",") <~ ")"
  def setOp: Parser[String] = """(in|notin)""".r
  def setSelector: Parser[LabelSelector] = term ~ setOp ~ set ^^ {
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

