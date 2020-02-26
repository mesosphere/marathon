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
	    sbt -Dsbt.global.base=/var/tmp/.sbt -Dsbt.boot.directory=/var/tmp/.sbt -Dsbt.ivy.home=/var/tmp/.ivy2 test
	  '''
	}
      }
    }
  }
}
