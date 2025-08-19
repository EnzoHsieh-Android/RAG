#!/usr/bin/env python3
"""
推薦系統精準度測試腳本
測試不同類型的查詢並分析推薦精準度
"""

import requests
import json
import time
from typing import List, Dict, Tuple

# 測試配置
API_BASE = "http://localhost:8081/api/v2/recommend"
TEST_QUERIES = [
    # 科幻/科技類
    {
        "query": "我想看科幻小說", 
        "expected_tags": ["科幻", "小說", "科技", "未來"],
        "category": "科幻文學"
    },
    {
        "query": "推薦人工智慧相關的書",
        "expected_tags": ["人工智慧", "AI", "機器學習", "科技"],
        "category": "人工智慧"
    },
    {
        "query": "量子計算的書籍",
        "expected_tags": ["量子計算", "物理", "計算機科學", "科技"],
        "category": "量子物理"
    },
    
    # 歷史/文學類
    {
        "query": "我想了解中國歷史",
        "expected_tags": ["歷史", "中國", "文化", "古代"],
        "category": "中國歷史"
    },
    {
        "query": "推薦經典文學作品",
        "expected_tags": ["文學", "經典", "小說", "名著"],
        "category": "經典文學"
    },
    
    # 商業/投資類
    {
        "query": "股票投資理財書籍",
        "expected_tags": ["投資", "股票", "理財", "金融", "經濟"],
        "category": "投資理財"
    },
    {
        "query": "創業和商業管理",
        "expected_tags": ["創業", "商業", "管理", "企業"],
        "category": "商業管理"
    },
    
    # 生活/健康類
    {
        "query": "心理學和自我成長",
        "expected_tags": ["心理學", "自我成長", "心理", "成長"],
        "category": "心理成長"
    },
    {
        "query": "料理和美食相關",
        "expected_tags": ["料理", "美食", "烹飪", "食譜"],
        "category": "美食料理"
    },
    
    # 藝術/設計類
    {
        "query": "設計和藝術書籍",
        "expected_tags": ["設計", "藝術", "美術", "創作"],
        "category": "藝術設計"
    }
]

def call_api(endpoint: str, query: str) -> Tuple[Dict, float, bool]:
    """調用推薦API"""
    try:
        start_time = time.time()
        response = requests.post(
            f"{API_BASE}/{endpoint}",
            json={"query": query},
            timeout=30
        )
        response_time = time.time() - start_time
        
        if response.status_code == 200:
            return response.json(), response_time, True
        else:
            return {"error": f"HTTP {response.status_code}"}, response_time, False
            
    except Exception as e:
        return {"error": str(e)}, 0, False

def calculate_tag_relevance(book_tags: List[str], expected_tags: List[str]) -> float:
    """計算標籤相關性評分"""
    if not book_tags or not expected_tags:
        return 0.0
    
    # 將所有標籤轉為小寫進行比較
    book_tags_lower = [tag.lower() for tag in book_tags]
    expected_tags_lower = [tag.lower() for tag in expected_tags]
    
    # 計算匹配的標籤數量
    matches = 0
    for expected in expected_tags_lower:
        for book_tag in book_tags_lower:
            if expected in book_tag or book_tag in expected:
                matches += 1
                break
    
    # 相關性評分 = 匹配數量 / 期望標籤數量
    return matches / len(expected_tags_lower)

def analyze_recommendations(results: List[Dict], expected_tags: List[str]) -> Dict[str, float]:
    """分析推薦結果"""
    if not results:
        return {"avg_relevance": 0.0, "top1_relevance": 0.0, "top3_relevance": 0.0, "total_books": 0}
    
    relevance_scores = []
    for book in results:
        book_tags = book.get("tags", [])
        relevance = calculate_tag_relevance(book_tags, expected_tags)
        relevance_scores.append(relevance)
    
    avg_relevance = sum(relevance_scores) / len(relevance_scores)
    top1_relevance = relevance_scores[0] if relevance_scores else 0.0
    top3_relevance = sum(relevance_scores[:3]) / min(3, len(relevance_scores))
    
    return {
        "avg_relevance": avg_relevance,
        "top1_relevance": top1_relevance, 
        "top3_relevance": top3_relevance,
        "total_books": len(results)
    }

