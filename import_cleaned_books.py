#!/usr/bin/env python3
"""
批量導入test_books.json到Qdrant向量數據庫
支持雙collection架構：tags_vecs + desc_vecs
"""

import json
import requests
import time
import uuid
from typing import List, Dict
from concurrent.futures import ThreadPoolExecutor, as_completed

# 配置
QDRANT_URL = "http://localhost:6333"
OLLAMA_URL = "http://localhost:11434/api/embeddings"
BATCH_SIZE = 10  # 每批處理10本書
MAX_WORKERS = 3  # 並行處理線程數

def get_embedding(text: str) -> List[float]:
    """獲取文本的embedding向量"""
    try:
        response = requests.post(OLLAMA_URL, 
            json={
                "model": "quentinz/bge-large-zh-v1.5:latest",
                "prompt": text
            },
            timeout=30
        )
        
        if response.status_code == 200:
            data = response.json()
            return data.get("embedding", [])
        else:
            print(f"❌ Embedding API失敗: {response.status_code}")
            return []
    except Exception as e:
        print(f"❌ Embedding請求異常: {e}")
        return []

def upsert_to_collection(collection_name: str, points: List[Dict]) -> bool:
    """批量上傳points到指定collection"""
    try:
        response = requests.put(
            f"{QDRANT_URL}/collections/{collection_name}/points",
            json={"points": points},
            timeout=60
        )
        
        if response.status_code == 200:
            return True
        else:
            print(f"❌ 上傳到{collection_name}失敗: {response.status_code}, {response.text}")
            return False
    except Exception as e:
        print(f"❌ 上傳異常: {e}")
        return False

def process_book_batch(books: List[Dict], batch_num: int) -> Dict[str, int]:
    """處理一批書籍數據"""
    print(f"📚 處理第{batch_num}批：{len(books)}本書籍...")
    
    tags_points = []
    desc_points = []
    success_count = 0
    error_count = 0
    
    for book in books:
        try:
            # 生成UUID格式的ID以匹配Qdrant要求
            book_id = str(uuid.uuid4())
            original_id = book["book_id"]
            
            # 生成tags向量
            tags_text = f"分類：{', '.join(book['tags'])}"
            tags_vector = get_embedding(tags_text)
            
            if not tags_vector:
                print(f"❌ 無法獲取tags向量: {book['title']}")
                error_count += 1
                continue
            
            # 生成description向量
            desc_vector = get_embedding(book["description"])
            
            if not desc_vector:
                print(f"❌ 無法獲取desc向量: {book['title']}")
                error_count += 1
                continue
                
            # 構建tags_vecs point
            tags_point = {
                "id": book_id,
                "vector": tags_vector,
                "payload": {
                    "book_id": book_id,
                    "original_id": original_id,  # 保留原始ID用於參考
                    "title": book["title"],
                    "author": book["author"],
                    "description": book["description"],
                    "tags": book["tags"],
                    "language": book["language"],
                    "cover_url": book.get("cover_url", ""),
                    "type": "book"
                }
            }
            
            # 構建desc_vecs point
            desc_point = {
                "id": book_id,
                "vector": desc_vector,
                "payload": {
                    "book_id": book_id,
                    "original_id": original_id,  # 保留原始ID用於參考
                    "description": book["description"],
                    "type": "book_desc"
                }
            }
            
            tags_points.append(tags_point)
            desc_points.append(desc_point)
            success_count += 1
            
        except Exception as e:
            print(f"❌ 處理書籍失敗 {book.get('title', 'Unknown')}: {e}")
            error_count += 1
    
    # 批量上傳
    tags_success = upsert_to_collection("tags_vecs", tags_points)
    desc_success = upsert_to_collection("desc_vecs", desc_points)
    
    if tags_success and desc_success:
        print(f"✅ 第{batch_num}批完成：{success_count}本成功，{error_count}本失敗")
        return {"success": success_count, "error": error_count}
    else:
        print(f"❌ 第{batch_num}批上傳失敗")
        return {"success": 0, "error": len(books)}

def main():
    """主函數：批量導入書籍數據"""
    print("🚀 開始批量導入test_books.json...")
    
    # 讀取書籍數據
    try:
        with open('test_books.json', 'r', encoding='utf-8') as f:
            books_data = json.load(f)
    except Exception as e:
        print(f"❌ 讀取文件失敗: {e}")
        return
    
    print(f"📖 讀取到 {len(books_data)} 本書籍")
    
    # 分批處理
    total_success = 0
    total_error = 0
    start_time = time.time()
    
    # 將書籍分成批次
    batches = [books_data[i:i + BATCH_SIZE] for i in range(0, len(books_data), BATCH_SIZE)]
    
    print(f"📦 分成 {len(batches)} 批次，每批 {BATCH_SIZE} 本書")
    
    # 使用線程池並行處理
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        future_to_batch = {
            executor.submit(process_book_batch, batch, i+1): i+1 
            for i, batch in enumerate(batches)
        }
        
        for future in as_completed(future_to_batch):
            batch_num = future_to_batch[future]
            try:
                result = future.result()
                total_success += result["success"]
                total_error += result["error"]
            except Exception as e:
                print(f"❌ 批次{batch_num}執行異常: {e}")
                total_error += BATCH_SIZE
    
    # 統計結果
    total_time = time.time() - start_time
    print(f"\n📊 導入完成統計:")
    print(f"   ✅ 成功: {total_success} 本")
    print(f"   ❌ 失敗: {total_error} 本") 
    print(f"   ⏱️ 耗時: {total_time:.2f} 秒")
    print(f"   📈 平均速度: {total_success/total_time:.2f} 本/秒")

if __name__ == "__main__":
    main()