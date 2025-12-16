package app.com.bc;

import java.io.Reader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ahnlab.v3mobileplus.webinterface.LicenseKeyManager;
import com.ahnlab.v3mobileplus.webinterface.V3MobileManager;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxSequence;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import frame.galaxy.common.authorization.SessionManager;
import frame.galaxy.common.util.CertUtils;
import frame.galaxy.common.util.DateUtils;
import frame.galaxy.common.util.StringUtils;
import frame.galaxy.core.context.GApplicationContext;
import frame.galaxy.core.dao.DBManager;
import frame.galaxy.core.exception.ApplicationException;
import frame.galaxy.core.io.DataSet;
import frame.galaxy.core.io.RecordSet;
import frame.galaxy.core.net.impl.FsbOpenApiSender;
import oracle.sql.CLOB;

/**
 *[공통] 공통 기능 BC
 * @author gnbsoftec
 */
@Component
public class COM0000BC {

	private static final Logger log = LoggerFactory.getLogger(COM0000BC.class);

	@Autowired
	private DBManager dbManager; 

	@Autowired
	private COM0001BC com0001bc;

	@Autowired
	private FsbOpenApiSender fsbOpenApiSender;

	@Value("v3.web.lic")
	private String v3Lic;

	@Value("model.path")
	private String modelPath;

	private Map<String, String> productNameMap = new HashMap<>();
	
