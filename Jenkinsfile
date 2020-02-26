pipeline {
  agent none
  stages {
    stage("Build") {
      agent {
        docker {
	  image 'mesosphere/scala-sbt:marathon'
          label 'large'
        }
      }
      steps {
        ansiColor('xterm') {
          sh '''
	    /usr/local/sbt/bin/sbt test
	  '''
	}
      }
    }
  }
}
