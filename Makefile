# Forex Signal Platform. Maven only through ./mvnw (never system mvn).
# Docker targets need your user in the "docker" group (log out and in again after adding it).
SHELL := /bin/bash
JAR   := engine/target/forex-signals.jar
CONF  ?= $(HOME)/.forexsignals/conf.xml

.PHONY: help up down db-wait test unit jar run-live run-replay

help:
	@echo "make up | down | test | unit | jar | run-live | run-replay FROM=<ISO> TO=<ISO>"

# Start Percona PG18 and wait until its healthcheck passes.
up:
	@test -f .env || { echo ".env missing: cp .env.example .env and set PG_PASSWORD"; exit 1; }
	docker compose up -d db
	@$(MAKE) --no-print-directory db-wait

db-wait:
	@for i in $$(seq 1 60); do \
	  s=$$(docker inspect -f '{{.State.Health.Status}}' forexsignals-db 2>/dev/null); \
	  if [ "$$s" = "healthy" ]; then echo "forexsignals-db: healthy"; exit 0; fi; sleep 2; \
	done; echo "forexsignals-db did not become healthy"; docker compose logs --tail=50 db; exit 1

down:
	docker compose down

# Full suite: Surefire unit tests + Failsafe *IT (Testcontainers Percona).
test:
	./mvnw -B verify

unit:
	./mvnw -B test

jar:
	./mvnw -B -DskipTests package

run-live: jar
	java -jar $(JAR) --mode=live --config=$(CONF) --feed=$${FEED:-deriv}

run-replay: jar
	java -jar $(JAR) --mode=replay --config=$(CONF) --feed=$${FEED:-deriv} --from=$(FROM) --to=$(TO) --compare-m5
