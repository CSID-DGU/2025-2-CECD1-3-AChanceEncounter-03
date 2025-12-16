/**
 * @fileoverview [메인] 로그인 후 메인
 * @file Mybanking.tsx
 * @author GNB
 * @version 1.0.0
 */

import { useEffect, useState } from "react";
import { MainBox } from "@src/components/Box";
import { AcnoCard, ProdCard } from "@src/components/Card";
import { Typography, Box, Tabs, Tab } from "@mui/material";
import Slider from "react-slick";
import DataSet from "@src/assets/io/DataSet";

import { doActionView, GLog, copyText, doActionAll, makeForms } from "@assets/js/common";
import { comma, formatAccountNumber } from "@src/assets/js/common_bank";
import { messageView, progressBar, toast } from '@assets/js/common_ui';

import "slick-carousel/slick/slick.css";
import "slick-carousel/slick/slick-theme.css";
import { openAcnoPopupMenu } from "@src/assets/js/common_popup";
import { useApiEffect } from "@src/hooks/useApiEffect";

// 계좌 정보 인터페이스
interface Account {
  strType: string;
  type: string;
  acno: string;
  balance: string;
  pdnm: string;
  newDt?: string;        // 신규일자
  expDt?: string;        // 만기일자
  nFunc?: (data?: DataSet) => void;
}

// 추천 상품 인터페이스
interface Product {
  pdcd: string;
  pdnm: string;
  categoty: string;
  pdDesc: string;
  keyword: string[];
  contents1: string;
  contents2: string;
  categoryClass: string;
}

// 공지사항 인터페이스
interface Notice {
  title: string;
  content: string;
}

// 슬라이드 인디케이터 (타원형 & 원형)
const CustomDots = ({ dots }: { dots: React.ReactNode }) => (
  <Box sx={{ display: "flex", justifyContent: "center", mt: 1 }}>
    <ul style={{ display: "flex", padding: 0, listStyle: "none" }}>{dots}</ul>
  </Box>
);