	/**
	 * 로그인 처리
	 */
	public DataSet login(GApplicationContext ctx, DataSet reqDs) throws ApplicationException{

		DataSet resDs = new DataSet();

		String loginType = reqDs.getString("loginType");		// 로그인타입(I: ID/PW 로그인, C: 인증서로그인, S: 간편인증, A: 본인인증, R: 세션갱신, Z: 인증성공여부확인)
		String rnno = "";				// 고객실명
		String csno = "";				// 중앙회고객번호
		String usrId = "";				// 전자금융ID
		String custNm = "";				// 고객명
		String mainAcno = "";			// 대표계좌
		String loginTrnType = "";		// 로그인거래타입
		String lastLoginDtm = "";		// 마지막로그인시각

		if (log.isDebugEnabled()) log.debug("LOGIN START:"+DateUtils.getDate("yyyyMMddHH24miss"));

		if ("I".equals(loginType)) /** ID/PW 로그인 */{
			DataSet fsbReqDs = new DataSet();
			fsbReqDs.setBT_ID("API_LogLogn0100_01"); // API_LogLogn0100_01:로그인 처리 (ID/PW)
			fsbReqDs.put("USER_ID_12", reqDs.getString("USER_ID_12"));	// 사용자ID
			fsbReqDs.put("USR_PWD", reqDs.getString("USR_PWD"));		// 사용자비밀번호
			DataSet fsbResDs = fsbOpenApiSender.send(ctx, fsbReqDs);

			if ("SUCCESS".equals(fsbResDs.getString("API_RS_MSG"))) {
				rnno = fsbResDs.getString("RNNO");							// 실명번호
				custNm = fsbResDs.getString("USER_NM");						// 고객명
				csno = fsbResDs.getString("CSNO");							// 고객번호
				usrId = reqDs.getString("USER_ID_12");						// 사용자ID
			} else {
				// 비밀번호 5회 처리용 1회성 인증등록
				ctx.regOTA();

				// 비밀번호 5회 처리 오류시 오류해제 세션값 세팅
				ctx.getSessionManager().set(SessionManager.LOGIN_KEY, "USR_ID", reqDs.getString("USER_ID_12"));

				resDs.put("API_RS_MSG", fsbResDs.getString("API_RS_MSG"));	// API 결과 메시지
				return resDs; // 로그인 오류메시지 출력위해 응답리턴
			}
		} else if ("C".equals(loginType)) /** 인증서로그인 */{
			String signedData = reqDs.getString("signedData");			// 서명데이터
			String vidRandom = reqDs.getString("vidRandom");			// 랜덤값

			DataSet certDs = null;
			try {
				certDs = CertUtils.getCertData(ctx, signedData, vidRandom);
			} catch (Exception e) {
				new ApplicationException(ctx,"E00001",e);
			}

			//  디지털뱅킹 공인인증서정보조회
			DataSet fsbReqDs = new DataSet();
			fsbReqDs.setBT_ID("API_comCert0100_02"); 					// API_comCert0100_02:디지털뱅킹 공인인증서정보조회
			fsbReqDs.put("BANK_CD", ctx.getSbcd()); 				 	// 저축은행코드 
			fsbReqDs.put("CERT_SERIAL", certDs.getString("SERIAL")); 	// 인증서시리얼코드
			fsbReqDs.put("DN", certDs.getString("DN")); 			 	// 인증서DN
			DataSet fsbResDs = fsbOpenApiSender.send(ctx, fsbReqDs);

			if ("SUCCESS".equals(fsbResDs.getString("API_RS_MSG"))) {
				rnno = ""; 										// 실명번호 : 없음
				custNm = fsbResDs.getString("CUST_NM");			// 고객명
				csno = fsbResDs.getString("CSNO");				// 고객번호
				usrId = fsbResDs.getString("USER_ID");			// 사용자ID

			} else {
				resDs.put("API_RS_MSG", fsbResDs.getString("API_RS_MSG"));	// API 결과 메시지
				return resDs; // 로그인 오류메시지 출력위해 응답리턴
			}

		} else if ("S".equals(loginType)) /** 간편인증 */{
			if ("Y".equals(reqDs.getString("TEST_FLAG")) && ctx.isDevServer()) {

				loginTrnType = reqDs.getString("loginTrnType");		// 로그인타입
				csno = reqDs.getString("CSNO");						// 고객번호
			} else {
				// 고객번호만 존재
				String tokenId = ctx.getSessionManager().get(SessionManager.AUTH_KEY, "TOKEN_ID"); 	// 사설(간편인증) 로그인 결과 TOKEN_ID
				csno = tokenId.split("[$]")[3].split("#")[0]; 										// 고객번호	
			}
		} else if ("A".equals(loginType)) /** 본인인증 */{
			SessionManager sessionManager = ctx.getSessionManager();
			rnno = sessionManager.getSSN(); // 실명번호

			if (StringUtils.isNotEmpty(rnno)) {
				DataSet fsbReqDs = new DataSet();
				fsbReqDs.setBT_ID("API_NomAcco0400_02"); 	// API_NomAcco0400_02:고객정보조회
				fsbReqDs.put("NRID", rnno);					// 주민번호
				fsbReqDs.put("NECT_PROD_DTLS_KNCD", "");	// 비대면상품세부종류코드
				fsbReqDs.put("NECT_INTG_PDCD", "");			// 비대면통합상품코드
				DataSet fsbResDs = fsbOpenApiSender.send(ctx, fsbReqDs);

				resDs.put("API_RS_MSG", fsbResDs.getString("API_RS_MSG"));	// API 결과 메시지

				if ("SUCCESS".equals(fsbResDs.getString("API_RS_MSG"))) {
					custNm = fsbResDs.getString("CUST_NM");	// 고객명
					csno = fsbResDs.getString("CSNO");		// 고객번호
					usrId = ""; 							// 사용자ID: 없음
				}
			} else {
				resDs.put("LOGIN_YN", "N");								// 로그인여부
				resDs.put("API_RS_MSG", "본인인증 후 이용가능한 거래입니다");	// API 결과 메시지
				return resDs; // 로그인 오류메시지 출력위해 응답리턴
			}

		} else if ("R".equals(loginType)) /** 세션갱신 로그인 */{
			custNm = ctx.getCUST_NM();	// 고객명
			csno = ctx.getCSNO();		// 고객번호
			rnno = ctx.getSSN();		// 주민번호
			usrId = ctx.getLogUSR_ID();	// 사용자ID
			lastLoginDtm = ctx.getSessionManager().get(SessionManager.LOGIN_KEY, "LAST_LOGIN_DTM");	// 마지막로그인시각
		} else if ("SC".equals(loginType)) /** 세션 체크 및 갱신 */{
			try {
				String loginEnd = reqDs.getString("loginEnd");				// 로그인종료여부
				resDs.put("IS_LOGIN", ctx.getSessionManager().isLogin()); 	// 로그인여부

				// 고객DS 업데이트
				if(ctx.getSessionManager().isLogin() && "N".equals(loginEnd)) {

					DataSet loginDs = ctx.getSessionManager().getDataSet(SessionManager.LOGIN_KEY);

					String keyToCheck = "LOGIN_END";  // 로그인후처리완료
					int timeout = 10000;  // 최대 대기 시간 (5초)
					int interval = 500;   // 체크 간격 (0.5초)
					long startTime = System.currentTimeMillis();

					while (!"Y".equals(loginDs.getString(keyToCheck))) {
						if (System.currentTimeMillis() - startTime > timeout) {
							return resDs;
						}
						Thread.sleep(interval);  // 일정 시간 대기 후 다시 확인
					}

					loginDs = ctx.getSessionManager().getDataSet(SessionManager.LOGIN_KEY);
					resDs.putAll(loginDs);
				}
			} catch (Exception e) {
				new ApplicationException(ctx,"E00001",e);
			}
			return resDs; 
		} else if ("Z".equals(loginType)) /** 인증성공여부확인 */{
			resDs.put("SUCC_YN", ctx.getSessionManager().get(SessionManager.AUTH_KEY, "SUCC_YN")); 	// 성공여부
			return resDs; 
		}



		// 중앙회 고객번호가 나왔다면 로그인 후속처리
		if (StringUtils.isNotEmpty(csno)) /** 중앙회고객 */{

			try {

				if ("A".equals(loginType)) loginType = "C"; 		// 인증서
				if ("S".equals(loginType)) loginType = "P"; 		// 인증서
				String sysDvCd = "";								// 시스템구분코드(A:APP, M:MWEB, W:WEB)
				if(ctx.isNative()) sysDvCd = "A";
				else sysDvCd = "M";

				DataSet userLoginDs = new DataSet();
				userLoginDs.put("CSNO", csno);								// 고객번호
				userLoginDs.put("SYS_DV_CD", sysDvCd);						// 시스템구분코드
				userLoginDs.put("LGIN_TYPE_CD", loginType);					// 로그인타입
				userLoginDs.put("LGIN_ID", usrId);							// 로그인ID
				userLoginDs.put("USR_NM", custNm);							// 사용자명
				userLoginDs.put("DVCE_TYPE_CD", ctx.getDeviceType());		// 디바이스타입
				userLoginDs.put("DVCE_MODEL_VL", reqDs.getString("UUID"));	// TODO 장치연식값
				userLoginDs.put("SESSION_ID", ctx.getSessionID());			// 세션ID
				userLoginDs.put("SRV_IP_ADDR", ctx.getServerIpAddr());		// 서버IP주소
				userLoginDs.put("ACSS_IP_ADDR", ctx.getIpAddr());			// 접속IP
				userLoginDs.put("DEL_YN", "N");								// 삭제여부
				userLoginDs.put("FRS_RG_GUID", ctx.getIpAddr());			// 최초등록GUID
				userLoginDs.put("FRS_RG_USR_NO", "99999999");				// 최초변경사용자(999999:채널)
				userLoginDs.put("LAST_CH_GUID", ctx.getGuid());				// 최종변경GUID
				userLoginDs.put("LAST_CH_USR_NO", "99999999");				// 최종변경사용자(999999:채널)

				//mainAcno = dbManager.getDataSet(ctx, "APP_MAIN_ACCOUNT.s001", loginDs).getString("MAIN_ACNO");						// TODO 대표계좌

				if (log.isDebugEnabled()) log.debug("userLoginDs[{}]", userLoginDs);

				DataSet lastLoginDs = dbManager.getDataSet(ctx, "APP_USRLGIN_I.s001", userLoginDs);
				lastLoginDtm = lastLoginDs.getString("LAST_LOGIN_DTM"); 	// 마지막로그인 시간	
				userLoginDs.put("LAST_LGIN_DTM", lastLoginDtm);				// 마지막로그인일시

				dbManager.execute(ctx, "APP_USRLGIN_I.i001", userLoginDs); 	// 로그인 등록
				dbManager.execute(ctx, "APP_USRLGIN_H.i001", userLoginDs); 	// 로그인이력 등록

			} catch (ApplicationException e) {
				log.error("LOGIN DB ApplicationException", e);
			} catch (Exception e) {
				log.error("LOGIN DB Exception", e);
			}

			// 후처리 전 임시로그인
			DataSet loginDs = new DataSet();
			loginDs.put("LOGIN_END", "N");					    // 로그인종료
			loginDs.put("CSNO", csno);							// 고객번호
			loginDs.put("USR_ID", usrId);						// 사용자ID
			loginDs.put("CUST_NM", custNm);						// 고객명
			loginDs.put("LAST_LOGIN_DTM", lastLoginDtm);		// 마지막로그인시각
			loginDs.put("MAIN_ACNO", mainAcno);     			// 대표계좌
			loginDs.put("LOGIN_TYPE", loginTrnType);     		// 로그인타입

			ctx.getSessionManager().login(loginDs);

			resDs.put("API_RS_MSG", "SUCCESS");		// 성공
			resDs.put("LOGIN_YN", "Y"); 			// 로그인여부
			resDs.putAll(loginDs);

			if (log.isDebugEnabled()) log.debug("LOGIN END:"+DateUtils.getDate("yyyyMMddHH24miss"));

			// TODO 임시로 동기설정
			// 비동기 실행 
			com0001bc.loginAddPrc(ctx, reqDs);

			
			
		} else {
			resDs.put("CSNO", csno); 		// 고객번호
			resDs.put("USR_ID", usrId); 	// 사용자ID
			resDs.put("LOGIN_YN", "N");		// 로그인여부
			resDs.put("API_RS_MSG", "저축은행 고객이 아닙니다 계좌개설 후 이용가능합니다");
		}
		return resDs;
	}

