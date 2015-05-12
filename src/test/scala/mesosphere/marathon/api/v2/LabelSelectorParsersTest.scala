package mesosphere.marathon.api.v2

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.AppDefinition
import org.scalatest.Matchers

class LabelSelectorParsersTest extends MarathonSpec with Matchers {

  test("A valid existence label query can be parsed") {
    val parser = new LabelSelectorParsers
    val existence = parser.parsed("existence")

    existence.selectors should have size 1
    existence.selectors.head.key should be("existence")
    existence.selectors.head.value should have size 0
    existence.matches(AppDefinition(labels = Map("existence" -> "one"))) should be (true)
    existence.matches(AppDefinition(labels = Map("none" -> "one"))) should be (false)
  }

  test("A valid label equals query can be parsed") {
    val parser = new LabelSelectorParsers
    val in = parser.parsed("foo == one")
    val in2 = parser.parsed("foo==one")

    in.selectors should have size 1
    in.selectors.head.key should be("foo")
    in.selectors.head.value should be (List("one"))
    in2.selectors.head.value should be (List("one"))
    in.matches(AppDefinition(labels = Map("foo" -> "one"))) should be (true)
    in.matches(AppDefinition(labels = Map("foo" -> "four"))) should be (false)
    in.matches(AppDefinition(labels = Map("bla" -> "one"))) should be (false)
  }

  test("A valid label not equals query can be parsed") {
    val parser = new LabelSelectorParsers
    val in = parser.parsed("foo != one")
    val in2 = parser.parsed("foo!=one")

    in.selectors should have size 1
    in.selectors.head.key should be("foo")
    in.selectors.head.value should be (List("one"))
    in.matches(AppDefinition(labels = Map("foo" -> "one"))) should be (false)
    in.matches(AppDefinition(labels = Map("foo" -> "four"))) should be (true)
    in.matches(AppDefinition(labels = Map("bla" -> "one"))) should be (false)
  }

  test("A valid label set query can be parsed") {
    val parser = new LabelSelectorParsers
    val in = parser.parsed("""foo in (one, two, three\ is\ cool)""")

    in.selectors should have size 1
    in.selectors.head.key should be("foo")
    in.selectors.head.value should be (List("one", "two", "three is cool"))
    in.matches(AppDefinition(labels = Map("foo" -> "one"))) should be (true)
    in.matches(AppDefinition(labels = Map("foo" -> "four"))) should be (false)
    in.matches(AppDefinition(labels = Map("bla" -> "one"))) should be (false)
  }

  test("A valid notin label set query can be parsed") {
    val parser = new LabelSelectorParsers
    val notin = parser.parsed("bla notin (one, two, three)")
    notin.selectors should have size 1
    notin.selectors.head.key should be("bla")
    notin.selectors.head.value should be (List("one", "two", "three"))
    notin.matches(AppDefinition(labels = Map("bla" -> "one"))) should be (false)
    notin.matches(AppDefinition(labels = Map("bla" -> "four"))) should be (true)
    notin.matches(AppDefinition(labels = Map("rest" -> "one"))) should be (false)
  }

  test("A valid combined label query can be parsed") {
    val parser = new LabelSelectorParsers
    val combined = parser.parsed("foo==one, bla!=one, foo in (one, two, three), bla notin (one, two, three), existence")
    combined.selectors should have size 5
    combined.matches(AppDefinition(labels = Map("foo" -> "one", "bla" -> "four", "existence" -> "true"))) should be (true)
    combined.matches(AppDefinition(labels = Map("foo" -> "one"))) should be (false)
    combined.matches(AppDefinition(labels = Map("bla" -> "four"))) should be (false)
  }

  test("A valid combined label query without alphanumeric characters can be parsed") {
    val parser = new LabelSelectorParsers
    val combined = parser.parsed("""\{\{\{ in (\*\*\*, \&\&\&, \$\$\$), \^\^\^ notin (\-\-\-, \!\!\!, \@\@\@), \#\#\#""")
    combined.selectors should have size 3
    combined.matches(AppDefinition(labels = Map("{{{" -> "&&&", "^^^" -> "&&&", "###" -> "&&&"))) should be (true)
    combined.matches(AppDefinition(labels = Map("^^^" -> "---"))) should be (false)
    combined.matches(AppDefinition(labels = Map("###" -> "four"))) should be (false)
  }

  test("An invalid combined label query can not be parsed") {
    intercept[IllegalArgumentException] {
      new LabelSelectorParsers().parsed("foo some (one, two, three)")
    }
    intercept[IllegalArgumentException] {
      new LabelSelectorParsers().parsed("foo in one")
    }
    intercept[IllegalArgumentException] {
      new LabelSelectorParsers().parsed("foo test")
    }
  }
}
