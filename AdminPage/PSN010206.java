package app.psn.biz;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxSequence;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import frame.dolphin.common.consts.BizConst;
import frame.dolphin.core.application.Application;
import frame.dolphin.core.context.GApplicationContext;
import frame.dolphin.core.dao.DBManager;
import frame.dolphin.core.exception.ApplicationException;
import frame.dolphin.core.io.DataSet;
import frame.dolphin.core.io.RecordSet;

/**
 * AI추천관리
 * AI가 추천하는 상품 TOP3를 조회한다.
 * @author kdh
 * </pre>
 */
@Component
public class PSN010206 implements Application {

	private static final Logger logger = LoggerFactory.getLogger(PSN010206.class);

	@Autowired
	DBManager dbManager;

	// ONNX 모델 파일 경로 (JEUS/WEB-INF/classes 기준 등 환경에 맞게 조정)
	//private static final String MODEL_PATH = "/home/pythonuser/model_final_14features.onnx";
	private static final String MODEL_PATH = "C:/Galaxy_Studio_win_x64/model_final_14features.onnx";
	private Map<String, String> productNameMap = new HashMap<>();
	/**
	 * 입력항목 체크
	 */
	public void inputCheck(GApplicationContext ctx, DataSet req) throws ApplicationException {
		// 예: CSNO 필수 체크
		String csno = req.getString("CSNO");

		if (csno == null || csno.trim().isEmpty()) {
			throw new ApplicationException(ctx, BizConst.ERR_CODE_INPCHK, new String[]{"고객번호"});
		}
	}

	/**
	 * 업무처리
	 */
	@Override
	public DataSet excute(GApplicationContext ctx, DataSet req) throws ApplicationException {

		DataSet result = new DataSet();

		try {
			////////////////////////////////////////////////////////////////////////////
			// 1. 입력 체크
			////////////////////////////////////////////////////////////////////////////
			inputCheck(ctx, req);

			String csno = req.getString("CSNO");

			////////////////////////////////////////////////////////////////////////////
			// 2. 추천 Top3 계산
			////////////////////////////////////////////////////////////////////////////
			List<ProductScore> top3 = getTop3Recommendations(ctx, csno);

			for (int i = 0; i < top3.size(); i++) {
				ProductScore p = top3.get(i);
				int rank = i + 1;
				result.put("RECO_PRD_" + rank + "_CD", p.productId);
				result.put("RECO_PRD_" + rank + "_NM", p.productName);
				result.put("RECO_PRD_" + rank + "_PROB", p.probability); // 0~1 사이 확률
			}

			RecordSet t1 = new RecordSet();
			for (int i = 0; i < top3.size(); i++) {
				ProductScore ps = top3.get(i);
				DataSet record = new DataSet();

				record.put("T1_RANK", i + 1);
				record.put("T1_PRDCT_CD", ps.productId);
				record.put("T1_PRDCT_NM", ps.productName);
				record.put("T1_PROB", ps.probability);

				t1.add(record);
			}

			result.put("T1", t1);

			////////////////////////////////////////////////////////////////////////////
			// 3. 기타 ruleData 등 다른 업무 처리 결과 병합 (있다면)
			////////////////////////////////////////////////////////////////////////////
			// result.putAll(ruleData);    // ruleData 사용 중이면 여기에 병합

			////////////////////////////////////////////////////////////////////////////
			// 4. 응답전문 생성
			////////////////////////////////////////////////////////////////////////////

			return doResponse(ctx, result, req);

		} catch (ApplicationException e) {
			logger.error("업무처리 오류 : " + e.getMessage(), e);
			throw e;
		} catch (Exception e) {
			logger.error("업무처리 오류 : " + e.getMessage(), e);
			// 메시지 : 기타 오류입니다 ({0})
			throw new ApplicationException(ctx, "BSYS9999", new String[]{e.getMessage()}, e);
		}
	}

	/**
	 * 응답전문 생성
	 */
	protected DataSet doResponse(GApplicationContext ctx, DataSet result, DataSet req) throws ApplicationException {

		try {

			return result;
			
		} catch (Exception e) {
			logger.error("응답처리 오류 : " + e.getMessage(), e);
			throw new ApplicationException(ctx, "BSYS9999", new String[]{e.getMessage()}, e);
		}
	}

	/**
	 * 추천 상품 점수
	 */
	static class ProductScore {
		String productId;
		String productName;
		float probability;

		public ProductScore(String productId, String productName, float probability) {
			this.productId = productId;
			this.productName = productName;
			this.probability = probability;
		}
	}

