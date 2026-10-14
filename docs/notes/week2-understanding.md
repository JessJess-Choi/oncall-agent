# 2주차 이해 단계: 대화형 Claude Code로 툴 서버 써 보기

목적: 러너(2주차 B)를 만들기 전에, 에이전트가 우리 툴을 실제로 어떻게 쓰는지 눈으로 본다.
주의: 대화형 모드는 파일 읽기·셸 같은 기본 툴이 살아 있어서 평가용 격리가 아니다. 관찰용일 뿐이다. 격리는 B에서 한다.

## 준비 (한 번만)

1. Claude Code CLI 설치 후 로그인 (PowerShell)
   ```powershell
   irm https://claude.ai/install.ps1 | iex
   claude --version
   ```
   `claude`를 한 번 실행해 구독 계정으로 로그인한다. `ANTHROPIC_API_KEY` 환경 변수가 있으면 API 과금으로 바뀌니 없는지 확인한다.

2. 툴 서버 실행 파일 만들기 (저장소에서)
   ```powershell
   make tools
   ```

3. 연습 폴더와 시험지 사본 만들기. 시나리오 이름이 보이지 않게 중립적인 이름(`case-a`)으로 복사하고, 허용 파일 4개만 복사한다(meta.json 제외).
   ```powershell
   $data = "C:\Users\koscom\oncall-practice-data"
   New-Item -ItemType Directory -Force "$data\case-a", "$data\out", "C:\Users\koscom\oncall-practice" | Out-Null
   foreach ($f in "alert.json", "logs.jsonl", "metrics.json", "deploys.json") {
     Copy-Item "C:\Users\koscom\oncall-agent\scenarios\snapshots\pool-exhaustion-01\$f" "$data\case-a\"
   }
   ```
   데이터를 작업 폴더(`oncall-practice`) 밖에 두는 이유: 에이전트가 파일 읽기 툴로 시험지를 직접 열려고 하면 권한 요청이 떠서 눈에 띈다.

4. `C:\Users\koscom\oncall-practice\.mcp.json` 작성 (UTF-8로 저장)
   ```json
   {
     "mcpServers": {
       "oncall": {
         "type": "stdio",
         "command": "C:\\Users\\koscom\\.gradle\\jdks\\eclipse_adoptium-21-amd64-windows.2\\bin\\java.exe",
         "args": [
           "-cp", "C:\\Users\\koscom\\oncall-agent\\tools\\build\\install\\tools\\lib\\*",
           "dev.oncall.tools.OncallMcpServer",
           "--snapshot", "C:\\Users\\koscom\\oncall-practice-data\\case-a",
           "--report-out", "C:\\Users\\koscom\\oncall-practice-data\\out\\report.json",
           "--evidence-out", "C:\\Users\\koscom\\oncall-practice-data\\out\\evidence.jsonl"
         ]
       }
     }
   }
   ```
   - java 경로를 직접 쓰는 이유: PATH의 기본 java가 17이라 시작 스크립트(`tools.bat`)로는 실행되지 않는다(Gradle이 받아 둔 JDK 21 사용).
   - 서버 프로세스 하나가 조사 한 건이다. 다시 조사하려면 Claude Code를 새로 띄우고, `out` 폴더를 비운다.

## 실행

```powershell
cd C:\Users\koscom\oncall-practice
claude
```
1. 프로젝트 MCP 서버(`oncall`) 사용을 묻으면 승인한다.
2. `/mcp`로 oncall이 연결됐고 툴 4개(search_logs, get_metrics, get_recent_deploys, submit_report)가 보이는지 확인한다.
3. 아래 요청을 그대로 붙여 넣는다.
   ```
   다음 알림의 원인을 조사해 줘. oncall MCP 툴만 써. 결론은 submit_report로 제출해.
   알림: HighErrorRate (service=order-api, severity=page)
   내용: order-api 5xx 비율이 5%를 넘었다 (현재 17.17%)
   발생: 2026-10-07T12:18:55.502Z
   ```
4. 시작 전과 끝난 뒤에 `/usage`로 구독 한도 소모를 확인하고, `out\report.json`과 `out\evidence.jsonl`을 열어 본다.
5. 정답은 조사가 끝난 뒤에 `scenarios/labels/pool-exhaustion-01.yaml`에서 확인한다.

## 관찰 기록 (직접 작성)

### 1. 연결
- `/mcp`에 보인 상태와 툴 목록:
- 툴 설명(한국어)을 에이전트가 이해한 것 같은가:

### 2. 호출 순서
| 순서 | 툴 | 입력 | 결과 요약 (증거 번호) | 왜 불렀다고 했나 |
|---|---|---|---|---|
| 1 | | | | |
| 2 | | | | |

### 3. 증거와 리포트
- 리포트에 인용한 번호와 발췌문:
- 발췌문이 해당 결과에 실제로 있는가 (`evidence.jsonl`과 대조):
- submit_report가 거부된 적이 있나, 거부 메시지를 보고 고쳤나:

### 4. 막힌 곳, 이상한 점
- 잘린 결과(note, truncated)를 보고 범위를 좁혔나:
- 시간 범위(start, end) 입력에서 헤맸나:
- 내장 툴(Read, Bash 등)로 우회하려 했나:
- 그 밖에:

### 5. 결과
- 원인 카테고리 (정답과 일치?):
- 걸린 시간, 툴 호출 수, 비용 추정치:

### 6. 러너(B)와 툴 개선에 반영할 것
-
