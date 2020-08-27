PROTOC_PLUGIN_PATH = $(shell which protoc-gen-grpc-java)
all:
	protoc --plugin=protoc-gen-grpc-java=$(PROTOC_PLUGIN_PATH) \
		--java_out=src/java --grpc-java_out=src/java --proto_path=resources/proto resources/proto/**/*.proto
