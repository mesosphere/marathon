package mesosphere.marathon.api

import java.nio.charset.Charset
import javax.inject.Inject
import javax.servlet._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import org.apache.commons.codec.binary.Base64
import org.apache.log4j.Logger

class AuthenticationFilter @Inject() (authenticationService: AuthenticationService) extends Filter {

  val log = Logger.getLogger(getClass.getName)

  override def init(filterConfig: FilterConfig): Unit = {}

  override def destroy(): Unit = {}

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {

    val AUTHENTICATION_HEADER = "Authorization"

    request match {
      case httpServletRequest: HttpServletRequest =>
        val authCredentials = Option(httpServletRequest.getHeader(AUTHENTICATION_HEADER))
        val requiredRole = roleToPerformMethod(httpServletRequest.getMethod)
        val authenticationStatus = authCredentials.exists(authenticate(_, requiredRole))
        if (authenticationStatus) {
          chain.doFilter(request, response)
        }
        else {
          response match {
            case httpServletResponse: HttpServletResponse =>
              httpServletResponse.setHeader("WWW-Authenticate", "Basic realm=\"Login with your AD credentials\"")
              httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
          }
        }
    }
  }

  private def roleToPerformMethod(httpMethod: String): String = {
    httpMethod match {
      case "GET" | "OPTIONS" => "user"
      case _                 => "admin"
    }
  }

  private def authenticate(authCredentials: String, method: String): Boolean = {
    val (username, password) = extractUsernameAndPassword(authCredentials)
    authenticationService.authenticate(username, password, method)
  }

  private def extractUsernameAndPassword(authCredentials: String) = {
    // Authorization: Basic base64credentials
    val base64Credentials = authCredentials.substring("Basic".length()).trim()
    val credentials = new String(Base64.decodeBase64(base64Credentials), Charset.forName("UTF-8"))
    // credentials = username:password
    val usernameAndPassword = credentials.split(":", 2)
    (usernameAndPassword(0), usernameAndPassword(1))
  }
}