	/**
	 * 앱인증
	 */
	public DataSet appAuth(GApplicationContext ctx, DataSet reqDs) throws ApplicationException{

		DataSet resDs = new DataSet();

		try {

			String txGbnCd = reqDs.getString("txGbnCd"); // R.랜덤키발급 M.mOTP인증 P.APP인증 A.스마트앱인증

			DataSet fsbReqDs = new DataSet();

			if ("R".equals(txGbnCd)) /** 랜덤키발급 */{
				fsbReqDs.setBT_ID("API_PA_TxCreate"); // API_PA_TxCreate 디지털뱅킹 랜덤발급요청(트랜잭션ID요청)
				resDs= fsbOpenApiSender.send(ctx, fsbReqDs);

			} else if ("M".equals(txGbnCd)) /** mOTP인증 */{
				fsbReqDs.setBT_ID("API_PA_OtpTxSign"); // API_PA_OtpTxSign:mOTP 사설인증  Otp 비교용 서명검증
				fsbReqDs.put("ID", reqDs.getString("ID"));
				fsbReqDs.put("SIG", reqDs.getString("SIG"));
				fsbReqDs.put("MSG", reqDs.getString("MSG"));
				fsbReqDs.put("OTP_LEN", 0);
				fsbReqDs.put("BKCD", ctx.getSbcd());
				fsbReqDs.put("CSNO", ctx.getCSNO());
				fsbReqDs.put("PA_TRN_TYPE", "2"); // 검증 타입 저축은행
				fsbReqDs.put("USR_ID", ctx.getUSR_ID());
				resDs = fsbOpenApiSender.send(ctx, fsbReqDs); 	// MOTP 서명검증

				if ("Y".equals(resDs.getString("VLD"))) /** mOTP 서명정상 */{
					ctx.regOTA(); // 1회성인증등록
				}

			} else if ("P".equals(txGbnCd) || "A".equals(txGbnCd)) /** APP인증, 스마트앱인증 */{
				fsbReqDs.setBT_ID("API_PA_TxSign"); // API_PA_TxSign 디지털뱅킹 사설인증서명검증
				fsbReqDs.put("ID", reqDs.getString("ID"));
				fsbReqDs.put("SIG", reqDs.getString("SIG"));
				fsbReqDs.put("MSG", reqDs.getString("MSG"));
				fsbReqDs.put("OTP_LEN", 0);
				fsbReqDs.put("BKCD", ctx.getSbcd());
				if ("P".equals(txGbnCd)) {
					fsbReqDs.put("PA_TRN_TYPE", "2"); // 거래구분 1.중앙회 2.저축은행
				} else {
					fsbReqDs.put("PA_TRN_TYPE", "2"); // 검증 타입 저축은행 거래구분 1.중앙회 2.저축은행
				}
				fsbReqDs.put("USR_ID", ctx.getLogUSR_ID()); // 필수아님
				resDs = fsbOpenApiSender.send(ctx, fsbReqDs); 

				// VLD true / false
				if ("true".equals(resDs.getString("VLD"))) /** 서명정상 */{

					ctx.getSessionManager().set(SessionManager.AUTH_KEY, "TOKEN_ID", resDs.getString("TOKEN_ID"));
					ctx.regOTA(); // 1회성인증등록

					// 매번 조회하지 않도록 인증내역이 존재하면 조회 SKIP 
					DataSet authDs = ctx.getSessionManager().getDataSet(SessionManager.AUTH_KEY);
					DataSet custDs = ctx.getSessionManager().getDataSet(SessionManager.CUST_KEY);
					String appAuthSsn = ctx.getLogSSN();
					if (StringUtils.isEmpty(appAuthSsn) 
							|| StringUtils.isEmpty(authDs.getString("CUST_NM")) 
							|| StringUtils.isEmpty(custDs.getString("CUST_NM"))) /** 실명번호없이 생성되었거나 인증세션이 생성되지 않았음 조회 및 생성 처리*/{
						String tokenId = resDs.getString("TOKEN_ID"); // 사설(간편인증) 로그인 결과 TOKEN_ID
						String csno = tokenId.split("[$]")[3].split("#")[0]; // 고객번호

						DataSet fsbCustReqDs = new DataSet();
						fsbCustReqDs.setBT_ID("API_CusIqmo0200"); 	// 고객정보조회
						fsbCustReqDs.put("PROC_FLAG", "01"); 		// 처리구분 01.조회
						fsbCustReqDs.put("CSNO", csno); 	 		// 고객번호
						DataSet fsbCustResDs = fsbOpenApiSender.send(ctx, fsbCustReqDs);

						String ssn = fsbCustResDs.getString("RNNO_C14");
						String ssn1 = ssn.substring(0, 6);
						String custNm = fsbCustResDs.getString("CUST_NM");
						String gender = ssn.substring(6, 7);
						String birthDay = Integer.parseInt(gender) > 2 ? "20" : "19";
						birthDay = birthDay + ssn1;
						String phoneNo = fsbCustResDs.getString("PRTB_TSNO") + fsbCustResDs.getString("PRTB_TFNO") + fsbCustResDs.getString("PRTB_TVNO");

						authDs.put("SSN", ssn);
						authDs.put("SSN1", ssn1);
						authDs.put("CI", "");
						authDs.put("CUST_NM", custNm);
						authDs.put("GENDER", gender);
						authDs.put("BIRTH_DAY", birthDay);
						authDs.put("PHONE_NO", phoneNo);
						authDs.put("TELECOM", "");
						authDs.put("AUTH_CD", "S000700013"); // 인증수단 S000700013:핀 
						ctx.getSessionManager().regAuth(authDs);

						custDs.put("CUST_NM", custNm);
						custDs.put("GENDER", gender);
						custDs.put("SSN1", ssn1);
						custDs.put("BIRTH_DAY", birthDay);
						custDs.put("PHONE_NO", phoneNo);
						custDs.put("TELECOM", "");
						ctx.getSessionManager().regCust(custDs);
					}
				}
			}
			return resDs;

		} catch (ApplicationException e) {
			throw e;
		} catch (Exception e) {
			throw new ApplicationException(ctx, "E00001", e);
		}
	}



