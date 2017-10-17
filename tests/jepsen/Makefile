.PHONY : default clean check-indent help indent indent-file clean-diff deps run shell build

default:
	@lein test $(test)

clean:
	@lein clean

check-indent:
	@lein cljfmt check

help:
	@lein test -h

indent:
	@echo "Indenting the clojure files in this project..."
	@lein cljfmt fix

indent-file:
	@echo "Indenting $(file)"
	@lein cljfmt fix $(file)

clean-diff: indent
	@lein clean
	@rm -rf store/

deps:
	@lein deps

run:
	@lein test $(test)

shell:
	@lein repl

build: clean
	@lein compile
	@lein uberjar
