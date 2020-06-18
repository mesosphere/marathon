package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition}

class LabelSelectorParsersTest extends UnitTest {

  "LabelSelectorParsers" should {
    "A valid existence label query can be parsed" in {
      val parser = new LabelSelectorParsers
      val existence = parser.parsed("existence")

      existence.selectors should have size 1
      existence.selectors.head.key should be("existence")
      existence.selectors.head.value should have size 0
      existence.matches(AppDefinition(id = runSpecId, labels = Map("existence" -> "one"), role = "*")) should be(true)
      existence.matches(AppDefinition(id = runSpecId, labels = Map("none" -> "one"), role = "*")) should be(false)
    }

    "A valid label equals query can be parsed" in {
      val parser = new LabelSelectorParsers
      val in = parser.parsed("foo == one")
      val in2 = parser.parsed("foo==one")

      in.selectors should have size 1
      in.selectors.head.key should be("foo")
      in.selectors.head.value should be(List("one"))
      in2.selectors.head.value should be(List("one"))
      in.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "one"), role = "*")) should be(true)
      in.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "four"), role = "*")) should be(false)
      in.matches(AppDefinition(id = runSpecId, labels = Map("bla" -> "one"), role = "*")) should be(false)
    }

    "A valid label not equals query can be parsed" in {
      val parser = new LabelSelectorParsers
      val in = parser.parsed("foo != one")

      in.selectors should have size 1
      in.selectors.head.key should be("foo")
      in.selectors.head.value should be(List("one"))
      in.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "one"), role = "*")) should be(false)
      in.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "four"), role = "*")) should be(true)
      in.matches(AppDefinition(id = runSpecId, labels = Map("bla" -> "one"), role = "*")) should be(false)
    }

    "A valid label set query can be parsed" in {
      val parser = new LabelSelectorParsers
      val in = parser.parsed("""foo in (one, two, three\ is\ cool)""")

      in.selectors should have size 1
      in.selectors.head.key should be("foo")
      in.selectors.head.value should be(List("one", "two", "three is cool"))
      in.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "one"), role = "*")) should be(true)
      in.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "four"), role = "*")) should be(false)
      in.matches(AppDefinition(id = runSpecId, labels = Map("bla" -> "one"), role = "*")) should be(false)
    }

    "A valid notin label set query can be parsed" in {
      val parser = new LabelSelectorParsers
      val notin = parser.parsed("bla notin (one, two, three)")
      notin.selectors should have size 1
      notin.selectors.head.key should be("bla")
      notin.selectors.head.value should be(List("one", "two", "three"))
      notin.matches(AppDefinition(id = runSpecId, labels = Map("bla" -> "one"), role = "*")) should be(false)
      notin.matches(AppDefinition(id = runSpecId, labels = Map("bla" -> "four"), role = "*")) should be(true)
      notin.matches(AppDefinition(id = runSpecId, labels = Map("rest" -> "one"), role = "*")) should be(false)
    }

    "A valid combined label query can be parsed" in {
      val parser = new LabelSelectorParsers
      val combined = parser.parsed("foo==one, bla!=one, foo in (one, two, three), bla notin (one, two, three), existence")
      combined.selectors should have size 5
      combined.matches(
        AppDefinition(id = runSpecId, labels = Map("foo" -> "one", "bla" -> "four", "existence" -> "true"), role = "*")
      ) should be(true)
      combined.matches(AppDefinition(id = runSpecId, labels = Map("foo" -> "one"), role = "*")) should be(false)
      combined.matches(AppDefinition(id = runSpecId, labels = Map("bla" -> "four"), role = "*")) should be(false)
    }

    "A valid combined label query without alphanumeric characters can be parsed" in {
      val parser = new LabelSelectorParsers
      val combined = parser.parsed("""\{\{\{ in (\*\*\*, \&\&\&, \$\$\$), \^\^\^ notin (\-\-\-, \!\!\!, \@\@\@), \#\#\#""")
      combined.selectors should have size 3
      combined.matches(AppDefinition(id = runSpecId, labels = Map("{{{" -> "&&&", "^^^" -> "&&&", "###" -> "&&&"), role = "*")) should be(
        true
      )
      combined.matches(AppDefinition(id = runSpecId, labels = Map("^^^" -> "---"), role = "*")) should be(false)
      combined.matches(AppDefinition(id = runSpecId, labels = Map("###" -> "four"), role = "*")) should be(false)
    }

    "An invalid combined label query can not be parsed" in {
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
  val runSpecId = AbsolutePathId("/test")
}