	/**
	 * 로그인 처리
	 */
	public DataSet isLogin(GApplicationContext ctx) throws ApplicationException{
		return new DataSet() {{ put("IS_LOGIN",ctx.isLogin()); }};
	}

	/**
	 * 메인 마이뱅킹용 조회
	 * @throws ApplicationException 
	 */
	public DataSet getMainMyBanking(GApplicationContext ctx, DataSet reqDs) throws ApplicationException {

		DataSet resDs = new DataSet();

		String inqAccoKncd = reqDs.getString("ACCO_KNCD", "9"); // 계좌종류 1:요구성, 2:적금, 3:정기예금, 4:대출, 9:전체 , D:수신전체(내부코드)
		String accoKncd = inqAccoKncd;
		if ("D".equals(inqAccoKncd) || "M".equals(inqAccoKncd)) accoKncd = "9";

		reqDs.setBT_ID("API_InqOvvi0100_01"); // 전계좌조회 API_InqOvvi0100_01
		reqDs.put("SBCD", ctx.getSbcd());	 	// 저축은행코드
		reqDs.put("CSNO", ctx.getCSNO());	 	// 고객번호
		reqDs.put("USR_ID", ""); 				// 전자금융ID
		reqDs.put("ACCO_KNCD", accoKncd);	
		reqDs.put("NEXT_DATA_REQT_KEY_VAL", ""); // 페이징 처리 위해 다음 페이지에 조회할 결과 데이터의 키
		DataSet fsbResDs = fsbOpenApiSender.send(ctx, reqDs);

		resDs.putAll(fsbResDs); // 합계 및 건수도 응답
		resDs.put("TOT_DEP_CCNT", resDs.getLong("DMNB_CCNT")+resDs.getLong("TMDP_CCNT")+resDs.getLong("INSV_CCNT"));
		resDs.put("TOT_DEP_AMT", resDs.getLong("DMNB_SAMT")+resDs.getLong("TMDP_SAMT")+resDs.getLong("INSV_SAMT"));
		resDs.put("TOT_LOAN_AMT", fsbResDs.getLong("LOAN_SAMT"));

		RecordSet acnoRec = new RecordSet();
		acnoRec.addAll(fsbResDs.getRecordSet("OUT_REC"));

		// 계좌 종류별로 나눠담기 다음데이터 더있으면 추가조회
		RecordSet rec1 = new RecordSet();
		RecordSet rec4 = new RecordSet();

		if ("D".equals(inqAccoKncd)) /** 수신계좌만 조회 */{
			for (DataSet ds : acnoRec) {
				switch (ds.getString("ACCO_KNCD")) {
				case "1":
					rec1.add(ds);
					break;
				case "2":
					rec1.add(ds);				
					break;
				case "3":
					rec1.add(ds);
					break;
				default:
					break;
				}
			}

		} else if ("4".equals(inqAccoKncd)) /** 여신계좌만조회 */{

			for (DataSet ds : acnoRec) {
				switch (ds.getString("ACCO_KNCD")) {
				case "4":
					String loanProdNm = ds.getString("PROD_NM");
					String LOAN_RPAY_MTH_NM = "원리금균등";
					if (loanProdNm.indexOf("원금균등") > -1) {
						LOAN_RPAY_MTH_NM = "원금균등";
					} else if (loanProdNm.indexOf("만기일시") > -1) {
						LOAN_RPAY_MTH_NM = "만기일시";
					}else if (loanProdNm.indexOf("예적금담보") > -1){
						LOAN_RPAY_MTH_NM = "만기일시";
					}
					ds.put("LOAN_RPAY_MTH_NM", LOAN_RPAY_MTH_NM);
					rec4.add(ds);
					break;
				default:
					break;
				}
			}
		}
		resDs.put("REC1", rec1); // 요구성 적금 예금
		resDs.put("REC4", rec4); // 대출

		return resDs;
	}

