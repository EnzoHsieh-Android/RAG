#!/usr/bin/env python3
"""
導入cleaned_books_1000.json到Qdrant向量庫的腳本
"""

import json
import requests
import uuid
import time
import hashlib
from typing import List, Dict, Any

# 配置
QDRANT_URL = "http://localhost:6333"
OLLAMA_URL = "http://localhost:11434"
EMBEDDING_MODEL = "quentinz/bge-large-zh-v1.5"
BATCH_SIZE = 10  # 批次大小
MAX_RETRIES = 3  # 最大重試次數

def book_id_to_uuid(book_id: str) -> str:
    """將book_id轉換為確定性的UUID"""
    # 使用MD5 hash來生成確定性UUID
    hash_object = hashlib.md5(book_id.encode())
    hash_hex = hash_object.hexdigest()
    
    # 將32位hex轉換為UUID格式 (8-4-4-4-12)
    uuid_str = f"{hash_hex[:8]}-{hash_hex[8:12]}-{hash_hex[12:16]}-{hash_hex[16:20]}-{hash_hex[20:32]}"
    return uuid_str

def get_embedding(text: str) -> List[float]:
    """獲取文本的embedding向量"""
    max_retries = 3
    for attempt in range(max_retries):
        try:
            response = requests.post(
                f"{OLLAMA_URL}/api/embeddings",
                json={
                    "model": EMBEDDING_MODEL,
                    "prompt": text
                },
                timeout=30
            )
            
            if response.status_code == 200:
                result = response.json()
                return result["embedding"]
            else:
                print(f"❌ Embedding API錯誤 (嘗試 {attempt + 1}/{max_retries}): {response.status_code}")
                if attempt < max_retries - 1:
                    time.sleep(1)
        except Exception as e:
            print(f"❌ Embedding請求異常 (嘗試 {attempt + 1}/{max_retries}): {e}")
            if attempt < max_retries - 1:
                time.sleep(1)
    
    raise Exception("無法獲取embedding")

def upsert_to_qdrant(collection: str, points: List[Dict[str, Any]]) -> bool:
    """批量上傳點到Qdrant"""
    try:
        response = requests.put(
            f"{QDRANT_URL}/collections/{collection}/points",
            json={"points": points},
            timeout=60
        )
        
        if response.status_code == 200:
            result = response.json()
            return result.get("status") == "ok"
        else:
            print(f"❌ Qdrant上傳錯誤: {response.status_code}, {response.text}")
            return False
    except Exception as e:
        print(f"❌ Qdrant請求異常: {e}")
        return False

def process_books_batch(books: List[Dict[str, Any]], start_idx: int) -> tuple:
    """處理一批書籍"""
    tags_points = []
    desc_points = []
    successful_count = 0
    
    for i, book in enumerate(books):
        try:
            book_id = book.get("book_id", f"bk_{start_idx + i}")
            title = book.get("title", "")
            author = book.get("author", "")
            description = book.get("description", "")
            tags = book.get("tags", [])
            language = book.get("language", "中文")
            cover_url = book.get("cover_url", "")
            
            print(f"📚 處理第 {start_idx + i + 1} 本書: {title}")
            
            # 生成tags向量
            tags_text = f"分類：{', '.join(tags)}" if tags else f"書名：{title}"
            tags_vector = get_embedding(tags_text)
            
            # 生成description向量
            desc_vector = get_embedding(description)
            
            # 構建payload（完整metadata存在tags_vecs中）
            full_payload = {
                "book_id": book_id,
                "title": title,
                "author": author,
                "description": description,
                "tags": tags,
                "language": language,
                "cover_url": cover_url,
                "type": "book"
            }
            
            # 生成UUID作為point ID
            point_uuid = book_id_to_uuid(book_id)
            
            # tags_vecs點（包含所有metadata）
            tags_points.append({
                "id": point_uuid,
                "vector": tags_vector,
                "payload": full_payload
            })
            
            # desc_vecs點（只包含必要信息）
            desc_points.append({
                "id": point_uuid,
                "vector": desc_vector,
                "payload": {
                    "book_id": book_id,
                    "type": "book_desc"
                }
            })
            
            successful_count += 1
            
        except Exception as e:
            print(f"❌ 處理書籍失敗: {title} - {e}")
            continue
    
    return tags_points, desc_points, successful_count

