// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

public interface IrDeclarationBaseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase)
    org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder {

  /**
   * <code>required int32 symbol = 1;</code>
   */
  boolean hasSymbol();
  /**
   * <code>required int32 symbol = 1;</code>
   */
  int getSymbol();

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationOrigin origin = 2;</code>
   */
  boolean hasOrigin();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationOrigin origin = 2;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationOrigin getOrigin();

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Coordinates coordinates = 3;</code>
   */
  boolean hasCoordinates();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Coordinates coordinates = 3;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.Coordinates getCoordinates();

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Annotations annotations = 4;</code>
   */
  boolean hasAnnotations();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Annotations annotations = 4;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.Annotations getAnnotations();
}