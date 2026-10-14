# 灌库验证（临时）：智谱 embedding 同款查询向量 → pgvector 余弦相似度 top3
# 复现 RetrievalAugmentationAdvisor 的检索路径，证明灌库数据可被真实检索
import json
import os

import httpx
import psycopg

resp = httpx.post(
    "https://open.bigmodel.cn/api/paas/v4/embeddings",
    headers={"Authorization": f"Bearer {os.environ['ZHIPU_EMBEDDING_API_KEY']}"},
    json={"model": "embedding-3", "input": "工单被驳回了怎么办", "dimensions": 1024},
    timeout=30,
)
vec = resp.json()["data"][0]["embedding"]
print(f"[embedding] {len(vec)} 维")

with psycopg.connect(
    "host=127.0.0.1 port=5432 dbname=terminal_kb user=my_user",
    password=os.environ["PG_PASSWORD"],
) as conn, conn.cursor() as cur:
    cur.execute(
        "SELECT metadata->>'filename', embedding <=> %s::vector "
        "FROM vector_store ORDER BY embedding <=> %s::vector LIMIT 3",
        (json.dumps(vec), json.dumps(vec)),
    )
    for fname, dist in cur.fetchall():
        print(f"[top 匹配] {fname}  距离={dist:.4f}")
