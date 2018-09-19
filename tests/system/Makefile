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
	pipenv run pytest --junitxml="../../shakedown.xml" -v --full-trace test_marathon_root.py test_marathon_universe.py
