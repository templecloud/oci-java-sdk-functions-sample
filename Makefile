.PHONY: build
build: clean
	mvn package

.PHONY: run-setup
run-setup:
	mvn exec:java -Dexec.mainClass="InvokeFunctionExample" -Dexec.args="setup"

.PHONY: run-invoke
run-invoke:
	mvn exec:java -Dexec.mainClass="InvokeFunctionExample" -Dexec.args="invoke"

.PHONY: run-teardown
run-teardown:
	mvn exec:java -Dexec.mainClass="InvokeFunctionExample" -Dexec.args="teardown"

.PHONY: clean
clean:
	mvn clean