def main():
    print("🚀 開始導入cleaned_books_1000.json到Qdrant...")
    
    # 檢查服務可用性
    print("🔍 檢查服務狀態...")
    try:
        # 檢查Qdrant
        qdrant_resp = requests.get(f"{QDRANT_URL}/collections", timeout=5)
        if qdrant_resp.status_code != 200:
            print("❌ Qdrant服務不可用")
            return
        print("✅ Qdrant服務正常")
        
        # 檢查Ollama
        ollama_resp = requests.get(f"{OLLAMA_URL}/api/tags", timeout=5)
        if ollama_resp.status_code != 200:
            print("❌ Ollama服務不可用")
            return
        print("✅ Ollama服務正常")
        
    except Exception as e:
        print(f"❌ 服務檢查失敗: {e}")
        return
    
    # 載入數據
    print("📖 載入cleaned_books_1000.json...")
    try:
        with open("cleaned_books_1000.json", "r", encoding="utf-8") as f:
            books = json.load(f)
        print(f"✅ 成功載入 {len(books)} 本書籍")
    except Exception as e:
        print(f"❌ 載入數據失敗: {e}")
        return
    
    # 批次處理
    total_books = len(books)
    total_processed = 0
    total_uploaded = 0
    start_time = time.time()
    
    print(f"🏭 開始批次處理，批次大小: {BATCH_SIZE}")
    
    for i in range(0, total_books, BATCH_SIZE):
        batch = books[i:i + BATCH_SIZE]
        batch_num = i // BATCH_SIZE + 1
        total_batches = (total_books + BATCH_SIZE - 1) // BATCH_SIZE
        
        print(f"\n📦 處理批次 {batch_num}/{total_batches} ({len(batch)} 本書)...")
        
        try:
            # 處理這批書籍
            tags_points, desc_points, successful_count = process_books_batch(batch, i)
            total_processed += successful_count
            
            if tags_points and desc_points:
                # 上傳到tags_vecs
                print(f"📤 上傳到tags_vecs...")
                if upsert_to_qdrant("tags_vecs", tags_points):
                    print(f"✅ tags_vecs上傳成功: {len(tags_points)} 個點")
                else:
                    print(f"❌ tags_vecs上傳失敗")
                    continue
                
                # 上傳到desc_vecs
                print(f"📤 上傳到desc_vecs...")
                if upsert_to_qdrant("desc_vecs", desc_points):
                    print(f"✅ desc_vecs上傳成功: {len(desc_points)} 個點")
                    total_uploaded += len(tags_points)
                else:
                    print(f"❌ desc_vecs上傳失敗")
                    continue
            
            # 進度報告
            elapsed = time.time() - start_time
            books_per_sec = total_processed / elapsed if elapsed > 0 else 0
            eta_seconds = (total_books - i - len(batch)) / books_per_sec if books_per_sec > 0 else 0
            eta_minutes = eta_seconds / 60
            
            print(f"📊 進度: {i + len(batch)}/{total_books} ({((i + len(batch))/total_books*100):.1f}%)")
            print(f"⏱️  處理速度: {books_per_sec:.1f} 本/秒")
            print(f"🕐 預計剩餘時間: {eta_minutes:.1f} 分鐘")
            
        except Exception as e:
            print(f"❌ 批次處理失敗: {e}")
            continue
        
        # 批次間稍作停頓
        time.sleep(0.5)
    
    # 最終報告
    total_time = time.time() - start_time
    print(f"\n🎉 導入完成!")
    print(f"📊 總計處理: {total_processed}/{total_books} 本書")
    print(f"📤 成功上傳: {total_uploaded} 本書")
    print(f"⏱️  總耗時: {total_time:.1f} 秒")
    print(f"🔥 平均速度: {total_processed/total_time:.1f} 本/秒")
    
    # 驗證collections狀態
    print(f"\n🔍 驗證collections狀態...")
    try:
        for collection in ["tags_vecs", "desc_vecs"]:
            resp = requests.get(f"{QDRANT_URL}/collections/{collection}")
            if resp.status_code == 200:
                info = resp.json()["result"]
                points_count = info["points_count"]
                print(f"✅ {collection}: {points_count} 個點")
            else:
                print(f"❌ 無法獲取 {collection} 狀態")
    except Exception as e:
        print(f"❌ 驗證失敗: {e}")
    
    print("\n🚀 數據導入腳本執行完成!")

if __name__ == "__main__":
    main()