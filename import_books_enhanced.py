#!/usr/bin/env python3
"""
增強版書籍導入腳本 - 導入cleaned_books_1000.json到Qdrant向量庫
支持自動創建collections、批量處理、錯誤恢復等功能
"""

import json
import requests
import hashlib
import argparse
import time
import sys
from typing import List, Dict, Any, Optional, Tuple
from pathlib import Path

# 配置
QDRANT_URL = "http://localhost:6333"
OLLAMA_URL = "http://localhost:11434"
EMBEDDING_MODEL = "quentinz/bge-large-zh-v1.5:latest"
DEFAULT_BATCH_SIZE = 10
MAX_RETRIES = 3
VECTOR_SIZE = 1024
DISTANCE_METRIC = "Cosine"

class BookImporter:
    def __init__(self, qdrant_url: str = QDRANT_URL, ollama_url: str = OLLAMA_URL):
        self.qdrant_url = qdrant_url
        self.ollama_url = ollama_url
        self.session = requests.Session()
        self.stats = {
            'total_books': 0,
            'processed_books': 0,
            'successful_uploads': 0,
            'failed_uploads': 0,
            'embedding_calls': 0,
            'start_time': None
        }
    
    def book_id_to_uuid(self, book_id: str) -> str:
        """將book_id轉換為確定性的UUID"""
        hash_object = hashlib.md5(book_id.encode())
        hash_hex = hash_object.hexdigest()
        uuid_str = f"{hash_hex[:8]}-{hash_hex[8:12]}-{hash_hex[12:16]}-{hash_hex[16:20]}-{hash_hex[20:32]}"
        return uuid_str
    
    def check_services(self) -> bool:
        """檢查Qdrant和Ollama服務狀態"""
        print("🔍 檢查服務狀態...")
        
        try:
            # 檢查Qdrant
            qdrant_resp = self.session.get(f"{self.qdrant_url}/collections", timeout=10)
            if qdrant_resp.status_code != 200:
                print(f"❌ Qdrant服務不可用: {qdrant_resp.status_code}")
                return False
            print("✅ Qdrant服務正常")
            
            # 檢查Ollama
            ollama_resp = self.session.get(f"{self.ollama_url}/api/tags", timeout=10)
            if ollama_resp.status_code != 200:
                print(f"❌ Ollama服務不可用: {ollama_resp.status_code}")
                return False
            print("✅ Ollama服務正常")
            
            # 檢查模型是否存在
            models = ollama_resp.json().get("models", [])
            model_names = [model["name"] for model in models]
            if EMBEDDING_MODEL not in model_names:
                print(f"❌ 模型 {EMBEDDING_MODEL} 未找到")
                print(f"可用模型: {model_names}")
                return False
            print(f"✅ 模型 {EMBEDDING_MODEL} 可用")
            
            return True
        except Exception as e:
            print(f"❌ 服務檢查失敗: {e}")
            return False
    
    def check_collection_exists(self, collection_name: str) -> bool:
        """檢查collection是否存在"""
        try:
            response = self.session.get(f"{self.qdrant_url}/collections/{collection_name}")
            return response.status_code == 200
        except Exception as e:
            print(f"⚠️ 檢查collection {collection_name} 失敗: {e}")
            return False
    
    def create_collection(self, collection_name: str) -> bool:
        """創建新的collection"""
        print(f"🔧 創建collection: {collection_name}")
        
        collection_config = {
            "vectors": {
                "size": VECTOR_SIZE,
                "distance": DISTANCE_METRIC
            },
            "optimizers_config": {
                "default_segment_number": 2
            },
            "replication_factor": 1
        }
        
        try:
            response = self.session.put(
                f"{self.qdrant_url}/collections/{collection_name}",
                json=collection_config,
                timeout=30
            )
            
            if response.status_code == 200:
                result = response.json()
                if result.get("status") == "ok":
                    print(f"✅ Collection {collection_name} 創建成功")
                    return True
            
            print(f"❌ 創建collection失敗: {response.status_code}, {response.text}")
            return False
        except Exception as e:
            print(f"❌ 創建collection異常: {e}")
            return False
    
    def clear_collection(self, collection_name: str) -> bool:
        """清空collection中的所有數據"""
        print(f"🧹 清空collection: {collection_name}")
        
        try:
            # 使用scroll來獲取所有點的ID
            response = self.session.post(
                f"{self.qdrant_url}/collections/{collection_name}/points/scroll",
                json={"limit": 10000, "with_payload": False, "with_vector": False}
            )
            
            if response.status_code != 200:
                print(f"❌ 獲取點列表失敗: {response.status_code}")
                return False
            
            points_data = response.json()["result"]
            point_ids = [point["id"] for point in points_data["points"]]
            
            if not point_ids:
                print(f"✅ Collection {collection_name} 已經是空的")
                return True
            
            # 刪除所有點
            delete_response = self.session.post(
                f"{self.qdrant_url}/collections/{collection_name}/points/delete",
                json={"points": point_ids}
            )
            
            if delete_response.status_code == 200:
                result = delete_response.json()
                if result.get("status") == "ok":
                    print(f"✅ Collection {collection_name} 清空成功，刪除了 {len(point_ids)} 個點")
                    return True
            
            print(f"❌ 清空collection失敗: {delete_response.status_code}")
            return False
        except Exception as e:
            print(f"❌ 清空collection異常: {e}")
            return False
    
    def setup_collections(self, clear_existing: bool = False) -> bool:
        """設置collections"""
        print("🏗️ 設置collections...")
        
        collections = ["tags_vecs", "desc_vecs"]
        
        for collection in collections:
            exists = self.check_collection_exists(collection)
            
            if exists:
                print(f"📋 Collection {collection} 已存在")
                if clear_existing:
                    if not self.clear_collection(collection):
                        return False
            else:
                if not self.create_collection(collection):
                    return False
        
        print("✅ Collections設置完成")
        return True
    
    def get_embedding(self, text: str) -> Optional[List[float]]:
        """獲取文本的embedding向量"""
        self.stats['embedding_calls'] += 1
        
        for attempt in range(MAX_RETRIES):
            try:
                response = self.session.post(
                    f"{self.ollama_url}/api/embeddings",
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
                    print(f"❌ Embedding API錯誤 (嘗試 {attempt + 1}/{MAX_RETRIES}): {response.status_code}")
                    if attempt < MAX_RETRIES - 1:
                        time.sleep(1)
            except Exception as e:
                print(f"❌ Embedding請求異常 (嘗試 {attempt + 1}/{MAX_RETRIES}): {e}")
                if attempt < MAX_RETRIES - 1:
                    time.sleep(1)
        
        return None
    
    def upsert_to_qdrant(self, collection: str, points: List[Dict[str, Any]]) -> bool:
        """批量上傳點到Qdrant"""
        if not points:
            return True
        
        try:
            response = self.session.put(
                f"{self.qdrant_url}/collections/{collection}/points",
                json={"points": points},
                timeout=60
            )
            
            if response.status_code == 200:
                result = response.json()
                if result.get("status") == "ok":
                    return True
            
            print(f"❌ Qdrant上傳錯誤: {response.status_code}, {response.text}")
            return False
        except Exception as e:
            print(f"❌ Qdrant請求異常: {e}")
            return False
    
    def process_book(self, book: Dict[str, Any], book_index: int) -> Tuple[Optional[Dict], Optional[Dict]]:
        """處理單本書籍，返回tags_point和desc_point"""
        try:
            book_id = book.get("book_id", f"bk_{book_index}")
            title = book.get("title", "")
            author = book.get("author", "")
            description = book.get("description", "")
            tags = book.get("tags", [])
            language = book.get("language", "中文")
            cover_url = book.get("cover_url", "")
            
            print(f"📚 處理第 {book_index + 1} 本書: {title}")
            
            # 生成tags向量
            tags_text = f"分類：{', '.join(tags)}" if tags else f"書名：{title}"
            tags_vector = self.get_embedding(tags_text)
            if tags_vector is None:
                print(f"❌ 無法獲取 tags embedding: {title}")
                return None, None
            
            # 生成description向量
            desc_vector = self.get_embedding(description)
            if desc_vector is None:
                print(f"❌ 無法獲取 description embedding: {title}")
                return None, None
            
            # 構建完整metadata (存在tags_vecs中)
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
            point_uuid = self.book_id_to_uuid(book_id)
            
            # tags_vecs點（包含所有metadata）
            tags_point = {
                "id": point_uuid,
                "vector": tags_vector,
                "payload": full_payload
            }
            
            # desc_vecs點（只包含必要信息）
            desc_point = {
                "id": point_uuid,
                "vector": desc_vector,
                "payload": {
                    "book_id": book_id,
                    "type": "book_desc"
                }
            }
            
            return tags_point, desc_point
            
        except Exception as e:
            print(f"❌ 處理書籍失敗: {book.get('title', 'Unknown')} - {e}")
            return None, None
    
    def process_batch(self, books: List[Dict[str, Any]], start_idx: int) -> Tuple[List[Dict], List[Dict], int]:
        """處理一批書籍"""
        tags_points = []
        desc_points = []
        successful_count = 0
        
        for i, book in enumerate(books):
            tags_point, desc_point = self.process_book(book, start_idx + i)
            
            if tags_point and desc_point:
                tags_points.append(tags_point)
                desc_points.append(desc_point)
                successful_count += 1
            else:
                self.stats['failed_uploads'] += 1
        
        return tags_points, desc_points, successful_count
    
    def import_books(self, json_file: str, batch_size: int = DEFAULT_BATCH_SIZE, clear_existing: bool = False) -> bool:
        """主導入流程"""
        print(f"🚀 開始導入 {json_file} 到Qdrant...")
        
        # 1. 檢查服務
        if not self.check_services():
            return False
        
        # 2. 設置collections
        if not self.setup_collections(clear_existing):
            return False
        
        # 3. 載入數據
        print(f"📖 載入 {json_file}...")
        try:
            json_path = Path(json_file)
            if not json_path.exists():
                print(f"❌ 文件不存在: {json_file}")
                return False
            
            with open(json_path, "r", encoding="utf-8") as f:
                books = json.load(f)
            
            print(f"✅ 成功載入 {len(books)} 本書籍")
            
        except Exception as e:
            print(f"❌ 載入數據失敗: {e}")
            return False
        
        # 4. 初始化統計
        self.stats['total_books'] = len(books)
        self.stats['start_time'] = time.time()
        
        # 5. 批次處理
        print(f"🏭 開始批次處理，批次大小: {batch_size}")
        
        for i in range(0, len(books), batch_size):
            batch = books[i:i + batch_size]
            batch_num = i // batch_size + 1
            total_batches = (len(books) + batch_size - 1) // batch_size
            
            print(f"\n📦 處理批次 {batch_num}/{total_batches} ({len(batch)} 本書)...")
            
            try:
                # 處理這批書籍
                tags_points, desc_points, successful_count = self.process_batch(batch, i)
                
                if not tags_points or not desc_points:
                    print("⚠️ 批次處理無有效結果，跳過上傳")
                    continue
                
                # 上傳到tags_vecs
                print(f"📤 上傳到 tags_vecs...")
                if not self.upsert_to_qdrant("tags_vecs", tags_points):
                    print(f"❌ tags_vecs 上傳失敗")
                    continue
                print(f"✅ tags_vecs 上傳成功: {len(tags_points)} 個點")
                
                # 上傳到desc_vecs
                print(f"📤 上傳到 desc_vecs...")
                if not self.upsert_to_qdrant("desc_vecs", desc_points):
                    print(f"❌ desc_vecs 上傳失敗")
                    continue
                print(f"✅ desc_vecs 上傳成功: {len(desc_points)} 個點")
                
                # 更新統計
                self.stats['processed_books'] += len(batch)
                self.stats['successful_uploads'] += successful_count
                
                # 進度報告
                self.print_progress(i + len(batch), len(books))
                
            except Exception as e:
                print(f"❌ 批次處理失敗: {e}")
                continue
            
            # 批次間稍作停頓
            time.sleep(0.5)
        
        # 6. 最終報告和驗證
        self.print_final_report()
        self.verify_collections()
        
        return True
    
    def print_progress(self, processed: int, total: int):
        """打印進度信息"""
        if self.stats['start_time'] is None:
            return
        
        elapsed = time.time() - self.stats['start_time']
        progress = (processed / total) * 100
        books_per_sec = processed / elapsed if elapsed > 0 else 0
        eta_seconds = (total - processed) / books_per_sec if books_per_sec > 0 else 0
        eta_minutes = eta_seconds / 60
        
        print(f"📊 進度: {processed}/{total} ({progress:.1f}%)")
        print(f"⏱️  處理速度: {books_per_sec:.1f} 本/秒")
        print(f"🕐 預計剩餘時間: {eta_minutes:.1f} 分鐘")
        print(f"🧮 Embedding調用次數: {self.stats['embedding_calls']}")
    
    def print_final_report(self):
        """打印最終報告"""
        if self.stats['start_time'] is None:
            return
        
        total_time = time.time() - self.stats['start_time']
        
        print(f"\n🎉 導入完成!")
        print(f"=====================================")
        print(f"📊 總計統計:")
        print(f"   總書籍數: {self.stats['total_books']}")
        print(f"   處理書籍: {self.stats['processed_books']}")
        print(f"   成功上傳: {self.stats['successful_uploads']}")
        print(f"   失敗數量: {self.stats['failed_uploads']}")
        print(f"   Embedding調用: {self.stats['embedding_calls']}")
        print(f"⏱️  總耗時: {total_time:.1f} 秒")
        
        if self.stats['processed_books'] > 0:
            print(f"🔥 平均速度: {self.stats['processed_books']/total_time:.1f} 本/秒")
            success_rate = (self.stats['successful_uploads'] / self.stats['processed_books']) * 100
            print(f"📈 成功率: {success_rate:.1f}%")
    
    def verify_collections(self):
        """驗證collections狀態"""
        print(f"\n🔍 驗證collections狀態...")
        
        try:
            for collection in ["tags_vecs", "desc_vecs"]:
                response = self.session.get(f"{self.qdrant_url}/collections/{collection}")
                if response.status_code == 200:
                    info = response.json()["result"]
                    points_count = info["points_count"]
                    vectors_count = info.get("vectors_count", points_count)
                    print(f"✅ {collection}: {points_count} 個點, {vectors_count} 個向量")
                else:
                    print(f"❌ 無法獲取 {collection} 狀態: {response.status_code}")
        except Exception as e:
            print(f"❌ 驗證失敗: {e}")


def main():
    """主函數"""
    parser = argparse.ArgumentParser(description="增強版書籍導入腳本")
    parser.add_argument("json_file", nargs="?", default="cleaned_books_1000.json", 
                       help="要導入的JSON文件路徑")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE,
                       help=f"批次大小 (默認: {DEFAULT_BATCH_SIZE})")
    parser.add_argument("--clear-existing", action="store_true",
                       help="清空現有collections數據")
    parser.add_argument("--qdrant-url", default=QDRANT_URL,
                       help=f"Qdrant服務URL (默認: {QDRANT_URL})")
    parser.add_argument("--ollama-url", default=OLLAMA_URL,
                       help=f"Ollama服務URL (默認: {OLLAMA_URL})")
    
    args = parser.parse_args()
    
    print("🌟 增強版書籍導入腳本")
    print("=" * 50)
    print(f"📁 JSON文件: {args.json_file}")
    print(f"📦 批次大小: {args.batch_size}")
    print(f"🧹 清空現有數據: {args.clear_existing}")
    print(f"🔗 Qdrant URL: {args.qdrant_url}")
    print(f"🔗 Ollama URL: {args.ollama_url}")
    print(f"🤖 Embedding模型: {EMBEDDING_MODEL}")
    print("=" * 50)
    
    # 創建導入器並執行導入
    importer = BookImporter(args.qdrant_url, args.ollama_url)
    success = importer.import_books(
        json_file=args.json_file,
        batch_size=args.batch_size,
        clear_existing=args.clear_existing
    )
    
    if success:
        print("\n🚀 數據導入腳本執行完成!")
        sys.exit(0)
    else:
        print("\n💥 數據導入失敗!")
        sys.exit(1)


if __name__ == "__main__":
    main()