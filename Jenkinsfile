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
       // ansiColor('xterm') {
          sh '''
	    sbt compile
	  '''
//	}
      }
    }
  }
}
