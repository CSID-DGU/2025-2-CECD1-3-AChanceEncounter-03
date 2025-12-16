"""
- Feature: 17개
- Training: Negative Sampling (1:5)
- Ops: Champion-Challenger + DB Logging
"""

import os
from pathlib import Path
import pandas as pd
import numpy as np
import datetime
import json
import warnings
from sqlalchemy import create_engine, text, types
import oracledb
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score, average_precision_score, precision_recall_curve, accuracy_score, f1_score
import lightgbm as lgb
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType

warnings.filterwarnings('ignore')

# -----------------------
# 환경 설정
# -----------------------
try:
     CLIENT_LIB_DIR = "/Users/m2/instantclient_19_8"
     if os.path.isdir(CLIENT_LIB_DIR):
         oracledb.init_oracle_client(lib_dir=CLIENT_LIB_DIR)
except: pass

DB_CONNECTION_STRING = "oracle+oracledb://C##GP_SB:gnb202502%23@175.209.155.138:1521/ORCLCDB"


BASE_DIR = Path(".") 

# 1. 챔피언 모델 저장 위치 (루트)
LATEST_MODEL_PATH = BASE_DIR / "model_latest.onnx"
LATEST_META_PATH = BASE_DIR / "model_latest_metadata.json"
SCORE_FILE_PATH = BASE_DIR / "best_auc_score.txt"

# 2. 이력(History) 저장 폴더 (하위 폴더 생성)
HISTORY_MODEL_DIR = BASE_DIR / "history" / "models"
HISTORY_META_DIR = BASE_DIR / "history" / "metadata"

HISTORY_MODEL_DIR.mkdir(parents=True, exist_ok=True)
HISTORY_META_DIR.mkdir(parents=True, exist_ok=True)

TEST_SIZE = 0.2
RANDOM_STATE = 42

# [최종 피처 리스트: 17개]
FIXED_FEATURES = [
    # 1. 상호작용
    "list_view_cnt", "detail_view_cnt",
    
    # 2. 상품 정보
    "avg_amt", "amt_range", "prdct_dv_cd", "prdct_cls_cd",
    "is_youth", "is_vip",
    
    # 3. 활동성
    "days_since_last_login", "total_login_count_raw", "total_session_count_raw", 
    "login_count_7d", "login_count_30d",
    
    # 4. 사용자 정보
    "age", "birth_yy", "sex_cd", "occp_dv_cd"
]

