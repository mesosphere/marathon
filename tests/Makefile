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
	pipenv install --dev --skip-lock

build:
	flake8 --count --max-line-length=120

test:
	pipenv run shakedown \
      --stdout all \
      --stdout-inline \
      --ssl-no-verify \
      --timeout 360000 \
      --pytest-option "--junitxml=../shakedown.xml" \
      --pytest-option --verbose \
      --pytest-option --full-trace \
      --ssh-key-file "$(CLI_TEST_SSH_KEY)" \
      --dcos-url "$(DCOS_URL)" system/test_marathon_root.py system/test_marathon_universe.py
