pipeline {
  agent none
  stages {
    stage("Build") {
      agent {
        dockerfile {
          label 'large'
        }
      }
      steps {
        ansiColor('xterm') {
          sh '''
	    SBT_OPTS="-Xms512M -Xmx2048M -Xss2M -XX:MaxMetaspaceSize=1024M" sbt compile
	  '''
	}
      }
    }
  }
}
