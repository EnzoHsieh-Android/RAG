#!/usr/bin/env python3
import requests
import json
import numpy as np

def get_embedding(text):
    response = requests.post("http://localhost:11434/api/embeddings", 
                           json={"model": "bge-large", "prompt": text})
    return np.array(response.json()["embedding"])

def cosine_similarity(a, b):
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))

# Get embeddings
book_tags = get_embedding("分類：小說、奇幻、史詩、戰爭、政治")
user_query = get_embedding("奇幻、小說、戰爭、恢宏")
user_full_query = get_embedding("我對奇幻類文學比較有興趣，最好有描寫戰爭的場面和恢宏的情節")

# Calculate similarities
tag_similarity = cosine_similarity(book_tags, user_query)
full_similarity = cosine_similarity(book_tags, user_full_query)

print(f"Tag similarity (book vs user tags): {tag_similarity:.4f}")
print(f"Full query similarity (book tags vs full query): {full_similarity:.4f}")

# Also test book description
book_desc = get_embedding("史詩級奇幻小說系列的第一部。在維斯特洛大陸上，七大王國的貴族家族為了爭奪鐵王座，展開了殘酷的權力鬥爭。與此同時，北境長城外的古老威脅也正悄然甦醒。")
desc_similarity = cosine_similarity(book_desc, user_full_query)
print(f"Description similarity (book desc vs full query): {desc_similarity:.4f}")