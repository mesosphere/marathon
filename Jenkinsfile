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
	    whoami
	    pwd
	    yum install -y git
	    /usr/local/sbt/bin/sbt test
	  '''
	}
      }
    }
  }
}