# -----------------------
# 1. 피처 저장소 갱신 (SQL)
# -----------------------
def create_feature_store(conn_string):
    print(f"\n[{datetime.datetime.now()}] [1단계] 운영 피처 테이블(ML_FEATURE_STORE) 갱신 중...")
    engine = create_engine(conn_string)
    
    with engine.connect() as conn:
        try: conn.execute(text("DROP TABLE ML_FEATURE_STORE PURGE"))
        except: pass

    create_sql = """
    CREATE TABLE ML_FEATURE_STORE AS
    SELECT * FROM (
        WITH 
        USER_BASIC AS (
            SELECT CSNO, SEX_CD, OCCP_DV_CD,
                TO_NUMBER(TO_CHAR(SYSDATE, 'YYYY')) - TO_NUMBER(REGEXP_REPLACE(BIRTH_YY, '[^0-9]', '')) AS AGE,
                TO_NUMBER(REGEXP_REPLACE(BIRTH_YY, '[^0-9]', '')) AS BIRTH_YY
            FROM APP_USR_M WHERE DEL_YN = 'N'
        ),
        USER_ACTIVITY AS (
            SELECT CSNO, COUNT(*) AS TOTAL_LOGIN_COUNT_RAW, COUNT(DISTINCT SESSION_ID) AS TOTAL_SESSION_COUNT_RAW,
                TRUNC(SYSDATE - MAX(LAST_LGIN_DTM)) AS DAYS_SINCE_LAST_LOGIN,
                COUNT(CASE WHEN LAST_LGIN_DTM >= SYSDATE - 7 THEN 1 END) AS LOGIN_COUNT_7D,
                COUNT(CASE WHEN LAST_LGIN_DTM >= SYSDATE - 30 THEN 1 END) AS LOGIN_COUNT_30D
            FROM APP_USRLGIN_H GROUP BY CSNO
        ),
        PRODUCT_INFO AS (
            SELECT M.PRDCT_CD, M.PRDCT_DV_CD, M.PRDCT_CLS_CD, M.PRDCT_NM,
                (NVL(D.MIN_AMT, 0) + NVL(D.MAX_AMT, 0)) / 2 AS AVG_AMT,
                (NVL(D.MAX_AMT, 0) - NVL(D.MIN_AMT, 0)) AS AMT_RANGE
            FROM PRD_INFO_M M 
            LEFT JOIN PRD_INFO_D D ON M.PRDCT_CD = D.PRDCT_CD WHERE M.USE_YN = 'Y'
        ),
        INTERACTION_LOGS AS (
            SELECT 
                CSNO,
                COUNT(CASE WHEN SCRN_NO LIKE 'DEP%_1' THEN 1 END) AS DEP_LIST_CNT,
                COUNT(CASE WHEN SCRN_NO LIKE 'DEP%_2' OR SCRN_NO LIKE 'DEP%_8' THEN 1 END) AS DEP_DETAIL_CNT,
                COUNT(CASE WHEN SCRN_NO LIKE 'LON%_1' THEN 1 END) AS LON_LIST_CNT,
                COUNT(CASE WHEN SCRN_NO LIKE 'LON%_2' THEN 1 END) AS LON_DETAIL_CNT
            FROM APP_TRLOG_I GROUP BY CSNO
        ),
        LABELS AS (
            SELECT DISTINCT CSNO, PRDCT_CD, 1 AS IS_SUBSCRIBED
            FROM PRD_SUBS_H WHERE SUBS_ST_CD = 'OK'
        )
        SELECT 
            U.CSNO, P.PRDCT_CD,
            NVL(L.IS_SUBSCRIBED, 0) AS IS_SUBSCRIBED,
            
            CASE WHEN P.PRDCT_DV_CD = '01' THEN NVL(I.LON_LIST_CNT, 0) WHEN P.PRDCT_DV_CD = '02' THEN NVL(I.DEP_LIST_CNT, 0) ELSE 0 END AS LIST_VIEW_CNT,
            CASE WHEN P.PRDCT_DV_CD = '01' THEN NVL(I.LON_DETAIL_CNT, 0) WHEN P.PRDCT_DV_CD = '02' THEN NVL(I.DEP_DETAIL_CNT, 0) ELSE 0 END AS DETAIL_VIEW_CNT,
            
            NVL(P.AVG_AMT, 0) AS AVG_AMT, 
            NVL(P.AMT_RANGE, 0) AS AMT_RANGE,
            CASE WHEN P.PRDCT_NM LIKE '%청년%' THEN 1 ELSE 0 END AS IS_YOUTH,
            CASE WHEN P.PRDCT_NM LIKE '%VIP%' THEN 1 ELSE 0 END AS IS_VIP,
            
            P.PRDCT_DV_CD, P.PRDCT_CLS_CD,
            NVL(A.DAYS_SINCE_LAST_LOGIN, 999) AS DAYS_SINCE_LAST_LOGIN,
            NVL(A.TOTAL_LOGIN_COUNT_RAW, 0) AS TOTAL_LOGIN_COUNT_RAW,
            NVL(A.TOTAL_SESSION_COUNT_RAW, 0) AS TOTAL_SESSION_COUNT_RAW,
            NVL(A.LOGIN_COUNT_7D, 0) AS LOGIN_COUNT_7D,
            NVL(A.LOGIN_COUNT_30D, 0) AS LOGIN_COUNT_30D,
            U.AGE, U.BIRTH_YY, U.SEX_CD, U.OCCP_DV_CD
        FROM USER_BASIC U
        CROSS JOIN PRODUCT_INFO P
        LEFT JOIN USER_ACTIVITY A ON U.CSNO = A.CSNO
        LEFT JOIN INTERACTION_LOGS I ON U.CSNO = I.CSNO
        LEFT JOIN LABELS L ON U.CSNO = L.CSNO AND P.PRDCT_CD = L.PRDCT_CD
    )
    """
    with engine.connect() as conn:
        conn.execute(text(create_sql))
        print("   -> ✅ 운영 피처 테이블 생성 완료.")

