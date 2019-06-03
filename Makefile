FN_IMAGE := "iad.ocir.io/odx-jafar/trjl-test-fn/fn-trjl-test:0.0.3"

.PHONY: build
build: clean
	mvn package

.PHONY: test
test: build
	mvn test

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