const Mybanking = () => {
  const [tabValue, setTabValue] = useState(0);
  const [accountList, setAccountList] = useState<Account[]>([]);
  const [accountList2, setAccountList2] = useState<Account[]>([]);  //예적금금
  const [loanList, setLoanList] = useState<Account[]>([]);
  const [productList, setProductList] = useState<Product[]>([]);
  const [noticeList, setNoticeList] = useState<Notice[]>([]);
  const [productComment, setProductComment] = useState<string>("");

  //페이지 마운트시 API 로드
  useApiEffect({
    //[1-1] 계좌 조회
    acnoSelect: makeForms("COM0000SC", {
      txGbnCd: "M",
      ACCO_KNCD: "9"
    }),
    //[2-1] 상품 조회
    productSelect: makeForms("COM0000SC", {
      txGbnCd: "P"
    }),
    //[3-1] 공지사항 조회
    noticesSelect: makeForms("COM0000SC", {
      txGbnCd: "N"
    }),
  },(resData) => {
    //[1-2] 계좌조회 결과 데이터 전달   오류시 안내메세지 출력력
    if (resData.acnoSelect.header.respCd === 'N00000') {
      const accounts = resData.acnoSelect.data.getList<{ ACNO: string; ACCO_KNCD: string; ACNT_BLNC: string; PROD_NM:string; NEW_DT?:string; EXPT_DT?:string}>("OUT_REC").map(acc => ({
        strType : acc.ACCO_KNCD === "4" ? "여신" : acc.ACCO_KNCD === "1" ? "입출금" : "예적금",
        type: acc.ACCO_KNCD,
        acno: formatAccountNumber(acc.ACNO),
        balance: comma(acc.ACNT_BLNC),
        pdnm: acc.PROD_NM, 
        newDt: acc.NEW_DT?acc.NEW_DT:"",    
        expDt: acc.EXPT_DT?acc.EXPT_DT:"" 
      }));
      // 계좌 유형별로 분리 (1,2,3 : 수신 / 4:여신)
      setAccountList(accounts.filter(acc => acc.type === "1")); //입출금
      setAccountList2(accounts.filter(acc => ["2", "3"].includes(acc.type))); //예적금
      setLoanList(accounts.filter(acc => acc.type === "4"));
    }
    else{
      messageView(resData.acnoSelect.header.respMsg);
    }

    //[2-2] 추천상품 결과 데이터 전달    오류 미처리
    if (resData.productSelect.header.respCd === 'N00000') {
      setProductComment(resData.productSelect.data.getString("comment"))
      setProductList(resData.productSelect.data.getList<{ PRDCT_CD: string; PRDCT_NM: string; PRDCT_CLS_CD: string; SMR_DC_CNTN: string; SALE_STR_DT: string; SALE_END_DT: string }>("prdList")
      .map(prod => ({
        pdcd: prod.PRDCT_CD,
        pdnm: prod.PRDCT_NM,
        categoty: prod.PRDCT_CLS_CD, // 백엔드 수정 부분
        pdDesc: prod.SMR_DC_CNTN, // 백엔드 수정 부분
        keyword: ["추천", "금융상품"],
        contents1: "",
        contents2: "",
        categoryClass: ""
      })));
    }

    //[3-2] 공지사항 결과 데이터 전달    오류 미처리
    if (resData.noticesSelect.header.respCd === 'N00000') {
      setNoticeList(resData.noticesSelect.data.getList<{ PBANC_TTL_NM: string; PBANC_CNTN: string }>("pbancList")
      .map(notice => ({
        title: notice.PBANC_TTL_NM,
        content: "",
      })))
    }
    progressBar(false);
  });


  
  // 슬라이드 설정
  const sliderSettings = {
    dots: true,
    infinite: false,
    speed: 500,
    slidesToShow: 1,
    slidesToScroll: 1,
    arrows: false,
    appendDots: (dots: React.ReactNode) => <CustomDots dots={dots} />,
    customPaging: (i: number) => (
      <Box sx={{ width: i === 0 ? 20 : 10, height: 10, backgroundColor: i === 0 ? "gray" : "lightgray", borderRadius: "50%", margin: "0 4px" }} />
    ),
  };

  // 버튼구분에 따른 동작 service 제어
  const handleClickService = async (action: string, accountData: any) => {
    console.log(action);
    switch (action) {
      case "거래내역": 
        doActionView("/inq/INQ002.view", accountData);
        break;
      case "상세정보":
        GLog.d("상세정보 onclick !! ");
        break;
       case "이체":
        const transfer = new DataSet()
        transfer.putString("acno", accountData.acno)
        doActionView("/tnf/TNF001.view", transfer);
        break;
      case "상환":
        GLog.d("상환버튼 onclick !! ");
        // doActionView("/loan/repayment", accountData);
       break;
    }
  };

  return (
    <MainBox>
      {/* 탭 메뉴 */}
      <Tabs
        value={tabValue}
        onChange={(_, newValue) => setTabValue(newValue)}
        variant="fullWidth"
        sx={{
          mb: 2,
          "& .MuiTabs-indicator": { backgroundColor: "#612AD0" },
          "& .MuiTab-root": { fontWeight: "bold", color: "gray" },
          "& .Mui-selected": { color: "#612AD0 !important" },
        }}
      >
        <Tab label="예적금" />
        <Tab label="대출" />
      </Tabs>
      
      {/* 입출금 계좌 */}
      {tabValue === 0 && (
        <Slider {...sliderSettings}>
          {accountList.map((account, index) => (
            <Box key={index} sx={{ padding: "5px" }}>
              
              <AcnoCard
                items={[
                  {
                    type: account.type,
                    acno: account.acno,
                    balance: account.balance,
                    pdnm: account.pdnm,
                    nFunc: handleClickService,
                    onSetting: ()=>{
                      openAcnoPopupMenu(account.acno,'account');
                    },
                    onCopy: ()=>{
                      copyText(account.acno)
                      toast(account.acno+' 복사했습니다.',3000)
                    }
                  }
                ]}
              />

            </Box>
          ))}
        </Slider>
      )}

      {/* 예적금 계좌 */}
      {tabValue === 0 && (
        <Slider {...sliderSettings}>
          {accountList2.map((account, index) => (
            <Box key={index} sx={{ padding: "5px" }}>
              
              <AcnoCard
                items={[
                  {
                    type: account.type,
                    acno: account.acno,
                    balance: account.balance,
                    pdnm: account.pdnm,
                    nFunc: handleClickService,
                    onSetting: ()=>{
                      openAcnoPopupMenu(account.acno,'dep', account.type, account.expDt, account.newDt, account.pdnm, String(account.balance));
                    },
                    onCopy: ()=>{
                      copyText(account.acno)
                      toast(account.acno+' 복사했습니다.',3000)
                    }
                  }
                ]}
              />

            </Box>
          ))}
        </Slider>
      )}

      {/* 여신 계좌 */}
      {tabValue === 1 && (
        <Slider {...sliderSettings}>
          {loanList.map((account, index) => (
            <Box key={index} sx={{ padding: "5px" }}>
              <AcnoCard
                items={[
                  {
                    type: account.type,
                    acno: account.acno,
                    balance: account.balance,
                    pdnm: account.pdnm,
                    nFunc: handleClickService,
                    onSetting: ()=>{
                      openAcnoPopupMenu(account.acno,'loan');
                    },
                    onCopy: ()=>{
                      copyText(account.acno)
                      toast(account.acno+' 복사했습니다.',3000)
                    }
                  }
                ]}
              />
            </Box>
          ))}
        </Slider>
      )}


      {/* 추천 상품 */}
      <Typography variant="h6" sx={{ fontWeight: "bold", mt: 3, mb: 2 }}>{productComment}</Typography>
      <Slider {...sliderSettings}>
        {productList.map((product, index) => (
          <Box key={index} sx={{ padding: "5px" }}>
            <ProdCard items={[
              {...product}
             ]} />
          </Box>
        ))}
      </Slider>


      {/* 공지사항 (새로운 소식) */}
      <Typography variant="h6" sx={{ fontWeight: "bold", mt: 3, mb: 2 }}>
        공지사항
      </Typography>

      <Box sx={{ backgroundColor: "#F8F9FA", borderRadius: "12px", padding: "12px", mt: 2 }}>
        {noticeList.length > 0 ? (
          noticeList.slice(0, 3).map((notice, index) => (
            <Box key={index} sx={{ display: "flex", alignItems: "center", mb: 1 }}>
              {/* 아이콘 추가 가능 */}
              <Box sx={{ width: 6, height: 6, borderRadius: "50%", backgroundColor: "#612AD0", mr: 1 }} />
              <Typography variant="body2">{notice.title}</Typography>
            </Box>
          ))
        ) : (
          <Typography variant="body2" sx={{ textAlign: "center" }}>
            공지사항이 없습니다.
          </Typography>
        )}
      </Box>
    </MainBox>
  );
};

export default Mybanking;