# -----------------------
# 2. 학습 및 평가
# -----------------------
def get_current_best_score():
    if SCORE_FILE_PATH.exists():
        try: return float(open(SCORE_FILE_PATH).read().strip())
        except: return 0.0
    return 0.0

def update_best_score(new_score):
    with open(SCORE_FILE_PATH, "w") as f: f.write(str(new_score))

def train_and_evaluate(conn_string):
    print(f"\n[{datetime.datetime.now()}] [2단계] 모델 학습 (Random Split + Negative Sampling)...")
    engine = create_engine(conn_string)
    
    df = pd.read_sql(text("SELECT * FROM ML_FEATURE_STORE"), con=engine)
    df.columns = df.columns.str.lower()
    
    for col in FIXED_FEATURES + ["is_subscribed"]:
        if col not in df.columns: df[col] = 0
        try: df[col] = pd.to_numeric(df[col]).fillna(0)
        except: df[col] = df[col].fillna(0).astype('category')


    X_all = df[FIXED_FEATURES]
    y_all = df['is_subscribed']
    
    X_train_raw, X_test, y_train_raw, y_test = train_test_split(
        X_all, y_all, test_size=TEST_SIZE, stratify=y_all, random_state=RANDOM_STATE, shuffle=True
    )
    
    # Negative Sampling
    train_df = X_train_raw.copy()
    train_df['is_subscribed'] = y_train_raw
    pos_df = train_df[train_df['is_subscribed'] == 1]
    neg_df = train_df[train_df['is_subscribed'] == 0]
    
    sample_ratio = 5
    n_neg = len(pos_df) * sample_ratio
    neg_df_sampled = neg_df.sample(n=n_neg, random_state=RANDOM_STATE) if len(neg_df) > n_neg else neg_df
    train_balanced = pd.concat([pos_df, neg_df_sampled]).sample(frac=1, random_state=RANDOM_STATE)
    
    X_train = train_balanced[FIXED_FEATURES]
    y_train = train_balanced['is_subscribed']

    model = lgb.LGBMClassifier(n_estimators=1000, learning_rate=0.05, scale_pos_weight=len(neg_df_sampled)/len(pos_df), random_state=RANDOM_STATE, n_jobs=-1)
    model.fit(X_train, y_train, eval_set=[(X_test, y_test)], eval_metric='auc', callbacks=[lgb.early_stopping(100)])
    
    # Evaluate
    y_proba = model.predict_proba(X_test)[:, 1]
    
    precisions, recalls, thresholds = precision_recall_curve(y_test, y_proba)
    f1_scores = 2 * (precisions * recalls) / (precisions + recalls)
    best_idx = np.argmax(f1_scores)
    best_th = thresholds[best_idx]
    y_pred = (y_proba >= best_th).astype(int)

    auc = roc_auc_score(y_test, y_proba)
    pr_auc = average_precision_score(y_test, y_proba)
    acc = accuracy_score(y_test, y_pred)
    f1 = f1_score(y_test, y_pred)
    
    print("\n" + "="*60)
    print(f"                FINAL MODEL PERFORMANCE REPORT               ")
    print("="*60)
    print(f"\n[1] Overall Metrics (Best Threshold: {best_th:.4f})")
    print(f"    - ROC AUC Score  : {auc:.4f}")
    print(f"    - PR AUC Score   : {pr_auc:.4f}")
    print(f"    - Accuracy       : {acc:.4f}")
    print(f"    - F1 Score       : {f1:.4f}")

    print(f"\n[2] Classification Report:\n")
    print(classification_report(y_test, y_pred))

    print(f"[3] Confusion Matrix:\n")
    cm = confusion_matrix(y_test, y_pred)
    print(f"              Pred:0  Pred:1")
    print(f"    Actual:0  {cm[0,0]:<6}  {cm[0,1]:<6}")
    print(f"    Actual:1  {cm[1,0]:<6}  {cm[1,1]:<6}")

    print(f"\n[4] Feature Importance (Top 10):")
    fi = pd.DataFrame({'Feature': FIXED_FEATURES, 'Importance': model.feature_importances_})
    fi = fi.sort_values('Importance', ascending=False).head(10)
    print(fi.to_string(index=False))
    
    metadata = {
        "model_name": "LGBM_Ranking_Model_Prod_Final",
        "created_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "metrics": {"auc": float(auc), "pr_auc": float(pr_auc), "f1": float(f1)},
        "threshold": float(best_th),
        "features": FIXED_FEATURES
    }
    return model, auc, metadata

