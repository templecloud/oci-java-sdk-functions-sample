FN_IMAGE := "iad.ocir.io/odx-jafar/trjl-test-fn/fn-trjl-test:0.0.3"

.PHONY: build
build: clean
	mvn package

.PHONY: test
test: build
	mvn test

.PHONY: run
run:
	mvn exec:java -Dexec.mainClass="InvokeFunctionExample" -Dexec.args="$(FN_IMAGE)"

.PHONY: clean
clean:
	mvn clean