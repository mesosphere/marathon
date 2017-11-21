# dcos-type-generator

> Generates code structures for all the types used and exchanged in DC/OS

DC/OS Type generator is a language-agnostic utility, designed to unify the API and internal structures of the DC/OS projects. It's purpose is to automatically generate the interface, migration, serialization and de-serialization code for each type and each project.

The code idea behind the project is described in the architectural diagram below:

![Architecture](doc/architecture.png)


## Usage

1. Install JDK 1.8+
2. Install Maven
3. Build the jar using

	```
	mvn package
	``` 

4. Run it

	```
	java -jar target/dtr-1.0-SNAPSHOT.jar
	```