def run_accuracy_test():
    """執行精準度測試"""
    print("🎯 開始推薦系統精準度測試")
    print(f"📊 測試查詢數量: {len(TEST_QUERIES)}")
    print("=" * 80)
    
    natural_results = []
    fast_results = []
    
    for i, test_case in enumerate(TEST_QUERIES, 1):
        query = test_case["query"]
        expected_tags = test_case["expected_tags"]
        category = test_case["category"]
        
        print(f"\n{i}. 測試查詢: {query}")
        print(f"   類別: {category}")
        print(f"   期望標籤: {expected_tags}")
        
        # 測試Natural API
        print("   🔍 測試Natural API...")
        natural_data, natural_time, natural_success = call_api("natural", query)
        
        # 測試Fast API  
        print("   ⚡ 測試Fast API...")
        fast_data, fast_time, fast_success = call_api("fast", query)
        
        if natural_success and fast_success:
            # 從recommendation.results路徑獲取結果
            natural_books = natural_data.get("recommendation", {}).get("results", [])
            fast_books = fast_data.get("recommendation", {}).get("results", [])
            
            # 分析推薦精準度
            natural_analysis = analyze_recommendations(natural_books, expected_tags)
            fast_analysis = analyze_recommendations(fast_books, expected_tags)
            
            print(f"   📊 Natural結果: {natural_analysis['total_books']}本書, Top1相關性: {natural_analysis['top1_relevance']:.2f}, 平均: {natural_analysis['avg_relevance']:.2f}, 耗時: {natural_time:.2f}s")
            print(f"   📊 Fast結果: {fast_analysis['total_books']}本書, Top1相關性: {fast_analysis['top1_relevance']:.2f}, 平均: {fast_analysis['avg_relevance']:.2f}, 耗時: {fast_time:.2f}s")
            
            # 顯示Top3推薦書籍
            print("   📚 Natural API Top 3:")
            for j, book in enumerate(natural_books[:3], 1):
                relevance = calculate_tag_relevance(book.get("tags", []), expected_tags)
                print(f"      {j}. {book.get('title', 'Unknown')} - {book.get('author', 'Unknown')} (相關性: {relevance:.2f})")
                print(f"         標籤: {book.get('tags', [])}")
            
            natural_results.append({
                "query": query,
                "category": category, 
                "success": True,
                "analysis": natural_analysis,
                "time": natural_time
            })
            
            fast_results.append({
                "query": query,
                "category": category,
                "success": True, 
                "analysis": fast_analysis,
                "time": fast_time
            })
            
        else:
            print(f"   ❌ API調用失敗 - Natural: {natural_success}, Fast: {fast_success}")
            natural_results.append({"query": query, "category": category, "success": False})
            fast_results.append({"query": query, "category": category, "success": False})
        
        print("-" * 80)
    
    # 統計總體結果
    print("\n📈 總體測試結果統計:")
    print("=" * 80)
    
    successful_natural = [r for r in natural_results if r["success"]]
    successful_fast = [r for r in fast_results if r["success"]]
    
    if successful_natural:
        natural_avg_relevance = sum(r["analysis"]["avg_relevance"] for r in successful_natural) / len(successful_natural)
        natural_avg_top1 = sum(r["analysis"]["top1_relevance"] for r in successful_natural) / len(successful_natural)
        natural_avg_time = sum(r["time"] for r in successful_natural) / len(successful_natural)
        
        print(f"🔍 Natural API 平均表現:")
        print(f"   成功率: {len(successful_natural)}/{len(TEST_QUERIES)} ({len(successful_natural)/len(TEST_QUERIES)*100:.1f}%)")
        print(f"   平均相關性: {natural_avg_relevance:.3f}")
        print(f"   Top1平均相關性: {natural_avg_top1:.3f}")
        print(f"   平均響應時間: {natural_avg_time:.2f}s")
    
    if successful_fast:
        fast_avg_relevance = sum(r["analysis"]["avg_relevance"] for r in successful_fast) / len(successful_fast)
        fast_avg_top1 = sum(r["analysis"]["top1_relevance"] for r in successful_fast) / len(successful_fast)  
        fast_avg_time = sum(r["time"] for r in successful_fast) / len(successful_fast)
        
        print(f"\n⚡ Fast API 平均表現:")
        print(f"   成功率: {len(successful_fast)}/{len(TEST_QUERIES)} ({len(successful_fast)/len(TEST_QUERIES)*100:.1f}%)")
        print(f"   平均相關性: {fast_avg_relevance:.3f}")
        print(f"   Top1平均相關性: {fast_avg_top1:.3f}")
        print(f"   平均響應時間: {fast_avg_time:.2f}s")
    
    # 保存詳細結果
    results_summary = {
        "test_time": time.strftime("%Y-%m-%d %H:%M:%S"),
        "total_queries": len(TEST_QUERIES),
        "natural_api": {
            "success_rate": len(successful_natural) / len(TEST_QUERIES),
            "avg_relevance": natural_avg_relevance if successful_natural else 0,
            "avg_top1_relevance": natural_avg_top1 if successful_natural else 0,
            "avg_response_time": natural_avg_time if successful_natural else 0
        },
        "fast_api": {
            "success_rate": len(successful_fast) / len(TEST_QUERIES),
            "avg_relevance": fast_avg_relevance if successful_fast else 0,
            "avg_top1_relevance": fast_avg_top1 if successful_fast else 0,
            "avg_response_time": fast_avg_time if successful_fast else 0
        },
        "detailed_results": {
            "natural": natural_results,
            "fast": fast_results
        }
    }
    
    with open("recommendation_accuracy_test.json", "w", encoding="utf-8") as f:
        json.dump(results_summary, f, ensure_ascii=False, indent=2)
    
    print(f"\n📄 詳細測試結果已保存到: recommendation_accuracy_test.json")
    print("🎯 精準度測試完成!")

if __name__ == "__main__":
    run_accuracy_test()