	/**
	 * 메인추천 상품 조회 
	 */
	public DataSet getRecommendedProducts(GApplicationContext ctx, DataSet reqDs) throws ApplicationException {
		String csno = ctx.getCSNO();

		RecordSet resRs = new RecordSet();
		DataSet resDs = new DataSet();
		DataSet tmpDs = new DataSet();
		String comment = "추천 상품";

		if(!"".equals(csno)) {
			reqDs.put("CSNO", csno);
			
			int aiCnt = 0;
			////////////////////////////////////////////////////////////////////////////
			// 2. 추천 Top3 계산
			////////////////////////////////////////////////////////////////////////////
			try {
				List<ProductScore> top3 = getTop3Recommendations(ctx, csno);
				aiCnt = top3.size();
				comment = "AI가 추천한 TOP3 상품";

				for (int i = 0; i < top3.size(); i++) {
					ProductScore ps = top3.get(i);
					DataSet record = new DataSet();

					record.put("RANK", i + 1);
					record.put("PRDCT_CD", ps.productId);
					record.put("PRDCT_NM", ps.productName);
					record.put("PROB", ps.probability);

					resRs.add(record);
				}

			} catch (Exception e) {
				log.error("AI상품추천 오류", e);
			}
			
			// AI가 상품 추천 못한 경우
			if(aiCnt == 0) {

				// 고객 속성 조회
				reqDs = dbManager.getDataSet(ctx, "APP_USR_M.s001", reqDs);

				if(!"".equals(reqDs.getString("AGE_CD_NM")))
					comment = reqDs.getString("AGE_CD_NM") + " " + reqDs.getString("SEX_CD_NM")+ reqDs.getString("OCCP_DV_CD_NM") + " 추천 상품";

				// 룰조회
				RecordSet categoryRs = dbManager.getRecordSet(ctx, "PSN_CATEGORY_I.s001", reqDs);

				// 추천상품 조회
				if(categoryRs.size() > 0) {
					for(int i=0; i< categoryRs.size(); i++) {
						tmpDs.put("RUL_SN", categoryRs.get(i).getString("RUL_SN"));
						RecordSet prdRs = dbManager.getRecordSet(ctx, "PRD_INFO_M.s003", tmpDs);
						resRs.addAll(prdRs);
					}
				}
			}
		}

		// 추천상품이 없는경우 전체노출
		if(resRs.size() == 0) {
			resRs = dbManager.getRecordSet(ctx, "PRD_INFO_M.s001", reqDs);
		}

		resDs.put("prdList", resRs);
		resDs.put("comment", comment);

		return resDs;
	}

