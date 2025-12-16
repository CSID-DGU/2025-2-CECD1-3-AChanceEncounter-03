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
OUT_DIR = Path("./model_output_final")
OUT_DIR.mkdir(parents=True, exist_ok=True)
SCORE_FILE_PATH = OUT_DIR / "best_auc_score.txt"

TEST_SIZE = 0.2
RANDOM_STATE = 42

# [최종 피처 리스트: 18개 (금리 제외)]
FIXED_FEATURES = [
    # 1. 상호작용 (Interaction)
    "list_view_cnt", "detail_view_cnt",
    
    # 2. 상품 정보 (Product Props - 금리 제거됨)
    "avg_amt", "amt_range", "prdct_dv_cd", "prdct_cls_cd",
    "is_youth",        # 청년 상품 여부
    "is_mobile",       # 모바일 전용 여부
    "is_vip",          # VIP 전용 여부
    
    # 3. 활동성 (Activity)
    "days_since_last_login", "total_login_count_raw", "total_session_count_raw", 
    "login_count_7d", "login_count_30d",
    
    # 4. 사용자 정보 (User)
    "age", "birth_yy", "sex_cd", "occp_dv_cd"
]

# -----------------------
# 1. 피처 저장소 갱신
# -----------------------
def create_feature_store(conn_string):
    print(f"\n[{datetime.datetime.now()}] [1단계] 피처 테이블 갱신 중 (금리 피처 제외)...")
    engine = create_engine(conn_string)
    
    with engine.connect() as conn:
        try: conn.execute(text("DROP TABLE ML_FEATURE_STORE_TEST PURGE"))
        except: pass

    create_sql = """
    CREATE TABLE ML_FEATURE_STORE_TEST AS
    SELECT * FROM (
        WITH 
        USER_BASIC AS (
            SELECT CSNO, SEX_CD, OCCP_DV_CD,
                TO_NUMBER(TO_CHAR(SYSDATE, 'YYYY')) - TO_NUMBER(REGEXP_REPLACE(BIRTH_YY, '[^0-9]', '')) AS AGE,
                TO_NUMBER(REGEXP_REPLACE(BIRTH_YY, '[^0-9]', '')) AS BIRTH_YY
            FROM APP_USR_M_TEST WHERE DEL_YN = 'N'
        ),
        USER_ACTIVITY AS (
            SELECT CSNO, COUNT(*) AS TOTAL_LOGIN_COUNT_RAW, COUNT(DISTINCT SESSION_ID) AS TOTAL_SESSION_COUNT_RAW,
                TRUNC(SYSDATE - MAX(LAST_LGIN_DTM)) AS DAYS_SINCE_LAST_LOGIN,
                COUNT(CASE WHEN LAST_LGIN_DTM >= SYSDATE - 7 THEN 1 END) AS LOGIN_COUNT_7D,
                COUNT(CASE WHEN LAST_LGIN_DTM >= SYSDATE - 30 THEN 1 END) AS LOGIN_COUNT_30D
            FROM APP_USRLGIN_H_TEST GROUP BY CSNO
        ),
        PRODUCT_INFO AS (
            SELECT M.PRDCT_CD, M.PRDCT_DV_CD, M.PRDCT_CLS_CD, M.PRDCT_NM,
                (NVL(D.MIN_AMT, 0) + NVL(D.MAX_AMT, 0)) / 2 AS AVG_AMT,
                (NVL(D.MAX_AMT, 0) - NVL(D.MIN_AMT, 0)) AS AMT_RANGE
                -- [삭제됨] 금리 컬럼 제외
            FROM PRD_INFO_M_TEST M 
            LEFT JOIN PRD_INFO_D_TEST D ON M.PRDCT_CD = D.PRDCT_CD WHERE M.USE_YN = 'Y'
        ),
        INTERACTION_LOGS AS (
            SELECT 
                CSNO,
                COUNT(CASE WHEN SCRN_NO LIKE 'DEP%_1' THEN 1 END) AS DEP_LIST_CNT,
                COUNT(CASE WHEN SCRN_NO LIKE 'DEP%_2' OR SCRN_NO LIKE 'DEP%_8' THEN 1 END) AS DEP_DETAIL_CNT,
                COUNT(CASE WHEN SCRN_NO LIKE 'LON%_1' THEN 1 END) AS LON_LIST_CNT,
                COUNT(CASE WHEN SCRN_NO LIKE 'LON%_2' THEN 1 END) AS LON_DETAIL_CNT
            FROM APP_TRLOG_I_TEST GROUP BY CSNO
        ),
        LABELS AS (
            SELECT DISTINCT CSNO, PRDCT_CD, 1 AS IS_SUBSCRIBED
            FROM PRD_SUBS_H_TEST WHERE SUBS_ST_CD = 'OK'
        )
        SELECT 
            U.CSNO, P.PRDCT_CD,
            NVL(L.IS_SUBSCRIBED, 0) AS IS_SUBSCRIBED,
            
            CASE WHEN P.PRDCT_DV_CD = '01' THEN NVL(I.LON_LIST_CNT, 0) WHEN P.PRDCT_DV_CD = '02' THEN NVL(I.DEP_LIST_CNT, 0) ELSE 0 END AS LIST_VIEW_CNT,
            CASE WHEN P.PRDCT_DV_CD = '01' THEN NVL(I.LON_DETAIL_CNT, 0) WHEN P.PRDCT_DV_CD = '02' THEN NVL(I.DEP_DETAIL_CNT, 0) ELSE 0 END AS DETAIL_VIEW_CNT,
            
            NVL(P.AVG_AMT, 0) AS AVG_AMT, 
            NVL(P.AMT_RANGE, 0) AS AMT_RANGE,
            
            CASE WHEN P.PRDCT_NM LIKE '%청년%' THEN 1 ELSE 0 END AS IS_YOUTH,
            CASE WHEN P.PRDCT_NM LIKE '%모바일%' THEN 1 ELSE 0 END AS IS_MOBILE,
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
        print("   -> ✅ 피처 테이블 갱신 완료.")

# -----------------------
# 2. Champion-Challenger Logic & Training
# -----------------------
def get_current_best_score():
    if SCORE_FILE_PATH.exists():
        try: return float(open(SCORE_FILE_PATH).read().strip())
        except: return 0.0
    return 0.0

def update_best_score(new_score):
    with open(SCORE_FILE_PATH, "w") as f: f.write(str(new_score))

def train_and_evaluate(conn_string):
    print(f"\n[{datetime.datetime.now()}] [2단계] 모델 학습 및 상세 평가...")
    engine = create_engine(conn_string)
    
    # Load Data
    df = pd.read_sql(text("SELECT * FROM ML_FEATURE_STORE_TEST"), con=engine)
    df.columns = df.columns.str.lower()
    
    for col in FIXED_FEATURES + ["is_subscribed"]:
        if col not in df.columns: df[col] = 0
        try: df[col] = pd.to_numeric(df[col]).fillna(0)
        except: df[col] = df[col].fillna(0).astype('category')

    # Split
    X_all = df[FIXED_FEATURES]
    y_all = df['is_subscribed']
    X_train_raw, X_test, y_train_raw, y_test = train_test_split(
        X_all, y_all, test_size=TEST_SIZE, stratify=y_all, random_state=RANDOM_STATE
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

    # Train
    model = lgb.LGBMClassifier(n_estimators=1000, learning_rate=0.05, scale_pos_weight=len(neg_df_sampled)/len(pos_df), random_state=RANDOM_STATE, n_jobs=-1)
    model.fit(X_train, y_train, eval_set=[(X_test, y_test)], eval_metric='auc', callbacks=[lgb.early_stopping(100)])
    
    # Evaluate with Details
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
    print(f"               📊 FINAL MODEL PERFORMANCE REPORT               ")
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
    
    # Metadata
    metadata = {
        "model_name": "LGBM_Ranking_Model_No_Rate",
        "created_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "metrics": {"auc": float(auc), "pr_auc": float(pr_auc), "f1": float(f1)},
        "threshold": float(best_th),
        "features": FIXED_FEATURES
    }
    
    return model, auc, metadata

# -----------------------
# 3. Deployment
# -----------------------
def deploy_model(model, new_auc, metadata):
    print(f"\n[{datetime.datetime.now()}] [3단계] 배포 결정...")
    current_best = get_current_best_score()
    print(f"   -> Champion: {current_best:.4f} vs Challenger: {new_auc:.4f}")
    
    initial_types = [('input', FloatTensorType([None, len(FIXED_FEATURES)]))]
    onnx_model = onnxmltools.convert_lightgbm(model, initial_types=initial_types)
    
    ts = datetime.datetime.now().strftime("%Y%m%d")
    
    onnxmltools.utils.save_model(onnx_model, OUT_DIR / f"model_history_{ts}.onnx")
    with open(OUT_DIR / f"model_metadata_{ts}.json", "w", encoding='utf-8') as f:
        json.dump(metadata, f, indent=4, ensure_ascii=False)
    
    if new_auc >= current_best:
        print("   -> 🏆 성능 향상! 운영 모델을 교체합니다.")
        onnxmltools.utils.save_model(onnx_model, OUT_DIR / "model_latest.onnx")
        with open(OUT_DIR / "model_latest_metadata.json", "w", encoding='utf-8') as f:
            json.dump(metadata, f, indent=4, ensure_ascii=False)
        update_best_score(new_auc)
        print(f"   -> ✅ 배포 완료: {OUT_DIR / 'model_latest.onnx'}")
    else:
        print("   -> 🛑 성능 저하. 기존 모델을 유지합니다.")

if __name__ == "__main__":
    try:
        create_feature_store(DB_CONNECTION_STRING)
        model, auc, metadata = train_and_evaluate(DB_CONNECTION_STRING)
        deploy_model(model, auc, metadata)
        print(f"\n✅ 모든 작업 완료.")
    except Exception as e:
        print(f"\n❌ 오류 발생: {e}")