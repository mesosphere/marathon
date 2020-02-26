pipeline {
  agent none
  stages {
    stage("Build") {
      agent {
        docker {
	  image 'mesosphere/scala-sbt:marathon'
          label 'large'
	  args '-u root'
        }
      }
      steps {
        ansiColor('xterm') {
          sh '''
	    sbt test
	  '''
	}
      }
    }
    stage("Lint Tests") {
      agent {
        docker {
	  image 'python:3.6-alpine'
          label 'large'
	  args '-u root'
        }
      }
      steps {
        ansiColor('xterm') {
          sh '''
	    pip install flake8
  	    flake8 --count --max-line-length=120 tests/system tests/integration/src/test/resources/python
	  '''
	}
      }
    }
  }
}
