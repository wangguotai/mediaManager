/**
 * 通用卡片组件
 */

import React from 'react';
import {View, Text, StyleSheet, TouchableOpacity} from 'react-native';
import Colors from '../utils/Colors';

const Card = ({
  title,
  children,
  onPress,
  style,
  titleStyle,
  contentStyle,
}) => {
  const Wrapper = onPress ? TouchableOpacity : View;
  
  return (
    <Wrapper
      style={[styles.card, style]}
      onPress={onPress}
      activeOpacity={onPress ? 0.7 : 1}
    >
      {title && (
        <Text style={[styles.title, titleStyle]}>{title}</Text>
      )}
      <View style={[styles.content, contentStyle]}>
        {children}
      </View>
    </Wrapper>
  );
};

const styles = StyleSheet.create({
  card: {
    backgroundColor: Colors.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    color: Colors.text,
    marginBottom: 12,
  },
  content: {
    // 内容区域样式
  },
});

export default Card;
