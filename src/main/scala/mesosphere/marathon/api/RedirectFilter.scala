package mesosphere.marathon.api

import com.google.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.inject.Named
import javax.servlet._
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import mesosphere.marathon.{MarathonSchedulerService, ModuleNames}


class RedirectFilter @Inject()
    (schedulerService: MarathonSchedulerService,
     @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean)
  extends Filter  {

  val log = Logger.getLogger(getClass.getName)

  def init(filterConfig: FilterConfig) {}

  def doFilter(rawRequest: ServletRequest,
               rawResponse: ServletResponse,
               chain: FilterChain) {
    //TODO(FL): Think about proxying instead of redirecting.
    if (rawRequest.isInstanceOf[HttpServletRequest]) {
      val request = rawRequest.asInstanceOf[HttpServletRequest]
      val leaderData = schedulerService.getLeader
      if (schedulerService.isLeader) {
        chain.doFilter(request, rawResponse)
      } else {
        log.info("Redirecting request.")
        val response = rawResponse.asInstanceOf[HttpServletResponse]

        val newUrl = "http://%s%s".format(leaderData.get,
          request.getRequestURI)

        log.info("Redirect header sent to point to: " + newUrl)

        response.setHeader("Location", newUrl )
        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT)
      }
    }
  }

  def destroy() {
    //NO-OP
  }
}