	/**
	 * TODO 공지사항 조회
	 */
	public RecordSet getNotices(GApplicationContext ctx, DataSet reqDs) throws ApplicationException {
		RecordSet rs = dbManager.getRecordSetCache(ctx, "APP_PBANC_M.s001", "APP_PBANC_M", reqDs);
		RecordSet result = new RecordSet();

		for (DataSet row : rs) {
			DataSet newRow = new DataSet();
			for (String key : row.keySet()) {
				Object value = row.get(key);

				if (value instanceof CLOB) {
					// CLOB → String 변환
					newRow.put(key, clobToString((CLOB) value));
				} else {
					newRow.put(key, value);
				}
			}
			result.add(newRow);
		}
		return result;
	}

	/**
	 * 공지사항 메인 팝업 조회
	 */
	public RecordSet getMainNotiPop(GApplicationContext ctx, DataSet reqDs) throws ApplicationException {
		return dbManager.getRecordSetCache(ctx, "APP_POPUP_M.s001", "APP_POPUP_M", reqDs);
	}

	/**
	 * 세션갱신 
	 */
	public DataSet refresh(GApplicationContext ctx) {
		ctx.getSessionManager().refresh();
		return new DataSet(){{
			put("API_RS_MSG", "SUCCESS");
		}};
	}

