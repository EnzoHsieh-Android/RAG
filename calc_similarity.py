#!/usr/bin/env python3
import json
import math

def dot_product(a, b):
    return sum(x * y for x, y in zip(a, b))

def magnitude(vector):
    return math.sqrt(sum(x * x for x in vector))

def cosine_similarity(a, b):
    return dot_product(a, b) / (magnitude(a) * magnitude(b))

# 从标准输入读取两个向量
import sys
data = json.load(sys.stdin)
similarity = cosine_similarity(data['vector1'], data['vector2'])
print(f"语义相似度: {similarity:.6f}")