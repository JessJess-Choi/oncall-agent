.PHONY: up down capture test eval lock-golden

# Windows의 make는 셸이 cmd일 수 있으므로 셸 문법에 기대지 않는다.
ifeq ($(OS),Windows_NT)
# make가 명령을 직접 실행하든 sh로 실행하든 찾을 수 있게 절대경로로 부른다.
GRADLE := "$(CURDIR)/gradlew.bat"
else
GRADLE := ./gradlew
endif

up down capture:
	$(error $@: 1주차에 구현한다)

test:
	$(GRADLE) test

# make eval / make eval SCENARIOS=a,b
eval:
	$(GRADLE) -q :eval:run $(if $(SCENARIOS),--args="--scenarios $(SCENARIOS)")

# 정답·스냅샷 해시를 다시 고정한다. 사람만 실행한다.
lock-golden:
	$(GRADLE) -q :eval:lockGolden
