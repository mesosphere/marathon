define USAGE
Marathon system integration tests ⚙️

Commands:
  init      Install Python dependencies with pipenv
  build     Run formatter and linter.
  test      Test system integration. The DCOS_URL environment variable must be
            present.
endef

export USAGE
help:
	@echo "$$USAGE"

init:
	pip3 install pipenv
	pipenv sync

build:
	pipenv run flake8 --count --max-line-length=120

test:
	pipenv run shakedown \
      --ssl-no-verify \
      --pytest-option "--junitxml=../../shakedown.xml" \
      --pytest-option -v \
      --pytest-option --full-trace \
      --ssh-key-file "$(CLI_TEST_SSH_KEY)" \
      --ssh-user "centos" \
      --dcos-url "$(DCOS_URL)" test_marathon_root.py test_marathon_universe.py