	/**
	 * 로그아웃 
	 */
	public DataSet logout(GApplicationContext ctx) {
		ctx.getSessionManager().logout();
		return new DataSet(){{
			put("API_RS_MSG", "SUCCESS");
		}};
	}


	/**
	 * 로그적재 
	 */
	@Async("asyncExecutor")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void logMng(GApplicationContext ctx, DataSet reqDs) throws ApplicationException {

		try {

			String sessionId = "";
			try {
				sessionId = ctx.getSession().getId();
			} catch (NullPointerException e){}

			if (StringUtils.getByteLength(reqDs.getString("PRCS_RSLT_CNTN")) > 400) {
				reqDs.put("PRCS_RSLT_CNTN", StringUtils.cutString(reqDs.getString("PRCS_RSLT_CNTN"), 200));	
			}

			String activeEnv = "";
			if(ctx.isNative()) activeEnv = "APP";
			else activeEnv = "MWB";

			reqDs.put("UUID", ctx.getGuid());							// UUID
			reqDs.put("TR_DT", DateUtils.getDate()); 					// 거래일자
			reqDs.put("TR_HHMM", DateUtils.getShortestTimeString()); 	// 거래시각
			reqDs.put("CHNL_CD", activeEnv);							// 채널구분코드
			reqDs.put("API_TYPE_CD", reqDs.getString("API_TYPE_CD")); 	// API타입코드
			reqDs.put("LGIN_ID", ctx.getLogUSR_ID());					// 로그인ID
			reqDs.put("CSNO", ctx.getLogCSNO());						// 고객번호
			reqDs.put("DVCE_TYPE_CD", ctx.isIOS()?"I":"A");				// 디바이스타입
			reqDs.put("DVCE_MODEL_VL", ctx.isIOS()?"IOS":"Android"); 	// 디바이스모델
			reqDs.put("SESSION_ID", sessionId);							// 세션ID
			reqDs.put("SRV_IP_ADDR", ctx.getServerIpAddr());			// 서버IP
			reqDs.put("ACSS_IP_ADDR", ctx.getIpAddr());					// 접속IP
			reqDs.put("FRS_RG_GUID", ctx.getGuid());					// 최초등록GUID
			reqDs.put("LAST_CH_GUID", ctx.getGuid());					// 최종변경GUID
			dbManager.execute(ctx, "APP_TRLOG_I.i001", reqDs);

		} catch (ApplicationException e) {
			throw e;
		} catch (Exception e) {
			throw new ApplicationException(ctx, "E00001", e);
		}

	}


