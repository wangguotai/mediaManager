#!/bin/bash

# Generate Go code from protobuf definitions
PROTO_DIR="../shared/proto"
OUTPUT_DIR="./gen"

# Create output directory
mkdir -p $OUTPUT_DIR

# Generate Go code
protoc --go_out=$OUTPUT_DIR --go_opt=paths=source_relative \
       --go-grpc_out=$OUTPUT_DIR --go-grpc_opt=paths=source_relative \
       -I $PROTO_DIR $PROTO_DIR/media.proto

echo "Protobuf code generated successfully in $OUTPUT_DIR"
