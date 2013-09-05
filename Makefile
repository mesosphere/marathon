
marathon-runnable.jar: jar = target/marathon-*-jar-with-dependencies.jar
marathon-runnable.jar: run = bin/marathon-framework
marathon-runnable.jar: $(run) $(jar)
	cat $(run) $(jar) > $@


