/**
 * 通用按钮组件
 */

import React from 'react';
import {TouchableOpacity, Text, StyleSheet} from 'react-native';
import Colors from '../utils/Colors';

const Button = ({
  title,
  onPress,
  type = 'primary', // primary, secondary, danger
  size = 'normal', // small, normal, large
  disabled = false,
  style,
  textStyle,
}) => {
  const buttonStyles = [
    styles.button,
    styles[type],
    styles[size],
    disabled && styles.disabled,
    style,
  ];

  const textStyles = [
    styles.text,
    styles[`${type}Text`],
    styles[`${size}Text`],
    disabled && styles.disabledText,
    textStyle,
  ];

  return (
    <TouchableOpacity
      style={buttonStyles}
      onPress={onPress}
      disabled={disabled}
      activeOpacity={0.8}
    >
      <Text style={textStyles}>{title}</Text>
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  button: {
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  // 类型样式
  primary: {
    backgroundColor: Colors.primary,
  },
  secondary: {
    backgroundColor: Colors.surface,
    borderWidth: 1,
    borderColor: Colors.primary,
  },
  danger: {
    backgroundColor: Colors.error,
  },
  // 大小样式
  small: {
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  normal: {
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  large: {
    paddingHorizontal: 28,
    paddingVertical: 16,
  },
  // 禁用样式
  disabled: {
    backgroundColor: Colors.textDisabled,
    borderColor: Colors.textDisabled,
  },
  // 文字样式
  text: {
    fontWeight: '600',
  },
  primaryText: {
    color: '#FFFFFF',
  },
  secondaryText: {
    color: Colors.primary,
  },
  dangerText: {
    color: '#FFFFFF',
  },
  smallText: {
    fontSize: 12,
  },
  normalText: {
    fontSize: 16,
  },
  largeText: {
    fontSize: 18,
  },
  disabledText: {
    color: '#FFFFFF',
  },
});

export default Button;