	/**
	 * V3 정보 조회
	 * @return
	 */
	public DataSet v3Info() {
		// TODO 예가람 개발 라이센스
		String v3_time = LicenseKeyManager.getInstance().getTimeValueForGettingEncLicenseKey();
		return new DataSet() {{
			put("v3_cryptoKeyVersion",V3MobileManager.getInstance().getCryptoKeyVersion());
			put("v3_time",v3_time);
			put("v3_licKey",LicenseKeyManager.getInstance().getEncLicenseKey(v3Lic, v3_time));
		}};
	}


	/**
	 * V3 정보 복호화  
	 * @return
	 */
	public DataSet v3Dec(DataSet reqDs) {
		DataSet resDs = new DataSet();
		switch(reqDs.getString("TYPE")) {
		//루팅감지
		case "RC":
			resDs.put("str", V3MobileManager.getInstance().getRootCheckerResult(
					reqDs.getString("DATA")
					, reqDs.getString("DT")
					, 10));
			break;
			//위협감지 
		case "VC":
			resDs.put("str", V3MobileManager.getInstance().getThreatAppResult(
					reqDs.getString("DATA")
					, reqDs.getString("DT")
					, 10));
			break;
		}
		return resDs;
	}

	/**
	 * 메뉴 조회
	 */
	public RecordSet getMenuList(GApplicationContext ctx) throws ApplicationException {
		return dbManager.getRecordSetCache(ctx, "APP_MNU_M.s001", "MENU_LIST", new DataSet());
	}
	
	private String clobToString(CLOB clob) {
		if (clob == null) return "";
		try {
			Reader reader = clob.getCharacterStream();
			StringBuilder sb = new StringBuilder();
			char[] buffer = new char[2048];
			int bytesRead;
			while ((bytesRead = reader.read(buffer)) != -1) {
				sb.append(buffer, 0, bytesRead);
			}
			reader.close();
			return sb.toString();
		} catch (Exception e) {
			throw new RuntimeException("CLOB 변환 실패", e);
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
			log.warn("CSNO={} 에 대한 상품 피처가 없습니다.", csno);
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
				OrtSession session = env.createSession(modelPath, new OrtSession.SessionOptions())) {

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
					log.error("Unexpected ONNX output type: {}", outputValue.getClass().getName());
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
		RecordSet rs = dbManager.getRecordSet(ctx, "ML_FEATURE_STORE.s001", reqDs);

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
