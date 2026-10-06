.PHONY: up down down-clean capture test run eval lock-golden

# Windows의 make는 셸이 cmd일 수 있으므로 셸 문법에 기대지 않는다.
ifeq ($(OS),Windows_NT)
# make가 명령을 직접 실행하든 sh로 실행하든 찾을 수 있게 절대경로로 부른다.
GRADLE := "$(CURDIR)/gradlew.bat"
else
GRADLE := ./gradlew
endif

COMPOSE := docker compose -f infra/docker-compose.yml

# 기준 버전으로 기동한다. 첫 기동은 시드 데이터(주문 400만 건) 생성에 몇 분 걸린다.
up:
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

# 볼륨(DB 시드, 메트릭, 로그)까지 지운다.
down-clean:
	$(COMPOSE) down -v

capture:
	$(error $@: 1주차 3단계에서 구현한다)

test:
	$(GRADLE) test

# make run SCENARIO=<id> RUNNER=claude_code|own_loop
run:
	$(error $@: 2주차에 구현한다)

# make eval / make eval SCENARIOS=a,b
eval:
	$(GRADLE) -q :eval:run $(if $(SCENARIOS),--args="--scenarios $(SCENARIOS)")

# 정답·스냅샷 해시를 다시 고정한다. 사람만 실행한다.
lock-golden:
	$(GRADLE) -q :eval:lockGolden