	/**
	 * 고객별 추천 상품 Top3 계산
	 */
	private List<ProductScore> getTop3Recommendations(GApplicationContext ctx, String csno) throws Exception {
		Map<String, float[]> productsFeatures = getAllFeaturesForUser(ctx, csno);

		if (productsFeatures.isEmpty()) {
			logger.warn("CSNO={} 에 대한 상품 피처가 없습니다.", csno);
			return new ArrayList<>();
		}

		int numProducts = productsFeatures.size();
		int numFeatures = 14;

		float[] flatInput = new float[numProducts * numFeatures];
		List<String> productIds = new ArrayList<>();

		int idx = 0;
		for (Map.Entry<String, float[]> entry : productsFeatures.entrySet()) {
			productIds.add(entry.getKey());
			float[] feats = entry.getValue();
			System.arraycopy(feats, 0, flatInput, idx * numFeatures, numFeatures);
			idx++;
		}

		List<ProductScore> scores = new ArrayList<>();

		// ONNX 런타임 호출
		try (OrtEnvironment env = OrtEnvironment.getEnvironment();
				OrtSession session = env.createSession(MODEL_PATH, new OrtSession.SessionOptions())) {

			long[] shape = new long[]{numProducts, numFeatures};
			OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatInput), shape);

			Map<String, OnnxTensor> inputs = new HashMap<>();
			inputs.put("input", inputTensor);

			try (OrtSession.Result results = session.run(inputs)) {
				// LightGBM ONNX 출력: index 1이 확률 정보
				OnnxValue outputValue = results.get(1);

				if (outputValue instanceof OnnxSequence) {
					OnnxSequence seq = (OnnxSequence) outputValue;
					List<?> list = seq.getValue();

					for (int i = 0; i < list.size(); i++) {
						Object item = list.get(i);
						Map<Long, Float> probs = null;

						if (item instanceof OnnxMap) {
							probs = (Map<Long, Float>) ((OnnxMap) item).getValue();
						} else if (item instanceof Map) {
							probs = (Map<Long, Float>) item;
						}

						if (probs != null) {
							float prob = probs.getOrDefault(1L, 0.0f); // 클래스 1 가입확률
							scores.add(new ProductScore(productIds.get(i), productNameMap.get(productIds.get(i)), prob));
						}
					}
				} else {
					logger.error("Unexpected ONNX output type: {}", outputValue.getClass().getName());
				}
			}
		}

		// 확률 내림차순 정렬 후 Top3 반환
		scores.sort((p1, p2) -> Float.compare(p2.probability, p1.probability));
		return scores.subList(0, Math.min(scores.size(), 3));
	}


	/**
	 * 고객별 상품 피처 조회 (DBManager 버전 예시)
	 *
	 * - ML_FEATURE_STORE.S001 쿼리는 CSNO 파라미터로
	 *   PRDCT_CD, PRODUCT_VIEW_COUNT, AVG_AMT, ... 컬럼을 리턴한다고 가정
	 */
	private Map<String, float[]> getAllFeaturesForUser(GApplicationContext ctx, String csno) throws Exception {


		Map<String, float[]> result = new HashMap<>();
		productNameMap.clear();

		DataSet reqDs = new DataSet();
		reqDs.put("CSNO", csno);

		// ► 실제로는 사용하는 프레임워크에 맞게 getData / getList / RecordSet 등을 사용
		DataSet resDs = dbManager.getData(ctx, "ML_FEATURE_STORE.S001", reqDs);

		// 예: ResultSet 형태가 아니라, RecordSet 형태라고 가정
		//    RS 이름/접근 방식은 실제 프레임워크에 맞게 수정 필요
		RecordSet rs = resDs.getRecordSet(); // ← 이름은 예시입니다.

		for (int i = 0; i < rs.size(); i++) {
			DataSet tmpDs = rs.get(i);

			String prdctCd = tmpDs.getString("PRDCT_CD");
			String prdctNm = tmpDs.getString("PRDCT_NM");

			productNameMap.put(prdctCd, prdctNm);


			float[] feats = new float[14];

			feats[0]  = parseToFloat(tmpDs.getString("PRODUCT_VIEW_COUNT"));
			feats[1]  = parseToFloat(tmpDs.getString("AVG_AMT"));
			feats[2]  = parseToFloat(tmpDs.getString("AMT_RANGE"));
			feats[3]  = parseToFloat(tmpDs.getString("PRDCT_DV_CD"));
			feats[4]  = parseToFloat(tmpDs.getString("PRDCT_CLS_CD"));
			feats[5]  = parseToFloat(tmpDs.getString("DAYS_SINCE_LAST_LOGIN"));
			feats[6]  = parseToFloat(tmpDs.getString("TOTAL_LOGIN_COUNT_RAW"));
			feats[7]  = parseToFloat(tmpDs.getString("TOTAL_SESSION_COUNT_RAW"));
			feats[8]  = parseToFloat(tmpDs.getString("LOGIN_COUNT_7D"));
			feats[9]  = parseToFloat(tmpDs.getString("LOGIN_COUNT_30D"));
			feats[10] = parseToFloat(tmpDs.getString("AGE"));
			feats[11] = parseToFloat(tmpDs.getString("BIRTH_YY"));
			feats[12] = parseToFloat(tmpDs.getString("SEX_CD"));
			feats[13] = parseToFloat(tmpDs.getString("OCCP_DV_CD"));

			result.put(prdctCd, feats);
		}

		return result;
	}

	private static float parseToFloat(String value) {
		if (value == null || value.trim().isEmpty()) return 0.0f;
		try {
			return Float.parseFloat(value);
		} catch (NumberFormatException e) {
			return 0.0f;
		}
	}
}
