.PHONY : default clean indent clean-diff deps run shell build

default:
	@lein run test --nodes-file nodes_list --username ubuntu --password ubuntu

clean:
	@lein clean

check-indent:
	@lein cljfmt check

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
	@lein run test --nodes-file $(nodes) --username ubuntu --password ubuntu

shell:
	@lein repl

build: clean
	@lein compile
	@lein uberjar
