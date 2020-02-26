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
	    sbt -Dsbt.global.base=.sbt -Dsbt.boot.directory=.sbt -Dsbt.ivy.home=.ivy2 test
	  '''
	}
      }
    }
  }
}
