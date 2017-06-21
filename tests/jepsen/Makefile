.PHONY : default clean clean-diff deps run shell build

default:
	@lein run test --nodes-file nodes_list --username vagrant --password vagrant

clean:
	@lein clean

clean-diff:
	@lein clean
	rm -rf store/

deps:
	@lein deps

run:
	@lein run test --nodes-file $(nodes) --username vagrant --password vagrant

shell:
	@lein repl

build: clean
	@lein compile
	@lein uberjar
