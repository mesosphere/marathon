define USAGE
Shakedown ⚙️

Commands:
  init      Install build and test dependencies with pipenv
  build     Run formatter and linter.
endef

export USAGE
help:
	@echo "$$USAGE"

init:
	pip3 install pipenv
	pipenv install --dev

build:
	pipenv run flake8 --count --max-line-length=120 shakedown dcos
