# 1. 디렉토리 이동
cd /home/batchjobs/test/batch_project

# 2. 오라클 라이브러리 경로 설정
export LD_LIBRARY_PATH=/home/batchjobs/test/batch_project/instantclient_19_29:$LD_LIBRARY_PATH

# 3. 가상환경 활성화
source venv/bin/activate

# 4. 로그에 시작 시간 기록
echo "=========================================="
echo "Batch Started at: $(date)"

# 5. 파이썬 실행 (에러 확인을 위해 -u 옵션 사용: 버퍼 없이 바로 출력)
python -u step3_train_model.py

# 6. 종료 시간 기록
echo "Batch Finished at: $(date)"
echo "=========================================="
