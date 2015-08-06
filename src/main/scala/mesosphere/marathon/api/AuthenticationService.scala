package mesosphere.marathon.api

import javax.inject.Inject

import mesosphere.marathon.MarathonConf
import org.apache.log4j.Logger
import org.apache.shiro.authc.{ AuthenticationException, UsernamePasswordToken }
import org.apache.shiro.config.IniSecurityManagerFactory
import org.apache.shiro.subject.Subject

class AuthenticationService @Inject() (config: MarathonConf) {

  val log = Logger.getLogger(getClass.getName)

  def authenticate(user: String, pass: String, requiredRole: String): Boolean = {

    log.debug(s"Credentials: $user:$pass")
    login(user, pass, requiredRole)
  }

  def login(uname: String, pwd: String, requiredRole: String): Boolean = {
    val ldapFactory = new IniSecurityManagerFactory("classpath:shiro.ini")
    val securityManager = ldapFactory.getInstance
    val token = new UsernamePasswordToken(uname, pwd)

    val subject = new Subject.Builder(securityManager).buildSubject()

    try {
      securityManager.login(subject, token).hasRole(requiredRole)
    }
    catch {
      case _: AuthenticationException => false
    }
  }

}
