pipeline {
  stages {
    stage("Build") {
      agent {
        dockerfile {
          node 'large'
        }
      }
      steps {
        ansiColor('xterm') {
          sh '''
	    sbt compile
	  '''
	}
      }
    }
  }
}
