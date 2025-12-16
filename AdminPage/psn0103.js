// CMD 목록
var CMD_INQ = "PSN010206";		// 조회

/**
 * [공통이벤트] 페이지 로드
 * */
function onPageLoad() {
	
}

/**
 * [공통이벤트] 업무 버튼 클릭
 * */
async function onCmdClick(cmd, id, cmdtype) {
	
	// 전송
    dolphinweb.sendRequest(cmd);
}

/**
 * [공통이벤트] 정상 응답 처리
 * */
function onCmdSuccess(cmd, respCode, respMsg) {
	
	
	if (cmd != CMD_INQ) {
		// 조회
	    dolphinweb.sendRequest(CMD_INQ);
	}
}

/**
 * [공통이벤트] 오류 응답 처리
 * @param cmd: 거래코드
 * @param respCode: 응답코드
 * @param respMsg: 정상/오류 메시지
 * */
function onCmdError(cmd, respCode, respMsg) {
	
}

/**
 * =============================================================================
 * User Defined
 * =============================================================================
 * */

/**
 * 상품목록 더블클릭
 * */
function T1_CellDblClick(grid, rowIndex, column) {

//	var data = {};
	
	// 상품코드
//	if (column == "T1_PRDCT_CD") {
//		// 모달 팝업으로 열기: 응답 받을 수 있음.
//		dolphinweb.openModal("PRD0201", data, function(response){
//			
//			dolphinweb.selectGridRow(grid, rowIndex);	// 체크 활성화
//			dolphinweb.setGridValue(grid, rowIndex, "T1_PRDCT_DV_CD", response.PRDCT_DV_CD);	// 상품구분
//			dolphinweb.setGridValue(grid, rowIndex, "T1_PRDCT_CLS_CD", response.PRDCT_CLS_CD);	// 분류코드
//			dolphinweb.setGridValue(grid, rowIndex, "T1_PRDCT_CLS_NM", response.PRDCT_CLS_NM);	// 분류코드명
//			dolphinweb.setGridValue(grid, rowIndex, "T1_PRDCT_CD", response.PRDCT_CD);			// 상품코드
//			dolphinweb.setGridValue(grid, rowIndex, "T1_PRDCT_NM", response.PRDCT_NM);			// 상품명
//			
//		}, 1000, 700);
//	}
}

/**
 * 속성목록 더블클릭
 * */
function T2_CellDblClick(grid, rowIndex, column) {

//	var data = {};
//	data.TBL_NM = "APP_USR_M"; // 스마트뱅킹_사용자_기본
//	
//	// 테이블명 or 추출속성명
//	if (column == "T2_TBL_NM" || column == "T2_EXTC_ATR_NM") {
//		// 모달 팝업으로 열기: 응답 받을 수 있음.
//		dolphinweb.openModal("FWK9998", data, function(response){
//			
//			dolphinweb.selectGridRow(grid, rowIndex);	// 체크 활성화
//			dolphinweb.setGridValue(grid, rowIndex, "T2_TBL_NM", response.TBL_NM);				// 테이블명
//			dolphinweb.setGridValue(grid, rowIndex, "T2_TBL_CMMT_VL", response.TBL_CMMT_VL);	// 테이블주석
//			dolphinweb.setGridValue(grid, rowIndex, "T2_COL_CMMT_VL", response.COL_CMMT_VL);	// 용어
//			dolphinweb.setGridValue(grid, rowIndex, "T2_EXTC_ATR_NM", response.COL_NM);			// 용어영문
//			dolphinweb.setGridValue(grid, rowIndex, "T2_CD_DMN_ID", response.CD_DMN_ID);		// 코드도메인
//			dolphinweb.setGridValue(grid, rowIndex, "T2_EXTC_ATR_CNTN", response.CD);			// 코드
//			dolphinweb.setGridValue(grid, rowIndex, "T2_CD_NM", response.CD_NM);				// 코드명
//			
//			
//		}, 1000, 700);
//	}
}