# -----------------------
# 3. DB Logging & Deployment
# -----------------------
def log_performance_to_db(conn_string, metadata, is_deployed):
    print("\n[DB Logging] 성능 정보를 MODEL_PERFORMANCE_LOG 테이블에 저장합니다...")
    engine = create_engine(conn_string)
    log_data = {
        'EXEC_DT': datetime.datetime.now(),
        'MODEL_VERSION': metadata['created_at'],
        'ROC_AUC': metadata['metrics']['auc'],
        'PR_AUC': metadata['metrics']['pr_auc'],
        'F1_SCORE': metadata['metrics']['f1'],
        'THRESHOLD': metadata['threshold'],
        'IS_DEPLOYED': 'Y' if is_deployed else 'N'
    }
    try:
        pd.DataFrame([log_data]).to_sql('model_performance_log', engine, if_exists='append', index=False,
                      dtype={'EXEC_DT': types.DateTime, 'MODEL_VERSION': types.VARCHAR(50), 
                             'ROC_AUC': types.Numeric(10, 5), 'PR_AUC': types.Numeric(10, 5), 
                             'F1_SCORE': types.Numeric(10, 5), 'THRESHOLD': types.Numeric(10, 5), 
                             'IS_DEPLOYED': types.CHAR(1)})
        print("   -> ✅ DB Log Inserted Successfully.")
    except Exception as e: print(f"   -> ❌ DB Log Failed: {e}")

def deploy_model(conn_string, model, new_auc, metadata):
    print(f"\n[{datetime.datetime.now()}] [3단계] 배포 결정...")
    current_best = get_current_best_score()
    print(f"   -> Champion: {current_best:.4f} vs Challenger: {new_auc:.4f}")
    
    initial_types = [('input', FloatTensorType([None, len(FIXED_FEATURES)]))]
    onnx_model = onnxmltools.convert_lightgbm(model, initial_types=initial_types)
    
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    
    # 1. 이력 저장 (history/models/...)
    onnxmltools.utils.save_model(onnx_model, HISTORY_MODEL_DIR / f"model_history_{ts}_auc{new_auc:.4f}.onnx")
    with open(HISTORY_META_DIR / f"metadata_{ts}.json", "w", encoding='utf-8') as f:
        json.dump(metadata, f, indent=4, ensure_ascii=False)
    
    is_deployed = False
    if new_auc >= current_best:
        print("   ->  성능 향상! 운영 모델을 교체합니다.")
        
        # 2. 챔피언 저장 (root/model_latest.onnx)
        onnxmltools.utils.save_model(onnx_model, LATEST_MODEL_PATH)
        with open(LATEST_META_PATH, "w", encoding='utf-8') as f:
            json.dump(metadata, f, indent=4, ensure_ascii=False)
            
        update_best_score(new_auc)
        print(f"   -> ✅ 배포 완료: {LATEST_MODEL_PATH}")
        is_deployed = True
    else:
        print("   -> 🛑 성능 저하. 기존 모델을 유지합니다.")

    log_performance_to_db(conn_string, metadata, is_deployed)

if __name__ == "__main__":
    try:
        create_feature_store(DB_CONNECTION_STRING)
        model, auc, metadata = train_and_evaluate(DB_CONNECTION_STRING)
        deploy_model(DB_CONNECTION_STRING, model, auc, metadata)
        print(f"\n✅ 모든 운영 배포 작업 완료.")
    except Exception as e:
        print(f"\n❌ 오류 발생: {e}")