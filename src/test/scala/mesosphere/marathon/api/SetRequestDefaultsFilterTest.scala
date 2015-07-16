package mesosphere.marathon.api

import java.util
import javax.servlet.FilterChain
import javax.servlet.http.{ HttpServletRequestWrapper, HttpServletRequest, HttpServletResponse }

import mesosphere.marathon.MarathonSpec
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.Matchers

import scala.collection.JavaConverters._

class SetRequestDefaultsFilterTest extends MarathonSpec with Matchers {

  var filter: SetRequestDefaultsFilter = _
  var response: HttpServletResponse = _
  var chain: FilterChain = _

  before {
    filter = new SetRequestDefaultsFilter()
    response = mock[HttpServletResponse]("response")
    chain = mock[FilterChain]("chain")

  }
  private[this] def enum(values: String*) = new util.Vector(values.asJava).elements()

  test("set default Content-Type and Accept") {
    val request = mock[HttpServletRequest]("request")

    when(request.getHeaderNames).thenReturn(new util.Vector[String]().elements())

    // when doFilter is called
    filter.doFilter(request, response, chain)

    val requestCaptor = ArgumentCaptor.forClass(classOf[HttpServletRequest])
    val responseCaptor = ArgumentCaptor.forClass(classOf[HttpServletResponse])

    verify(chain, times(1)).doFilter(requestCaptor.capture(), responseCaptor.capture())

    // we set the default headers
    val modifiedRequest = requestCaptor.getValue
    assert(modifiedRequest.getHeader("Content-Type") == "application/json")
    assert(modifiedRequest.getHeader("Accept") == "application/json")

    // the response is left untouched
    verifyNoMoreInteractions(response)
    assert(responseCaptor.getValue == response)
  }

  test("don't overwrite existing headers") {
    val request = new HttpServletRequestWrapper(mock[HttpServletRequest]) {
      val headers = Map(
        "Content-Type" -> enum("application/text"),
        "Accept" -> enum("application/text")
      ).withDefaultValue(null)

      override val getHeaderNames: util.Enumeration[String] = new util.Vector(headers.keySet.asJava).elements()
      override def getHeader(name: String): String = headers.get(name).map(e => e.nextElement()).orNull
      override def getHeaders(name: String): util.Enumeration[String] = headers(name)
    }

    // when doFilter is called
    filter.doFilter(request, response, chain)

    val requestCaptor = ArgumentCaptor.forClass(classOf[HttpServletRequest])
    val responseCaptor = ArgumentCaptor.forClass(classOf[HttpServletResponse])

    verify(chain, times(1)).doFilter(requestCaptor.capture(), responseCaptor.capture())

    // we set the default headers
    val modifiedRequest = requestCaptor.getValue
    assert(modifiedRequest.getHeader("Content-Type") == "application/text")
    assert(modifiedRequest.getHeader("Accept") == "application/text")

    // the response is left untouched
    verifyNoMoreInteractions(response)
    assert(responseCaptor.getValue == response)
  }

  test("A non existing header can be fetched") {
    //request with no headers at all
    val request = new HttpServletRequestWrapper(mock[HttpServletRequest]) {
      val empty = new util.Vector[String]().elements()
      override val getHeaderNames: util.Enumeration[String] = empty
      override def getHeader(name: String): String = null
      override def getHeaders(name: String): util.Enumeration[String] = empty
    }

    // when doFilter is called
    filter.doFilter(request, response, chain)

    val requestCaptor = ArgumentCaptor.forClass(classOf[HttpServletRequest])
    val responseCaptor = ArgumentCaptor.forClass(classOf[HttpServletResponse])

    verify(chain, times(1)).doFilter(requestCaptor.capture(), responseCaptor.capture())

    // we return correct values for non existing headers
    val modifiedRequest = requestCaptor.getValue
    assert(modifiedRequest.getHeader("not existing") == null)
    assert(!modifiedRequest.getHeaders("not existing").hasMoreElements)

    // the response is left untouched
    verifyNoMoreInteractions(response)
    assert(responseCaptor.getValue == response)
  }
}
