.PHONY : default clean indent clean-diff deps run shell build

default:
	@lein run test --nodes-file nodes_list --username vagrant --password vagrant

clean:
	@lein clean

indent:
	@echo "Indenting the clojure files in this project..."
	@echo "[Requires node-cljfmt utility: https://github.com/snoe/node-cljfmt]"
	@for i in `find . -type f -name '*.clj'`;do echo $$i; cljfmt $$i; done

clean-diff: indent
	@lein clean
	@rm -rf store/

deps:
	@lein deps

run:
	@lein run test --nodes-file $(nodes) --username vagrant --password vagrant

shell:
	@lein repl

build: clean
	@lein compile
	@lein uberjar
